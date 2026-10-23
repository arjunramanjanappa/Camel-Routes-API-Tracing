import type { ApiLogResult } from './types';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, logWindowLine, passRateLine, ascii, type Ramp, type RGB } from './pdfReport';
import { appLabel } from './appName';
import { groupItemsByFeature } from './feature';
import type { ModuleDiff } from './diffPdf';
import { buildTestPlan, buckets, logForRow, testedState, type Bucket, type PlanRow, type SharedClass } from './testPlan';

/**
 * The one-page manager Summary PDF — the Release Impact test plan on A4: the verdict, the four counts, then
 * each bucket as a short table (what changed / test focus / who / tested), and the four definitions as the
 * legend. Built from the same {@link buildTestPlan} model as the on-screen Summary, so the words match.
 * The developer report is {@link exportDiffPdf}.
 */

type Cell = string | { pill: { label: string; fill: RGB; text: RGB; stripe: RGB } } | { text: string; mono?: boolean; color?: RGB };
const pill = (label: string, r: Ramp): Cell => ({ pill: { label: ascii(label), fill: r.fill, text: r.text, stripe: r.bar } });
const RAMP: Record<Bucket['key'], Ramp> = { bau: PAL.red, bc: PAL.purple, new: PAL.green, shared: PAL.blue };
const whoRamp = (who: PlanRow['who']): Ramp => (who === 'BAU team' ? PAL.red : who === 'New-app team' ? PAL.green : PAL.purple);
const riskRamp = (r: PlanRow['risk']): Ramp => (r === 'High' ? PAL.red : r === 'Medium' ? PAL.amber : PAL.green);
function testedCell(l?: ApiLogResult): Cell {
  const t = testedState(l);
  const r = t.state === 'pass' ? PAL.green : t.state === 'fail' ? PAL.red : t.state === 'partial' ? PAL.amber : PAL.gray;
  return pill(t.label, r);
}

export async function exportDiffSummaryPdf(mods: ModuleDiff[], app?: string, logWindow?: { start?: string; end?: string }) {
  const r = await ReportDoc.create();
  const withReport = mods.filter((m) => m.report);
  const first = withReport[0]?.report;
  const ver = first?.version || 'N/A';
  const country = first?.country;
  const appVersion = withReport.find((m) => m.report?.appVersion)?.report?.appVersion || null;
  const commits = withReport.reduce((n, m) => n + (m.report?.matchedCommits ?? 0), 0);
  const hasAnyLog = withReport.some((m) => m.logByVer && Object.keys(m.logByVer).length);
  const multi = withReport.length > 1;

  // One plan per module, merged into the four buckets (module name kept for the API cell when > 1 module).
  type R = { row: PlanRow; m: ModuleDiff };
  const plans = withReport.map((m) => ({ m, plan: buildTestPlan(m.report!) }));
  const merge = (pick: (p: ReturnType<typeof buildTestPlan>) => PlanRow[]): R[] =>
    plans.flatMap(({ m, plan }) => pick(plan).map((row) => ({ row, m })));
  const bau = merge((p) => p.bau), bc = merge((p) => p.bc), neu = merge((p) => p.newOnly);
  const shared: { s: SharedClass; m: ModuleDiff }[] = plans.flatMap(({ m, plan }) => plan.shared.map((s) => ({ s, m })));
  const inScope = plans.reduce((n, p) => n + p.plan.inScope, 0);
  const unchanged = plans.reduce((n, p) => n + p.plan.unchanged, 0);
  const sharedVersions = new Set(shared.flatMap(({ s }) => s.routes.map((x) => x.version))).size;
  const verdict = multi
    ? `${inScope} APIs in scope across ${withReport.length} modules: ${bau.length} BAU regression · ${bc.length} backward-compat · ${neu.length} new app only · ${unchanged} unchanged.`
    : plans[0]?.plan.verdict ?? '';
  const B = buckets(ver);
  const logOf = (x: R) => logForRow(x.row, x.m.logByVer);

  // Tested tally over the actionable rows (BAU + new-app; BC repeats BAU).
  let passed = 0, failed = 0;
  if (hasAnyLog) for (const x of [...bau, ...neu]) { const l = logOf(x); if (l?.tested) { if (l.status === 'SUCCESS') passed++; else failed++; } }

  // ---- Cover band + verdict + the four counts ----
  r.coverBand('Release Impact - Summary',
    [appLabel(app), country, `Release ${ver}`, appVersion ? `commit ${appVersion}${commits ? ' - ' + commits : ''}` : ''].filter((c): c is string => !!c),
    ['Generated ' + generatedStamp(),
     hasAnyLog ? passRateLine(passed, passed + failed) : null,
     hasAnyLog ? logWindowLine(logWindow?.start, logWindow?.end) : null]);
  r.bookmark('Summary');
  r.para(ascii(verdict), M, CONTENT_W, 'bold', 11, PAL.ink, 15);
  r.paragraph('');
  r.kpiBand([
    { n: bau.length, label: B[0].title, ramp: RAMP.bau },
    { n: bc.length, label: 'Backward compat', ramp: RAMP.bc },
    { n: neu.length, label: B[2].title, ramp: RAMP.new },
    { n: shared.length ? `${shared.length} / ${sharedVersions}` : 0, label: shared.length ? 'Shared code: classes / versions' : B[3].title, ramp: RAMP.shared },
  ]);

  const apiCell = (x: R): Cell => ({ text: x.row.a.api + (multi ? `  [${x.m.name}]` : ''), mono: true, color: PAL.ink });
  // Rows grouped by business feature (payment, manage, ...) like the screen; risk order kept within a feature.
  const byFeature = (rows: R[], cols: { header: string; w: number; mono?: boolean }[], cells: (x: R) => Cell[]) => {
    for (const fg of groupItemsByFeature(rows, (x) => x.row.a.api)) {
      r.para(ascii(`${fg.feature}  (${fg.items.length})`), M, CONTENT_W, 'bold', 9.5, PAL.accent, 13);
      r.wrapTable(cols, fg.items.map(cells));
    }
  };
  const section = (b: Bucket, count: number, emptyText: string, body: () => void) => {
    r.bookmark(b.title);
    r.section(b.title, count, RAMP[b.key], ascii(`${b.question} - ${b.who}`));
    if (count === 0) { r.para(ascii(emptyText), M, CONTENT_W, 'normal', 9.5, PAL.muted, 13); r.paragraph(''); return; }
    body();
  };

  // ---- BAU regression (all High by rule: no Risk column; Who separates BAU-only from Both) ----
  section(B[0], bau.length, 'None - no live route or shared class changed.', () => {
    const cols = hasAnyLog
      ? [{ header: 'API', w: 0.26, mono: true }, { header: 'Change', w: 0.28 }, { header: 'Test focus', w: 0.24 }, { header: 'Who', w: 0.11 }, { header: 'Tested', w: 0.11 }]
      : [{ header: 'API', w: 0.28, mono: true }, { header: 'Change', w: 0.32 }, { header: 'Test focus', w: 0.27 }, { header: 'Who', w: 0.13 }];
    byFeature(bau, cols, (x) => {
      const cells: Cell[] = [apiCell(x), x.row.what + (x.row.detail ? `\n${x.row.detail}` : ''), x.row.test.join('; '), pill(x.row.who, whoRamp(x.row.who))];
      if (hasAnyLog) cells.push(testedCell(logOf(x)));
      return cells;
    });
  });

  // ---- Backward compatibility ----
  section(B[1], bc.length, 'None.', () => {
    const cols = hasAnyLog
      ? [{ header: 'API', w: 0.30, mono: true }, { header: 'Still called by', w: 0.20 }, { header: 'Test focus', w: 0.38 }, { header: 'Tested', w: 0.12 }]
      : [{ header: 'API', w: 0.32, mono: true }, { header: 'Still called by', w: 0.22 }, { header: 'Test focus', w: 0.46 }];
    byFeature(bc, cols, (x) => {
      const cells: Cell[] = [apiCell(x), x.row.oldVersions.length ? x.row.oldVersions.join(', ') : 'current app', x.row.test.join('; ')];
      if (hasAnyLog) cells.push(testedCell(logOf(x)));
      return cells;
    });
  });

  // ---- New app only ----
  section(B[2], neu.length, 'None.', () => {
    const cols = hasAnyLog
      ? [{ header: 'API', w: 0.28, mono: true }, { header: 'Change', w: 0.30 }, { header: 'Test focus', w: 0.22 }, { header: 'Risk', w: 0.10 }, { header: 'Tested', w: 0.10 }]
      : [{ header: 'API', w: 0.30, mono: true }, { header: 'Change', w: 0.34 }, { header: 'Test focus', w: 0.24 }, { header: 'Risk', w: 0.12 }];
    byFeature(neu, cols, (x) => {
      const cells: Cell[] = [apiCell(x), x.row.what + (x.row.detail ? `\n${x.row.detail}` : ''), x.row.test.join('; '), pill(x.row.risk, riskRamp(x.row.risk))];
      if (hasAnyLog) cells.push(testedCell(logOf(x)));
      return cells;
    });
  });

  // ---- Shared code ----
  section(B[3], shared.length, 'None - no shared class changed.', () => {
    const cols = [{ header: 'Class', w: 0.26, mono: true }, { header: 'Changed by', w: 0.16 }, { header: 'Versions to test', w: 0.40 }, { header: 'APIs', w: 0.18, mono: true }];
    r.wrapTable(cols, shared.map(({ s, m }) => [
      { text: s.name + (s.removed ? ' - removed' : '') + (s.versions.length ? `\n${s.versions.join(', ')}` : '') + (multi ? `\n[${m.name}]` : ''), mono: true, color: PAL.ink },
      s.authors || '-',
      s.routes.slice(0, 6).map((x) => `${x.group}: ${x.route}${x.api ? ' -> ' + x.api : ''}`).join('\n') + (s.routes.length > 6 ? `\n+${s.routes.length - 6} more` : ''),
      s.apis.slice(0, 4).join('\n') + (s.apis.length > 4 ? `\n+${s.apis.length - 4} more` : ''),
    ]));
  });

  if (unchanged > 0) r.para(ascii(`${unchanged} unchanged API${unchanged === 1 ? '' : 's'} - carried forward, no action.`), M, CONTENT_W, 'normal', 9.5, PAL.muted, 13);

  // ---- The four definitions ----
  r.legend('What the labels mean', B.map((b) => ascii(`${b.title} - ${b.define}`)));

  const footer = `TraceGuard - Release ${ver}${country ? ' - ' + country : ''}${app ? ' - ' + appLabel(app) : ''} - Summary`;
  r.save(`TraceGuard-Release-${ver}-Summary.pdf`, footer);
}
