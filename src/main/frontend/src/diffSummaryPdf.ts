import type { ApiDiff, ApiLogResult, DiffStatus } from './types';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, type Ramp } from './pdfReport';
import type { ModuleDiff } from './diffPdf';
import { groupItemsByFeature } from './feature';

/**
 * The 1–2 page leadership Summary PDF — a RAG overview (what's changing, how risky, is it tested) for
 * release managers, coordinators and delivery leads. Derived entirely from the existing report; the full
 * developer report is {@link exportDiffPdf} (route/class/test detail).
 */

function effectiveStatus(a: ApiDiff): DiffStatus { return a.status === 'NEW' && a.codeChanged ? 'CHANGED' : a.status as DiffStatus; }
function riskOf(a: ApiDiff): 'High' | 'Medium' | 'Low' { return (a.risk as 'High' | 'Medium' | 'Low') || 'Low'; }
function whatChanged(a: ApiDiff): { label: string; ramp: Ramp } {
  if (effectiveStatus(a) === 'NEW') return { label: 'New API', ramp: PAL.blue };
  if (a.codeChanged) return { label: 'Shared code changed', ramp: PAL.purple };
  if (a.payloadChange?.removedKeys?.length || a.payloadChange?.addedKeys?.length) return { label: 'Request/response changed', ramp: PAL.amber };
  if (a.backendVersionChanges?.length) return { label: 'Backend version changed', ramp: PAL.gray };
  return { label: 'Logic changed', ramp: PAL.gray };
}
function riskRamp(r: 'High' | 'Medium' | 'Low'): Ramp { return r === 'High' ? PAL.red : r === 'Medium' ? PAL.amber : PAL.green; }

function normVer(v?: string | null): string { return v && v !== 'N/A' && v !== 'BASE' ? v : 'BASE'; }
function logAt(logByVer: Record<string, Record<string, ApiLogResult>> | undefined, version: string | null | undefined, api?: string | null): ApiLogResult | undefined {
  return api ? logByVer?.[normVer(version)]?.[api] : undefined;
}
function testedRamp(l?: ApiLogResult): { label: string; ramp: Ramp } {
  if (!l || !l.tested) return { label: 'Not tested', ramp: PAL.gray };
  if (l.status === 'SUCCESS') return { label: 'Passed', ramp: PAL.green };
  if (l.status === 'PARTIAL') return { label: 'Partial', ramp: PAL.amber };
  if (l.status === 'FAILED' || l.status === 'TIMEOUT') return { label: 'Failed', ramp: PAL.red };
  return { label: 'Ran', ramp: PAL.gray };
}
function pillCell(label: string, ramp: Ramp) { return { pill: { label, fill: ramp.fill, text: ramp.text, stripe: ramp.bar } }; }

export async function exportDiffSummaryPdf(mods: ModuleDiff[], app?: string) {
  const r = await ReportDoc.create();
  const first = mods.find((m) => m.report)?.report;
  const ver = first?.version || 'N/A';
  const country = first?.country;
  const appVersion = mods.find((m) => m.report?.appVersion)?.report?.appVersion || null;
  const multi = mods.filter((m) => m.report).length > 1;
  const hasAnyLog = mods.some((m) => m.logByVer && Object.keys(m.logByVer).length);

  // Aggregate the counts across modules.
  const tot = { inScope: 0, changed: 0, added: 0, unchanged: 0, high: 0, bc: 0, code: 0 };
  for (const m of mods) {
    const rep = m.report; if (!rep) continue;
    tot.inScope += rep.snapshot ? (rep.snapshotCount ?? rep.apis.length) : rep.apis.length;
    tot.changed += rep.changedCount ?? 0;
    tot.added += rep.newCount ?? 0;
    tot.unchanged += rep.unchangedCount ?? 0;
    tot.high += rep.highRiskCount ?? 0;
    tot.bc += rep.backwardCompatCount ?? 0;
    tot.code += rep.codeChangedCount ?? 0;
  }

  // The actionable rows (what to verify) across modules, highest-risk first.
  type Row = { api: string; module: string; a: ApiDiff; log?: ApiLogResult };
  const rows: Row[] = [];
  for (const m of mods) {
    const rep = m.report; if (!rep) continue;
    const actionable = rep.snapshot
      ? rep.apis.filter((a) => a.codeChanged)
      : rep.apis.filter((a) => effectiveStatus(a) !== 'UNCHANGED' && a.status !== 'SNAPSHOT');
    for (const a of actionable) rows.push({ api: a.api, module: m.name, a, log: logAt(m.logByVer, a.targetVersion, a.api) });
  }
  const RANK = { High: 0, Medium: 1, Low: 2 } as const;
  rows.sort((x, y) => RANK[riskOf(x.a)] - RANK[riskOf(y.a)]);

  let passed = 0, failed = 0, notTested = 0;
  if (hasAnyLog) for (const row of rows) {
    if (row.log?.tested) { if (row.log.status === 'SUCCESS') passed++; else failed++; }
    else notTested++;
  }
  const verified = passed + failed;

  // ---- Page 1: cover ----
  r.titlePage('Release Impact — Summary',
    'For release managers, coordinators & delivery leads',
    [
      `${app ? app + ' · ' : ''}Release ${ver}${country ? ' · ' + country : ''}`,
      appVersion ? `Commit version(s): ${appVersion}` : '',
      'Generated ' + generatedStamp(),
    ].filter(Boolean));

  // ---- Release health ----
  r.bookmark('Release health');
  r.banner('Release health', PAL.blue, `Release ${ver}${country ? ' · ' + country : ''} — what this release touches.`);
  r.statBand([
    { n: tot.inScope, label: 'APIs in scope', ramp: PAL.blue },
    { n: tot.added, label: 'New', ramp: PAL.blue },
    { n: tot.changed, label: 'Changed', ramp: PAL.amber },
    { n: tot.unchanged, label: 'Unchanged', ramp: PAL.gray },
  ]);
  r.statBand([
    { n: tot.high, label: 'High risk', ramp: PAL.red },
    { n: tot.bc, label: 'Need backward-compat', ramp: PAL.amber },
    { n: rows.length, label: 'To verify', ramp: PAL.purple },
    { n: hasAnyLog ? verified : 0, label: hasAnyLog ? 'Verified' : 'Verified (no log)', ramp: PAL.green },
  ]);
  if (hasAnyLog) {
    r.paragraph(`Test readiness — ${verified} of ${rows.length} verified: ${passed} passed, ${failed} failed, ${notTested} not tested.`);
  }

  // ---- What to verify ----
  if (rows.length) {
    r.bookmark('What to verify');
    r.banner('What to verify', PAL.purple, 'The new & changed APIs this release needs verified, highest-risk first. Full route/class detail is in the Detailed report.');
    const cols = multi
      ? [{ header: 'API', w: 0.28, mono: true }, { header: 'Module', w: 0.16 }, { header: 'What changed', w: 0.24 }, { header: 'Risk', w: 0.14 }, { header: 'Tested', w: 0.18 }]
      : [{ header: 'API', w: 0.40, mono: true }, { header: 'What changed', w: 0.28 }, { header: 'Risk', w: 0.16 }, { header: 'Tested', w: 0.16 }];
    const rowCells = (row: Row) => {
      const wc = whatChanged(row.a);
      const rk = riskOf(row.a);
      const t = testedRamp(row.log);
      return [
        { text: row.api, mono: true, color: PAL.ink },
        ...(multi ? [row.module] : []),
        pillCell(wc.label, wc.ramp),
        pillCell(rk, riskRamp(rk)),
        hasAnyLog ? pillCell(t.label, t.ramp) : '—',
      ];
    };
    // Grouped by business feature (fx / manage / payment …), features A→Z, highest-risk first within each.
    for (const fg of groupItemsByFeature(rows, (row) => row.api)) {
      r.para(`${fg.feature}  (${fg.items.length})`, M, CONTENT_W, 'bold', 10.5, PAL.ink, 15);
      r.wrapTable(cols, fg.items.map(rowCells));
    }
  }

  // ---- Plain-English key ----
  r.legend('What the labels mean', [
    'New API — added this release; test end to end.',
    'Changed — existed before and behaves differently now (logic, backend version, request/response, or shared code); regression-test.',
    'Shared code changed — a common Java class was modified; older versions that use it must be re-tested too.',
    'Unchanged — identical behaviour to the previous version; no action.',
    'Risk (High/Medium/Low) — test priority. Tested — from the uploaded run log.',
  ]);

  const footer = `TraceGuard — Release ${ver}${country ? ' · ' + country : ''}${app ? ' · ' + app : ''} — Summary`;
  r.save(`TraceGuard-Release-${ver}-Summary.pdf`, footer);
}
