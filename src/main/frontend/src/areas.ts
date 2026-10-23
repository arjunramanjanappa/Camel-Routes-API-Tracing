import type { PlanRow, Risk } from './testPlan';
import { RISK_RANK } from './testPlan';
import { areaOf, subOf, type FeatureLabels } from './labels';

/**
 * The Release Impact reports' functional categorisation: the test-plan rows regrouped by business AREA, each
 * area with its sub-features, a risk + one-line reason, the backward-compatibility position and a short testing
 * focus. This is how a release team talks about a release ("Funds Transfer & Payments — Medium — request/response
 * changes on the new routes"), so the Summary and Detailed PDFs lead with it. Derived from the plan rows only.
 */

export interface AreaSub { key: string; label: string; rows: PlanRow[] }
export interface Area {
  key: string;
  label: string;
  rows: PlanRow[];
  /** Sub-features in display order; rows with no sub-feature are in `direct`. */
  subs: AreaSub[];
  direct: PlanRow[];
  risk: Risk;
  /** One line: what kind of changes make up this area, e.g. "2 live routes edited · 12 request/response changes on new routes". */
  reason: string;
  bauCount: number;
  /** Old app versions that still call something this area changed (BC); empty = old app unaffected. */
  bcVersions: string[];
  /** Deduplicated test-focus lines across the area's rows (BAU first), capped. */
  focus: string[];
}

/** A short change tag for one API in a report list, e.g. "Request/response changed", "Live route edited". */
export function changeTag(row: PlanRow): string {
  const a = row.a;
  if (row.bucket === 'bau') {
    if (a.bauRouteEdits?.some((e) => e.routeRemoved)) return 'Live route removed';
    if (a.bauRouteEdits?.length && a.codeChanged) return 'Live route edited · Shared class changed';
    if (a.bauRouteEdits?.length) return 'Live route edited';
    return 'Shared class changed';
  }
  if (a.status === 'NEW') return 'New API';
  if (a.payloadChange?.addedKeys?.length || a.payloadChange?.removedKeys?.length) return 'Request/response changed';
  if (a.backendVersionChanges?.length) return 'Backend version changed';
  return 'Route logic changed';
}

function plural(n: number, one: string, many = one + 's'): string { return `${n} ${n === 1 ? one : many}`; }

/** Area reason: counts per change kind, most serious first. */
function reasonOf(rows: PlanRow[]): string {
  let edited = 0, removed = 0, shared = 0, neu = 0, payload = 0, backend = 0, logic = 0;
  for (const r of rows) {
    const t = changeTag(r);
    if (t === 'Live route removed') removed++;
    else if (t.startsWith('Live route edited')) { edited++; if (t.includes('Shared')) shared++; }
    else if (t === 'Shared class changed') shared++;
    else if (t === 'New API') neu++;
    else if (t === 'Request/response changed') payload++;
    else if (t === 'Backend version changed') backend++;
    else logic++;
  }
  const parts: string[] = [];
  if (removed) parts.push(plural(removed, 'live route removed', 'live routes removed'));
  if (edited) parts.push(plural(edited, 'live route edited', 'live routes edited'));
  if (shared) parts.push(plural(shared, 'shared class change'));
  if (payload) parts.push(plural(payload, 'request/response change') + ' on new routes');
  if (backend) parts.push(plural(backend, 'backend version bump'));
  if (neu) parts.push(plural(neu, 'new API'));
  if (logic) parts.push(plural(logic, 'route logic change'));
  return parts.join(' · ') || 'no change';
}

function maxRisk(rows: PlanRow[]): Risk {
  let best: Risk = 'Low';
  for (const r of rows) if (RISK_RANK[r.risk] < RISK_RANK[best]) best = r.risk;
  return best;
}

/** Group plan rows (BAU + new-app; BC repeats BAU so it is not passed) into areas, highest impact first. */
export function buildAreas(rows: PlanRow[], labels: FeatureLabels = {}): Area[] {
  const byArea = new Map<string, Area>();
  for (const r of rows) {
    const a = areaOf(r.a.api, labels);
    let area = byArea.get(a.key);
    if (!area) {
      area = { key: a.key, label: a.label, rows: [], subs: [], direct: [], risk: 'Low', reason: '', bauCount: 0, bcVersions: [], focus: [] };
      byArea.set(a.key, area);
    }
    area.rows.push(r);
    const s = subOf(r.a.api, labels);
    if (!s) area.direct.push(r);
    else {
      let sub = area.subs.find((x) => x.key === s.key);
      if (!sub) { sub = { key: s.key, label: s.label, rows: [] }; area.subs.push(sub); }
      sub.rows.push(r);
    }
  }
  for (const area of byArea.values()) {
    area.bauCount = area.rows.filter((r) => r.bucket === 'bau').length;
    area.risk = maxRisk(area.rows);
    area.reason = reasonOf(area.rows);
    area.bcVersions = Array.from(new Set(area.rows.flatMap((r) => r.oldVersions))).sort((x, y) => cmpVerDesc(x, y));
    const focus: string[] = [];
    for (const r of [...area.rows.filter((r) => r.bucket === 'bau'), ...area.rows.filter((r) => r.bucket !== 'bau')]) {
      for (const t of r.test) if (!focus.includes(t)) focus.push(t);
    }
    area.focus = focus.slice(0, 6);
    area.subs.sort((x, y) => y.rows.length - x.rows.length || x.label.localeCompare(y.label));
    const byRisk = (x: PlanRow, y: PlanRow) => RISK_RANK[x.risk] - RISK_RANK[y.risk] || x.a.api.localeCompare(y.a.api);
    area.subs.forEach((s) => s.rows.sort(byRisk));
    area.direct.sort(byRisk);
  }
  // Highest impact first: live (BAU) changes, then High risk, then size; ties by label.
  return Array.from(byArea.values()).sort((x, y) =>
    y.bauCount - x.bauCount
    || RISK_RANK[x.risk] - RISK_RANK[y.risk]
    || y.rows.length - x.rows.length
    || x.label.localeCompare(y.label));
}

function cmpVerDesc(a: string, b: string): number {
  const pa = a.split('.').map(Number), pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) { const d = (pb[i] || 0) - (pa[i] || 0); if (d) return d; }
  return 0;
}
