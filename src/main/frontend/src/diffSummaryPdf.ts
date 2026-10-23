import type { ApiLogResult } from './types';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, logWindowLine, passRateLine, ascii, type Ramp, type RGB } from './pdfReport';
import { appLabel } from './appName';
import type { ModuleDiff } from './diffPdf';
import { buildTestPlan, buckets, logForRow, testedState, type Bucket, type PlanRow, type SharedClass } from './testPlan';
import { buildAreas, type Area } from './areas';
import type { FeatureLabels } from './labels';

/**
 * The manager Summary PDF — the release as a release team talks about it: the verdict and the four test-plan
 * counts, then one section per BUSINESS AREA ("Funds Transfer & Payments"): risk + reason, the APIs under their
 * sub-features ("Quick Transfer") with the precise change and who tests it, the backward-compatibility position
 * and a short testing focus. Shared code and the four definitions close the report. Built from the same
 * {@link buildTestPlan} model as the on-screen Summary; area names come from the configurable feature labels.
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

export async function exportDiffSummaryPdf(mods: ModuleDiff[], app?: string, logWindow?: { start?: string; end?: string }, labels: FeatureLabels = {}) {
  const r = await ReportDoc.create();
  const withReport = mods.filter((m) => m.report);
  const first = withReport[0]?.report;
  const ver = first?.version || 'N/A';
  const country = first?.country;
  const appVersion = withReport.find((m) => m.report?.appVersion)?.report?.appVersion || null;
  const commits = withReport.reduce((n, m) => n + (m.report?.matchedCommits ?? 0), 0);
  const hasAnyLog = withReport.some((m) => m.logByVer && Object.keys(m.logByVer).length);
  const multi = withReport.length > 1;

  // One plan per module, merged (module kept per row for the API cell and the per-module log lookup).
  const plans = withReport.map((m) => ({ m, plan: buildTestPlan(m.report!) }));
  const modOf = new Map<PlanRow, ModuleDiff>();
  const bau: PlanRow[] = [], bc: PlanRow[] = [], neu: PlanRow[] = [];
  for (const { m, plan } of plans) {
    for (const row of plan.bau) { bau.push(row); modOf.set(row, m); }
    for (const row of plan.bc) { bc.push(row); modOf.set(row, m); }
    for (const row of plan.newOnly) { neu.push(row); modOf.set(row, m); }
  }
  const shared: { s: SharedClass; m: ModuleDiff }[] = plans.flatMap(({ m, plan }) => plan.shared.map((s) => ({ s, m })));
  const inScope = plans.reduce((n, p) => n + p.plan.inScope, 0);
  const unchanged = plans.reduce((n, p) => n + p.plan.unchanged, 0);
  const sharedVersions = new Set(shared.flatMap(({ s }) => s.routes.map((x) => x.version))).size;
  const verdict = multi
    ? `${inScope} APIs in scope across ${withReport.length} modules: ${bau.length} BAU regression · ${bc.length} backward-compat · ${neu.length} new app only · ${unchanged} unchanged.`
    : plans[0]?.plan.verdict ?? '';
  const B = buckets(ver);
  const logOf = (row: PlanRow) => logForRow(row, modOf.get(row)?.logByVer);

  // Tested tally over the actionable rows (BAU + new-app; BC repeats BAU).
  let passed = 0, failed = 0;
  if (hasAnyLog) for (const row of [...bau, ...neu]) { const l = logOf(row); if (l?.tested) { if (l.status === 'SUCCESS') passed++; else failed++; } }

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

  // ---- By business area: risk + reason, APIs by sub-feature, backward compatibility, testing focus ----
  const areas: Area[] = buildAreas([...bau, ...neu], labels);
  const apiCell = (row: PlanRow): Cell => ({ text: row.a.api + (multi ? `  [${modOf.get(row)?.name ?? ''}]` : ''), mono: true, color: PAL.ink });
  const cols = hasAnyLog
    ? [{ header: 'API', w: 0.34, mono: true }, { header: 'Change', w: 0.32 }, { header: 'Who tests', w: 0.17 }, { header: 'Tested', w: 0.17 }]
    : [{ header: 'API', w: 0.40, mono: true }, { header: 'Change', w: 0.38 }, { header: 'Who tests', w: 0.22 }];
  const table = (rows: PlanRow[]) => r.wrapTable(cols, rows.map((row) => {
    const cells: Cell[] = [apiCell(row), row.what + (row.detail ? `\n${row.detail}` : ''), pill(row.who, whoRamp(row.who))];
    if (hasAnyLog) cells.push(testedCell(logOf(row)));
    return cells;
  }));
  if (areas.length) {
    r.bookmark('By business area');
    r.banner('By business area', PAL.blue, ascii('Highest impact first. Each area: risk and why, the APIs under their sub-features with who tests them, the backward-compatibility position, and the testing focus.'));
  }
  areas.forEach((area, i) => {
    r.bookmark(area.label);
    const head = `${i + 1}. ${area.label}${i === 0 && areas.length > 1 ? '  -  highest impact' : ''}`;
    r.section(ascii(head), area.rows.length, riskRamp(area.risk), ascii(`Risk: ${area.risk} - ${area.reason}`));
    for (const sub of area.subs) {
      r.para(ascii(`${sub.label}  (${sub.rows.length})`), M, CONTENT_W, 'bold', 9.5, PAL.accent, 13);
      table(sub.rows);
    }
    if (area.direct.length) {
      if (area.subs.length) r.para(ascii(`Other  (${area.direct.length})`), M, CONTENT_W, 'bold', 9.5, PAL.accent, 13);
      table(area.direct);
    }
    const bcRows = area.rows.filter((x) => x.bc);
    r.para(ascii(bcRows.length
        ? `Backward compatibility: ${bcRows.length} API${bcRows.length === 1 ? '' : 's'} still called by ${area.bcVersions.length ? area.bcVersions.join(', ') : 'the current app'} - an old client against the new build must pass.`
        : 'Backward compatibility: none - old app unaffected.'),
      M, CONTENT_W, 'bold', 9, bcRows.length ? PAL.purple.text : PAL.green.text, 12);
    if (area.focus.length) r.para(ascii('Testing focus: ' + area.focus.join('; ') + '.'), M, CONTENT_W, 'normal', 9, PAL.body, 12);
    r.paragraph('');
  });

  // ---- Shared code ----
  r.bookmark(B[3].title);
  r.section(B[3].title, shared.length, RAMP.shared, ascii(`${B[3].question} - ${B[3].who}`));
  if (!shared.length) {
    r.para('None - no shared class changed.', M, CONTENT_W, 'normal', 9.5, PAL.muted, 13); r.paragraph('');
  } else {
    r.wrapTable([{ header: 'Class', w: 0.26, mono: true }, { header: 'Changed by', w: 0.16 }, { header: 'Versions to test', w: 0.40 }, { header: 'APIs', w: 0.18, mono: true }],
      shared.map(({ s, m }) => [
        { text: s.name + (s.removed ? ' - removed' : '') + (s.versions.length ? `\n${s.versions.join(', ')}` : '') + (multi ? `\n[${m.name}]` : ''), mono: true, color: PAL.ink },
        s.authors || '-',
        s.routes.slice(0, 6).map((x) => `${x.group}: ${x.route}${x.api ? ' -> ' + x.api : ''}`).join('\n') + (s.routes.length > 6 ? `\n+${s.routes.length - 6} more` : ''),
        s.apis.slice(0, 4).join('\n') + (s.apis.length > 4 ? `\n+${s.apis.length - 4} more` : ''),
      ]));
  }

  if (unchanged > 0) r.para(ascii(`${unchanged} unchanged API${unchanged === 1 ? '' : 's'} - carried forward, no action.`), M, CONTENT_W, 'normal', 9.5, PAL.muted, 13);

  // ---- The four definitions ----
  r.legend('What the labels mean', B.map((b) => ascii(`${b.title} - ${b.define}`)));

  const footer = `TraceGuard - Release ${ver}${country ? ' - ' + country : ''}${app ? ' - ' + appLabel(app) : ''} - Impact Summary`;
  r.save(`TraceGuard-Release-${ver}-Impact-Summary.pdf`, footer);
}
