import type { ApiDiff, ApiLogResult, DiffStatus, VersionDiffReport } from './types';

/**
 * The Release Impact "test plan" model — the Summary view and the Summary PDF are both rendered from this, so
 * a manager reads the same four buckets and the same words on screen and on paper:
 *
 *   BAU regression        live behaviour changes            -> test the current app
 *   Backward compatibility old app versions still call it   -> old client against the new build
 *   New app only           versioned to the release          -> new-app team only, old app unaffected
 *   Shared code            one class, several route versions -> test every version that runs it
 *
 * Everything is derived from the existing {@link VersionDiffReport}; no backend data is added. Wording is
 * deliberately terse: one phrase per change, one to three short imperatives per test focus.
 */

export type Risk = 'High' | 'Medium' | 'Low';
export type Audience = 'BAU team' | 'New-app team' | 'Both teams';
export const RISK_RANK: Record<Risk, number> = { High: 0, Medium: 1, Low: 2 };

export interface PlanRow {
  a: ApiDiff;
  /** Which bucket the API's own change falls in (BC is the BAU set seen from the old client). */
  bucket: 'bau' | 'new';
  /** What changed — one precise phrase (e.g. "channel: MOBILE → APP"). */
  what: string;
  /** Where it lives — route / class / author, for the small print. */
  detail?: string;
  /** Test focus — 1–3 short imperatives. */
  test: string[];
  who: Audience;
  risk: Risk;
  /** Backward compatibility applies (old app versions still call what changed). */
  bc: boolean;
  /** Old versions that still call this (BC rows). */
  oldVersions: string[];
  /** The version whose log result verifies this row (BAU rows → the live route's version). */
  logVersion?: string | null;
}

export interface SharedRoute { group: 'This release' | 'Live now' | 'Upcoming' | 'Untraced'; route: string; api?: string | null; version: string }
export interface SharedClass {
  name: string;
  file?: string;
  versions: string[];
  authors?: string;
  removed: boolean;
  routes: SharedRoute[];
  apis: string[];
}

export interface TestPlan {
  version: string;
  snapshot: boolean;
  inScope: number;
  unchanged: number;
  bau: PlanRow[];
  bc: PlanRow[];
  newOnly: PlanRow[];
  shared: SharedClass[];
  /** One line: counts per bucket. */
  verdict: string;
}

// ---- shared predicates (mirror the detailed view / backend rules) ----
export function riskOf(a: ApiDiff): Risk { return (a.risk as Risk) || 'Low'; }
export function bauRouteModified(a: ApiDiff): boolean { return !!a.bauRouteEdits?.length; }
export function bauRouteRemoval(a: ApiDiff): boolean {
  return !!a.bauRouteEdits?.some((e) => e.routeRemoved || e.removedSteps?.length || e.removedKeys?.length);
}
/** Reaches the old (BAU) app: a shared class it runs changed, or a live route was edited in place. */
export function isBauImpact(a: ApiDiff): boolean { return !!a.codeChanged || bauRouteModified(a); }
export function effectiveStatus(a: ApiDiff): DiffStatus {
  return (a.status === 'NEW' || a.status === 'UNCHANGED') && isBauImpact(a) ? 'CHANGED' : (a.status as DiffStatus);
}

const ROUTE_VER = /^R(\d+(?:\.\d+)*)_/;
export function normVer(v?: string | null): string { return v && v !== 'N/A' && v !== 'BASE' ? v : 'BASE'; }
export function routeVersion(routeId: string): string | null { const m = ROUTE_VER.exec(routeId || ''); return m ? m[1] : null; }
function versionOfPath(path: string[]): string { for (const r of path) { const v = routeVersion(r); if (v) return v; } return 'BASE'; }
function cmpVerDesc(a: string, b: string): number {
  const pa = a.split('.').map(Number), pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) { const d = (pb[i] || 0) - (pa[i] || 0); if (d) return d; }
  return 0;
}

// ---- small formatters ----
const cap = (s: string, n = 64) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
/** `<to uri="bean:x"/>` / `to bean:x` → `bean:x`; otherwise the trimmed step. */
function stepLabel(s: string): string {
  const m = /uri\s*=\s*"([^"]+)"/.exec(s) || /^\s*to\s+(\S+)/i.exec(s);
  return cap((m ? m[1] : s).trim());
}
/** Short template name: from `META-INF/`, else `templates/…`, else the last two segments. */
function shortTemplate(p: string): string {
  const n = (p || '').replace(/\\/g, '/');
  const mi = n.search(/META-INF\//i); if (mi >= 0) return n.slice(mi);
  const t = n.match(/\/templates\/(.+)$/i); if (t) return 'templates/' + t[1];
  return n.split('/').filter(Boolean).slice(-2).join('/') || n;
}
/** `http://host:port/bfs/ft/own/submit` → `/bfs/ft/own/submit`. */
export function shortBackend(b: string): string { return cap((b || '').replace(/^[a-z]+:\/\/[^/]+/i, '') || b, 48); }
const more = (n: number) => (n > 0 ? ` +${n} more` : '');

/** `statusProcessor (StatusProcessor.java) · 19.18.0 — Alice, Bob  — REMOVED` → its parts. */
export function parseClassLabel(label: string): { name: string; file?: string; versions: string[]; authors?: string; removed: boolean } {
  const removed = /—\s*REMOVED\s*$/.test(label);
  const s = label.replace(/\s*—\s*REMOVED\s*$/, '');
  const m = /^(.+?)\s*\(([^)]+\.java)\)\s*(?:·\s*([^—]+?))?\s*(?:—\s*(.+))?$/.exec(s);
  if (!m) return { name: s.trim(), versions: [], removed };
  return {
    name: m[1].trim(), file: m[2].trim(),
    versions: (m[3] || '').split(/[,\s]+/).map((v) => v.trim()).filter(Boolean),
    authors: m[4]?.trim() || undefined, removed,
  };
}

// ---- row builders ----
function bauWhat(a: ApiDiff): { what: string; detail?: string } {
  const parts: string[] = [];
  const where: string[] = [];
  for (const e of a.bauRouteEdits || []) {
    // Every list may arrive null from the backend — guard each one (a null here blanked the whole Summary).
    const removedSteps = e.removedSteps || [], addedSteps = e.addedSteps || [];
    const removedKeys = e.removedKeys || [], addedKeys = e.addedKeys || [], changedValues = e.changedValues || [];
    where.push(e.route + (e.changedBy?.length ? ' · ' + e.changedBy.join(', ') : ''));
    if (e.routeRemoved) { parts.push('Live route removed'); continue; }
    removedSteps.forEach((s) => parts.push('Step removed: ' + stepLabel(s)));
    addedSteps.forEach((s) => parts.push('Step added: ' + stepLabel(s)));
    changedValues.forEach((v) => parts.push(`${v.key}: ${v.before} → ${v.after}`));
    removedKeys.forEach((k) => parts.push('Field removed: ' + k));
    addedKeys.forEach((k) => parts.push('Field added: ' + k));
    if (!removedSteps.length && !addedSteps.length && !changedValues.length && !removedKeys.length && !addedKeys.length && (e.payloadDiff?.length || e.payloadFiles?.length)) {
      parts.push('Request template changed' + (e.payloadFiles?.[0] ? ': ' + shortTemplate(e.payloadFiles[0]) : ''));
    }
  }
  if (a.codeChanged) {
    const names = (a.changedClasses || []).map((c) => parseClassLabel(c));
    const removed = names.filter((c) => c.removed).map((c) => c.name);
    const changed = names.filter((c) => !c.removed).map((c) => c.name);
    if (changed.length) parts.push('Shared class changed: ' + changed.slice(0, 2).join(', ') + more(changed.length - 2));
    if (removed.length) parts.push('Shared class removed: ' + removed.slice(0, 2).join(', ') + more(removed.length - 2));
    const live = (a.impactedRoutes || []).filter((r) => r.category === 'BAU').map((r) => (r.routePath || [])[(r.routePath || []).length - 1] || "");
    if (live.length) where.push('live route ' + live.slice(0, 2).join(', ') + more(live.length - 2));
  }
  const what = parts.length ? parts.slice(0, 2).join(' · ') + more(parts.length - 2) : 'Live behaviour changed';
  return { what, detail: where.length ? Array.from(new Set(where)).slice(0, 2).join(' · ') : undefined };
}

/** Versions of the LIVE routes this change reaches (BAU edits' routes, BAU re-test routes, the compared lower version). */
function liveVersions(a: ApiDiff): string[] {
  const vs = new Set<string>();
  for (const e of a.bauRouteEdits || []) { const v = routeVersion(e.route); if (v) vs.add(v); }
  for (const r of a.impactedRoutes || []) if (r.category === 'BAU') { const v = versionOfPath(r.routePath); if (v !== 'BASE') vs.add(v); }
  if (!vs.size && a.lowerVersion && a.lowerVersion !== 'N/A') vs.add(a.lowerVersion);
  if (a.targetVersion) vs.delete(a.targetVersion);
  return Array.from(vs).sort(cmpVerDesc);
}

function bauTests(a: ApiDiff, live: string[]): string[] {
  const t: string[] = [`Run on the ${live[0] ? live[0] + ' ' : 'current '}app`];
  const edits = a.bauRouteEdits || [];
  if (edits.some((e) => e.routeRemoved)) t.push('Confirm callers survive the removed route');
  else if (edits.some((e) => e.removedSteps?.length || e.removedKeys?.length)) t.push('Confirm nothing depends on the removed step/field');
  const v = edits.flatMap((e) => e.changedValues || [])[0];
  if (v) t.push(`Host accepts ${v.key}=${v.after}`);
  else if (edits.some((e) => e.addedSteps?.length || e.addedKeys?.length || e.payloadDiff?.length)) t.push('Host accepts the changed request');
  if (a.codeChanged) t.push('Response unchanged vs today');
  return t.slice(0, 3);
}

function bcTests(a: ApiDiff): string[] {
  const t = ['Old client → new build passes'];
  if (bauRouteRemoval(a)) t.push('Works without the removed step/field');
  else t.push('Response contract unchanged');
  return t;
}

function newWhat(a: ApiDiff, ver: string): { what: string; detail?: string } {
  if (a.status === 'NEW') return { what: 'New API', detail: a.targetRoute ? a.targetRoute + (a.authors?.length ? ' · ' + a.authors.join(', ') : '') : undefined };
  const pc = a.payloadChange;
  const parts: string[] = [];
  if (pc?.addedKeys?.length) parts.push(`+${pc.addedKeys.length} field${pc.addedKeys.length === 1 ? '' : 's'}: ${pc.addedKeys.slice(0, 3).join(', ')}${more(pc.addedKeys.length - 3)}`);
  if (pc?.removedKeys?.length) parts.push(`−${pc.removedKeys.length} field${pc.removedKeys.length === 1 ? '' : 's'}: ${pc.removedKeys.slice(0, 3).join(', ')}${more(pc.removedKeys.length - 3)}`);
  if (parts.length) return { what: 'Request ' + parts.join(' · '), detail: a.targetRoute || undefined };
  const b = a.backendVersionChanges?.[0];
  if (b) return { what: `Backend svc ${b.fromVersion ?? '?'} → ${b.toVersion ?? '?'}` + more((a.backendVersionChanges?.length ?? 1) - 1), detail: shortBackend(b.backend) };
  const diffs = a.routeDiffs || [];
  const added = diffs.reduce((n, d) => n + (d.added?.length ?? 0), 0) + (a.addedRoutes?.length ?? 0);
  const removed = diffs.reduce((n, d) => n + (d.removed?.length ?? 0), 0) + (a.removedRoutes?.length ?? 0);
  if (added || removed) return { what: `Route logic changed (${added ? '+' + added : ''}${added && removed ? ' ' : ''}${removed ? '−' + removed : ''} step${added + removed === 1 ? '' : 's'})`, detail: a.targetRoute || undefined };
  return { what: `Changed on ${ver}`, detail: a.targetRoute || undefined };
}

function newTests(a: ApiDiff, ver: string): string[] {
  if (a.status === 'NEW') return [`End to end on ${ver}`];
  if (a.payloadChange?.addedKeys?.length || a.payloadChange?.removedKeys?.length) return [`Fields accepted on ${ver}`];
  const b = a.backendVersionChanges?.[0];
  if (b) return [`Call succeeds on svc ${b.toVersion ?? '?'}`];
  return [`Changed steps on ${ver}`];
}

function hasNewAppWork(a: ApiDiff): boolean {
  return a.status === 'NEW' || !!a.payloadChange?.addedKeys?.length || !!a.payloadChange?.removedKeys?.length
    || !!a.backendVersionChanges?.length || (a.routeDiffs || []).some((d) => d.added?.length || d.removed?.length)
    || !!a.addedRoutes?.length || !!a.removedRoutes?.length;
}

const GROUP: Record<string, SharedRoute['group']> = { Current: 'This release', BAU: 'Live now', Future: 'Upcoming' };
const GROUP_ORDER: SharedRoute['group'][] = ['This release', 'Live now', 'Upcoming', 'Untraced'];

function sharedClasses(apis: ApiDiff[]): SharedClass[] {
  const byName = new Map<string, SharedClass>();
  for (const a of apis) {
    if (!a.codeChanged) continue;
    for (const label of a.changedClasses || []) {
      const p = parseClassLabel(label);
      let sc = byName.get(p.name);
      if (!sc) { sc = { name: p.name, file: p.file, versions: [], authors: p.authors, removed: p.removed, routes: [], apis: [] }; byName.set(p.name, sc); }
      for (const v of p.versions) if (!sc.versions.includes(v)) sc.versions.push(v);
      if (!sc.apis.includes(a.api)) sc.apis.push(a.api);
      for (const r of a.impactedRoutes || []) {
        const route = (r.routePath || [])[(r.routePath || []).length - 1] || '';
        if (!route || sc.routes.some((x) => x.route === route)) continue;
        sc.routes.push({ group: r.api ? GROUP[r.category] : 'Untraced', route, api: r.api, version: versionOfPath(r.routePath) });
      }
    }
  }
  for (const sc of byName.values()) sc.routes.sort((x, y) => GROUP_ORDER.indexOf(x.group) - GROUP_ORDER.indexOf(y.group) || cmpVerDesc(x.version, y.version));
  return Array.from(byName.values());
}

/** Build the plan for one report. */
export function buildTestPlan(report: VersionDiffReport): TestPlan {
  const ver = (report.version && report.version.trim()) || report.appVersion || 'N/A';
  const snapshot = !!report.snapshot;
  const inScope = report.snapshotCount ?? report.apis.length;
  const toVerify = (snapshot
    ? report.apis.filter((a) => isBauImpact(a))
    : report.apis.filter((a) => effectiveStatus(a) !== 'UNCHANGED' && a.status !== 'SNAPSHOT'))
    .sort((x, y) => RISK_RANK[riskOf(x)] - RISK_RANK[riskOf(y)] || x.api.localeCompare(y.api));

  const bau: PlanRow[] = [];
  const newOnly: PlanRow[] = [];
  for (const a of toVerify) {
    const row = planRowFor(a, ver);
    (row.bucket === 'bau' ? bau : newOnly).push(row);
  }
  const bc: PlanRow[] = bau.map((r) => ({ ...r, test: bcTests(r.a), what: r.oldVersions.length ? 'Still called by ' + r.oldVersions.join(', ') : 'Still called by the current app' }));
  const shared = sharedClasses(report.apis);
  const unchanged = snapshot ? 0 : (report.unchangedCount ?? 0);
  const verdict = snapshot
    ? `${inScope} APIs at latest version: ${bau.length} with live behaviour change · ${shared.length} shared class${shared.length === 1 ? '' : 'es'} changed.`
    : `${inScope} APIs in scope: ${bau.length} BAU regression · ${bc.length} backward-compat · ${newOnly.length} new app only · ${unchanged} unchanged.`;
  return { version: ver, snapshot, inScope, unchanged, bau, bc, newOnly, shared, verdict };
}

/** The plan row for ONE API — the Detailed card and Detailed PDF use this so every view says the same thing. */
export function planRowFor(a: ApiDiff, ver: string): PlanRow {
  if (isBauImpact(a)) {
    const live = liveVersions(a);
    const { what, detail } = bauWhat(a);
    return { a, bucket: 'bau', what, detail, test: bauTests(a, live), who: hasNewAppWork(a) ? 'Both teams' : 'BAU team',
      risk: riskOf(a), bc: true, oldVersions: live, logVersion: live[0] ?? a.lowerVersion ?? null };
  }
  const { what, detail } = newWhat(a, ver);
  return { a, bucket: 'new', what, detail, test: newTests(a, ver), who: 'New-app team', risk: riskOf(a), bc: false, oldVersions: [], logVersion: a.targetVersion };
}
/** The backward-compatibility checks for a BAU-impact API (old client against the new build). */
export function bcTestsFor(a: ApiDiff): string[] { return isBauImpact(a) ? bcTests(a) : []; }
/** One-line verdict for a card / PDF block: bucket + the precise change. */
export function verdictFor(row: PlanRow): string {
  return (row.bucket === 'bau' ? 'BAU regression' : 'New app only') + ' · ' + row.what;
}
/** Shared-code re-test groups, in reader's words (Current/BAU/Future are the backend's category names). */
export const SHARED_GROUP: Record<'Current' | 'BAU' | 'Future' | 'Unknown', { label: string; desc: string }> = {
  Current: { label: 'This release', desc: 'verify here' },
  BAU: { label: 'Live now', desc: 'regression-test the current app' },
  Future: { label: 'Upcoming', desc: 'pre-test now; not flagged again under its own version' },
  Unknown: { label: 'Untraced', desc: 'not wired to a controller — trace and verify manually' },
};

// ---- one vocabulary ----
export interface Bucket { key: 'bau' | 'bc' | 'new' | 'shared'; title: string; question: string; who: string; define: string }
export function buckets(ver: string): Bucket[] {
  return [
    { key: 'bau', title: 'BAU regression', question: 'Live behaviour changes — test the current app', who: 'BAU team', define: 'Live behaviour changes: a production route or code it runs was edited. Regression-test the current app.' },
    { key: 'bc', title: 'Backward compatibility', question: 'Old app versions still call what changed', who: 'BAU team', define: 'Old app versions still call what changed. An old client against the new build must pass.' },
    { key: 'new', title: 'New app only', question: `Versioned to ${ver} — old app unaffected`, who: 'New-app team', define: `Versioned to ${ver}: new route, new fields or a backend bump on the new route. The old app never sees it.` },
    { key: 'shared', title: 'Shared code', question: 'One class, several route versions — test each', who: 'Both teams', define: 'One class serves several route versions. Test every version that runs the changed method: this release, live, upcoming.' },
  ];
}

// ---- tested state from a log result (text only; the UI adds icons) ----
export type TestedState = 'pass' | 'fail' | 'partial' | 'none' | 'ran';
export function testedState(l?: ApiLogResult): { state: TestedState; label: string } {
  if (!l || !l.tested) return { state: 'none', label: 'Not tested' };
  if (l.status === 'SUCCESS') return { state: 'pass', label: `Passed${l.attempts > 1 ? ' · ' + l.attempts + '×' : ''}` };
  if (l.status === 'PARTIAL') return { state: 'partial', label: `Partial · ${l.successCount}/${l.attempts}` };
  if (l.status === 'FAILED' || l.status === 'TIMEOUT') return { state: 'fail', label: `Failed · ${l.failureCount}/${l.attempts}` };
  return { state: 'ran', label: 'Ran' };
}
/** A row's log result from a (version → api → result) map, falling back to the compared-version map. */
export function logForRow(row: PlanRow, byVer?: Record<string, Record<string, ApiLogResult>>, compared?: Record<string, ApiLogResult>): ApiLogResult | undefined {
  const v = normVer(row.logVersion ?? row.a.targetVersion);
  return byVer?.[v]?.[row.a.api] ?? byVer?.[normVer(row.a.targetVersion)]?.[row.a.api] ?? compared?.[row.a.api];
}
