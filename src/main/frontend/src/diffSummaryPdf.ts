import type { ApiLogResult } from './types';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, logWindowLine, passRateLine, ascii, type Ramp, type RGB } from './pdfReport';
import { appLabel } from './appName';
import type { ModuleDiff } from './diffPdf';
import { buildTestPlan, buckets, logForRow, testedState, appOf, jiraOf, jiraLine, jiraSummary, jiraApp, changedBy,
  type AppTag, type Bucket, type PlanRow, type SharedClass } from './testPlan';
import { buildAreas, changeTag, type Area } from './areas';
import type { FeatureLabels } from './labels';

/**
 * The manager Summary PDF: the verdict and the four counts, then each business area (highest impact first) with
 * its sub-features and one row per API — API · Which App · Change · Jira Details · Commit Message — each Jira
 * ticket on its own sub-row. Shared code and the release's Jira tickets close it. Same model and order as the
 * on-screen Summary ({@link buildTestPlan}, {@link buildAreas}). The developer report is {@link exportDiffPdf}.
 */

type Cell = string | { pill: { label: string; fill: RGB; text: RGB; stripe: RGB } } | { text: string; mono?: boolean; color?: RGB };
const pill = (label: string, r: Ramp): Cell => ({ pill: { label: ascii(label), fill: r.fill, text: r.text, stripe: r.bar } });
const RAMP: Record<Bucket['key'], Ramp> = { bau: PAL.red, bc: PAL.purple, new: PAL.green, shared: PAL.blue };
const appRamp = (app: AppTag): Ramp => (app === 'BAU app' ? PAL.red : app === 'New app' ? PAL.green : app === 'All clients' ? PAL.blue : PAL.purple);
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
  const single = plans.length > 0 && plans.every((p) => p.plan.snapshot);
  const verLabel = single ? plans[0].plan.version : ver;
  const B = buckets(verLabel, single);
  const logOf = (row: PlanRow) => logForRow(row, modOf.get(row)?.logByVer);
  const rows = [...bau, ...neu];
  const jira = jiraSummary(rows);
  const hasJira = jira.length > 0;
  const developers = new Set(jira.flatMap((j) => j.developers.map((d) => d.toLowerCase()))).size;

  // Tested tally over the actionable rows (BAU + new-app; BC repeats BAU).
  let passed = 0, failed = 0;
  if (hasAnyLog) for (const row of rows) { const l = logOf(row); if (l?.tested) { if (l.status === 'SUCCESS') passed++; else failed++; } }

  // ---- Cover band + verdict + the four counts ----
  r.coverBand('Release Impact - Summary',
    [appLabel(app), country, single ? verLabel : `Release ${ver}`,
     appVersion && !single ? `commit ${appVersion}${commits ? ' - ' + commits : ''}` : '',
     hasJira ? `${jira.length} Jira - ${developers} developer${developers === 1 ? '' : 's'}` : ''].filter((c): c is string => !!c),
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

  // ---- By business area: API · Which App · Change · Jira Details · Commit Message ----
  const areas: Area[] = buildAreas(rows, labels);
  const apiCell = (row: PlanRow): Cell => ({ text: row.a.api + (multi ? `  [${modOf.get(row)?.name ?? ''}]` : ''), mono: true, color: PAL.ink });
  const jiraCell = (j: { ticket: string | null; developers: string[] }): Cell => ({ text: jiraLine(j as never), color: PAL.ink });
  const cols = [
    { header: 'API', w: hasJira ? (hasAnyLog ? 0.26 : 0.30) : (hasAnyLog ? 0.46 : 0.56), mono: true },
    { header: 'Which App', w: hasJira ? 0.11 : 0.16 },
    { header: 'Change', w: hasJira ? (hasAnyLog ? 0.16 : 0.18) : (hasAnyLog ? 0.24 : 0.28) },
    ...(hasJira ? [{ header: 'Jira Details', w: hasAnyLog ? 0.17 : 0.18 }, { header: 'Commit Message', w: hasAnyLog ? 0.20 : 0.23 }] : []),
    ...(hasAnyLog ? [{ header: 'Tested', w: 0.10 }] : []),
  ];
  const table = (list: PlanRow[]) => r.wrapTable(cols, list.flatMap((row) => {
    const refs = hasJira ? jiraOf(row.a) : [];
    const head: Cell[] = [apiCell(row), pill(appOf(row), appRamp(appOf(row))), changeTag(row)];
    if (hasJira) head.push(refs[0] ? jiraCell(refs[0]) : '-', refs[0] ? refs[0].messages.join('\n') : '-');
    if (hasAnyLog) head.push(testedCell(logOf(row)));
    const rest: Cell[][] = refs.slice(1).map((j) => {
      const c: Cell[] = ['', '', '', jiraCell(j), j.messages.join('\n')];
      if (hasAnyLog) c.push('');
      return c;
    });
    return [head, ...rest];
  }));
  areas.forEach((area, i) => {
    r.bookmark(area.label);
    r.section(ascii(`${i + 1}. ${area.label}`), area.rows.length, riskRamp(area.risk), ascii(`Risk: ${area.risk}`));
    if (hasJira) {
      const owners = changedBy(area.rows);
      if (owners.developers.length) {
        r.para(ascii('Changed by: ' + owners.developers.map((d) => `${d.name} (${d.tickets})`).join(' - ') + '    ' + owners.tickets.join(', ')),
          M, CONTENT_W, 'normal', 9, PAL.body, 12);
      }
    }
    for (const sub of area.subs) {
      r.para(ascii(`${sub.label}  (${sub.rows.length})`), M, CONTENT_W, 'bold', 9.5, PAL.accent, 13);
      table(sub.rows);
    }
    if (area.direct.length) {
      if (area.subs.length) r.para(ascii(`Other  (${area.direct.length})`), M, CONTENT_W, 'bold', 9.5, PAL.accent, 13);
      table(area.direct);
    }
  });

  // ---- Shared code ----
  r.bookmark(B[3].title);
  r.section(B[3].title, shared.length, RAMP.shared, '');
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

  // ---- Jira in this release: one row per ticket ----
  if (hasJira) {
    r.bookmark('Jira in this release');
    r.section('Jira in this release', jira.length, PAL.blue, '');
    r.wrapTable([{ header: 'Jira Details', w: 0.28 }, { header: 'Which App', w: 0.14 }, { header: 'Commit Message', w: 0.46 }, { header: 'APIs', w: 0.12 }],
      jira.map((j) => [jiraCell(j), pill(jiraApp(j), appRamp(jiraApp(j))), j.messages.join('\n') || '-', String(j.apis.length)]));
  }

  if (unchanged > 0) r.para(ascii(`${unchanged} unchanged API${unchanged === 1 ? '' : 's'} - carried forward, no action.`), M, CONTENT_W, 'normal', 9.5, PAL.muted, 13);

  const footer = `TraceGuard - Release ${ver}${country ? ' - ' + country : ''}${app ? ' - ' + appLabel(app) : ''} - Impact Summary`;
  r.save(`TraceGuard-Release-${ver}-Impact-Summary.pdf`, footer);
}
