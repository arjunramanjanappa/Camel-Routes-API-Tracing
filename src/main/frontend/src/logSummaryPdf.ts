import type { ApiLogResult, LogAnalysisReport, LogStatus } from './types';
import type { CapabilityResult } from './api';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, logWindowLine, passRateLine, type Ramp } from './pdfReport';
import { groupItemsByFeature } from './feature';

/** A capability's "L1 > L2 > … > L5" path, stopping at the first blank level (e.g. "Wealth > UT Transactor"). */
function capabilityPath(c: { l1: string; l2: string; l3: string; l4: string; l5: string }): string {
  const out: string[] = [];
  for (const l of [c.l1, c.l2, c.l3, c.l4, c.l5]) {
    if (!l || !l.trim()) break;
    out.push(l.trim());
  }
  return out.join(' > ');
}

/** FE API path → its distinct L1..L5 capability paths (from the VAL Capability Matrix join). */
function capabilityLabels(caps?: CapabilityResult): Map<string, string[]> {
  const byApi = new Map<string, string[]>();
  for (const m of caps?.matched || []) {
    if (!m.fe) continue;   // the summary lists front-end APIs
    const labels = [...new Set(m.capabilities.map(capabilityPath).filter(Boolean))];
    if (labels.length) byApi.set(m.api, labels);
  }
  return byApi;
}

/**
 * The 1–2 page leadership Summary PDF for the Release Test tab — verification readiness (passed / issues /
 * not tested) and a plain API · Result · Remark table, for release managers & delivery leads. The full
 * developer report (response codes, latency, backends, per-attempt) is {@link exportLogPdf}.
 */

const SEVERITY: Record<LogStatus, number> = { FAILED: 0, TIMEOUT: 1, PARTIAL: 2, INDETERMINATE: 3, NOT_TESTED: 4, SUCCESS: 5, SKIPPED: 6 };
function resultRamp(s: LogStatus): { label: string; ramp: Ramp } {
  if (s === 'SUCCESS') return { label: 'Passed', ramp: PAL.green };
  if (s === 'NOT_TESTED') return { label: 'Not tested', ramp: PAL.gray };
  if (s === 'SKIPPED') return { label: 'Skipped', ramp: PAL.gray };
  if (s === 'PARTIAL') return { label: 'Partial', ramp: PAL.amber };
  if (s === 'INDETERMINATE') return { label: 'Check', ramp: PAL.gray };
  return { label: s === 'TIMEOUT' ? 'Timeout' : 'Failed', ramp: PAL.red };
}
function remarkOf(a: ApiLogResult): string {
  if (a.status === 'SUCCESS') return '-';
  // The note is purpose-built to explain the verdict — a coverage gap ("change flow not tested"), a failed
  // flow, an FE error, or a timeout. Prefer it: the FE responseDescription can read "Success" on a
  // PARTIAL/FAILED whose real cause is a backend flow, which would be misleading in a manager summary.
  return a.note || a.responseDescription || a.responseCode || (a.attempts > 0 ? `${a.failureCount}/${a.attempts} failed` : '-');
}
function pillCell(label: string, ramp: Ramp) { return { pill: { label, fill: ramp.fill, text: ramp.text, stripe: ramp.bar } }; }

export async function exportLogSummaryPdf(report: LogAnalysisReport, app?: string, version?: string, country?: string, caps?: CapabilityResult) {
  const r = await ReportDoc.create();
  const capByApi = capabilityLabels(caps);
  const hasCaps = capByApi.size > 0;
  const ver = version || report.clientVersion || 'BASE';
  const ctry = country || report.country || '';
  const apis = [...report.apis].sort((a, b) => SEVERITY[a.status] - SEVERITY[b.status] || a.api.localeCompare(b.api));
  let passed = 0, issues = 0, notTested = 0;
  for (const a of apis) {
    if (a.status === 'SUCCESS') passed++;
    else if (a.status === 'NOT_TESTED') notTested++;
    else if (a.status === 'SKIPPED') { /* neutral — not passed, not an issue */ }
    else issues++;
  }
  const total = apis.length;

  r.titlePage('Release Test — Verification Summary', '',
    [
      `${app ? app + ' · ' : ''}Release ${ver}${ctry ? ' · ' + ctry : ''}`,
      `${report.transactions} transactions across ${report.matchedLines} matched log lines`,
      'Generated ' + generatedStamp(),
      passRateLine(passed, passed + issues),
      logWindowLine(report.logStart, report.logEnd),
    ].filter((l): l is string => !!l));

  r.bookmark('Verification readiness');
  r.banner('Verification readiness', PAL.blue, `Release ${ver}${ctry ? ' · ' + ctry : ''} — from the uploaded run log.`);
  r.statBand([
    { n: total, label: 'APIs checked', ramp: PAL.blue },
    { n: passed, label: 'Passed', ramp: PAL.green },
    { n: issues, label: 'Issues', ramp: PAL.red },
    { n: notTested, label: 'Not tested', ramp: PAL.amber },
  ]);
  r.paragraph(`${passed} of ${total} passed${issues ? `, ${issues} with issues` : ''}${notTested ? `, ${notTested} not tested` : ''}.`);

  if (total) {
    r.bookmark('Results');
    r.banner('Results', PAL.purple, 'Per-API verdict from the log, grouped by business feature, worst first within each. Full detail (response codes, latency, backends) is in the Detailed report.');
    // When the VAL Capability Matrix is configured, add a business-capability column (L1 › L2) so leadership
    // reads capabilities, not raw paths. Otherwise keep the original 3-column layout.
    const cols = hasCaps
      ? [{ header: 'API', w: 0.26, mono: true }, { header: 'Capability', w: 0.28 }, { header: 'Latest Result', w: 0.18 }, { header: 'Remark', w: 0.28 }]
      : [{ header: 'API', w: 0.38, mono: true }, { header: 'Latest Result', w: 0.22 }, { header: 'Remark', w: 0.40 }];
    const rowCells = (a: ApiLogResult) => {
      const res = resultRamp(a.status);
      const base = [
        { text: a.api, mono: true, color: PAL.ink },
        pillCell(res.label, res.ramp),
        { text: remarkOf(a), color: a.status === 'SUCCESS' ? PAL.muted : PAL.body },
      ];
      if (!hasCaps) return base;
      const labels = capByApi.get(a.api);
      const cap = { text: labels && labels.length ? labels.join('\n') : '—', color: labels && labels.length ? PAL.body : PAL.muted };
      return [base[0], cap, base[1], base[2]];
    };
    for (const fg of groupItemsByFeature(apis, (a) => a.api)) {
      r.para(`${fg.feature}  (${fg.items.length})`, M, CONTENT_W, 'bold', 10.5, PAL.ink, 15);
      r.wrapTable(cols, fg.items.map(rowCells));
    }
  }

  r.legend('What the labels mean', [
    'Passed - the API executed and returned success in the log.',
    'Failed / Timeout / Partial - executed with a non-success or incomplete result; investigate.',
    'Not tested - no matching transaction was found in the uploaded log.',
    'Remark - the response description / code (or reason) from the log.',
    ...(hasCaps ? ['Capability - the business capability (L1 > L2) this API delivers, from the VAL matrix.'] : []),
  ]);

  const footer = `TraceGuard - Release ${ver}${ctry ? ' · ' + ctry : ''}${app ? ' · ' + app : ''} - Test Summary`;
  r.save(`TraceGuard-Release-${ver}-Test-Summary.pdf`, footer);
}

/** One module's log verification (+ its own VAL capability join) for the consolidated Summary PDF. */
export interface ModuleLogSummary { name: string; report: LogAnalysisReport | null; error?: string; caps?: CapabilityResult; }

function countStatuses(apis: ApiLogResult[]) {
  let passed = 0, issues = 0, notTested = 0;
  for (const a of apis) {
    if (a.status === 'SUCCESS') passed++;
    else if (a.status === 'NOT_TESTED') notTested++;
    else if (a.status === 'SKIPPED') { /* neutral — not passed, not an issue */ }
    else issues++;
  }
  return { passed, issues, notTested, total: apis.length };
}

/**
 * The consolidated leadership Summary PDF for a MULTI-MODULE Release Test run — ONE document covering every
 * module together (Mighty main + SPL sub-modules), mirroring the Release Impact Summary. An aggregate
 * readiness band + a per-module coverage table, then each module's per-API verdicts grouped by feature.
 * The single-module report uses {@link exportLogSummaryPdf}; the Detailed equivalent is {@code exportLogPdfMulti}.
 */
export async function exportLogSummaryPdfMulti(mods: ModuleLogSummary[], app?: string, version?: string, country?: string) {
  const r = await ReportDoc.create();
  const first = mods.find((m) => m.report)?.report;
  const ver = version || first?.clientVersion || 'BASE';
  const ctry = country || first?.country || '';

  // Per-module capability labels; a Capability column is shown (for every module) if ANY module has a matrix join.
  const capByModule = new Map<string, Map<string, string[]>>();
  for (const m of mods) capByModule.set(m.name, capabilityLabels(m.caps));
  const hasCaps = [...capByModule.values()].some((mp) => mp.size > 0);

  // Aggregate readiness across all modules.
  const agg = { passed: 0, issues: 0, notTested: 0, total: 0 };
  let txns = 0, matched = 0;
  for (const m of mods) {
    if (!m.report) continue;
    const c = countStatuses(m.report.apis);
    agg.passed += c.passed; agg.issues += c.issues; agg.notTested += c.notTested; agg.total += c.total;
    txns += m.report.transactions; matched += m.report.matchedLines;
  }
  // Combined log window: earliest start, latest end (timestamps share a sortable format).
  const starts = mods.map((m) => m.report?.logStart).filter(Boolean) as string[];
  const ends = mods.map((m) => m.report?.logEnd).filter(Boolean) as string[];
  const logStart = starts.length ? starts.reduce((a, b) => (a < b ? a : b)) : undefined;
  const logEnd = ends.length ? ends.reduce((a, b) => (a > b ? a : b)) : undefined;

  r.titlePage('Release Test — Verification Summary', '',
    [
      `${app ? app + ' · ' : ''}Release ${ver}${ctry ? ' · ' + ctry : ''} · ${mods.length} module(s)`,
      `${txns} transactions across ${matched} matched log lines`,
      'Generated ' + generatedStamp(),
      passRateLine(agg.passed, agg.passed + agg.issues),
      logWindowLine(logStart, logEnd),
    ].filter((l): l is string => !!l));

  r.bookmark('Verification readiness');
  r.banner('Verification readiness', PAL.blue, `Release ${ver}${ctry ? ' · ' + ctry : ''} — across ${mods.length} module(s), from the uploaded run log.`);
  r.statBand([
    { n: agg.total, label: 'APIs checked', ramp: PAL.blue },
    { n: agg.passed, label: 'Passed', ramp: PAL.green },
    { n: agg.issues, label: 'Issues', ramp: PAL.red },
    { n: agg.notTested, label: 'Not tested', ramp: PAL.amber },
  ]);
  r.paragraph(`${agg.passed} of ${agg.total} passed${agg.issues ? `, ${agg.issues} with issues` : ''}${agg.notTested ? `, ${agg.notTested} not tested` : ''} across ${mods.length} module(s).`);

  // Per-module coverage table (which repo's APIs passed / had issues / were not tested).
  const n0 = (v: number) => (v > 0 ? v : '—');
  const covRows = mods.map((m) => {
    if (m.error) return [m.name, '—', '—', '—', '—', 'Failed'];
    const c = m.report ? countStatuses(m.report.apis) : { passed: 0, issues: 0, notTested: 0, total: 0 };
    const status = !m.report ? '—' : c.issues > 0 ? 'At risk' : c.notTested > 0 ? 'Review' : 'Ready';
    return [m.name, c.total, n0(c.passed), n0(c.issues), n0(c.notTested), status];
  });
  r.dataTable(
    ['Module (pom artifactId)', 'APIs', 'Passed', 'Issues', 'Not tested', 'Status'],
    covRows,
    ['Total', agg.total, n0(agg.passed), n0(agg.issues), n0(agg.notTested), ''],
  );

  // Per-API verdicts, one section per module, grouped by business feature (worst first within each).
  const cols = hasCaps
    ? [{ header: 'API', w: 0.26, mono: true }, { header: 'Capability', w: 0.28 }, { header: 'Latest Result', w: 0.18 }, { header: 'Remark', w: 0.28 }]
    : [{ header: 'API', w: 0.38, mono: true }, { header: 'Latest Result', w: 0.22 }, { header: 'Remark', w: 0.40 }];
  const rowCells = (a: ApiLogResult, capByApi: Map<string, string[]>) => {
    const res = resultRamp(a.status);
    const base = [
      { text: a.api, mono: true, color: PAL.ink },
      pillCell(res.label, res.ramp),
      { text: remarkOf(a), color: a.status === 'SUCCESS' ? PAL.muted : PAL.body },
    ];
    if (!hasCaps) return base;
    const labels = capByApi.get(a.api);
    const cap = { text: labels && labels.length ? labels.join('\n') : '—', color: labels && labels.length ? PAL.body : PAL.muted };
    return [base[0], cap, base[1], base[2]];
  };

  r.bookmark('Results by module');
  r.banner('Results by module', PAL.purple, 'Per-API verdict from the log, grouped by module then business feature, worst first. Full detail (response codes, latency, backends) is in the Detailed report.');
  for (const m of mods) {
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const rep = m.report; if (!rep) continue;
    const apis = [...rep.apis].sort((a, b) => SEVERITY[a.status] - SEVERITY[b.status] || a.api.localeCompare(b.api));
    const c = countStatuses(apis);
    const ramp = c.total === 0 ? PAL.gray : c.issues > 0 ? PAL.red : c.notTested > 0 ? PAL.amber : PAL.green;
    const parts: string[] = [];
    if (c.passed > 0) parts.push(`${c.passed} passed`);
    if (c.issues > 0) parts.push(`${c.issues} issue${c.issues === 1 ? '' : 's'}`);
    if (c.notTested > 0) parts.push(`${c.notTested} not tested`);
    r.section('Module — ' + m.name, c.total, ramp, parts.length ? parts.join(' · ') : `${c.total} API${c.total === 1 ? '' : 's'}`);
    if (!apis.length) { r.emptyNote('No APIs were correlated for this module.'); continue; }
    const capByApi = capByModule.get(m.name) || new Map<string, string[]>();
    for (const fg of groupItemsByFeature(apis, (a) => a.api)) {
      r.para(`${fg.feature}  (${fg.items.length})`, M, CONTENT_W, 'bold', 10.5, PAL.ink, 15);
      r.wrapTable(cols, fg.items.map((a) => rowCells(a, capByApi)));
    }
  }

  r.legend('What the labels mean', [
    'Passed - the API executed and returned success in the log.',
    'Failed / Timeout / Partial - executed with a non-success or incomplete result; investigate.',
    'Not tested - no matching transaction was found in the uploaded log.',
    'Remark - the response description / code (or reason) from the log.',
    ...(hasCaps ? ['Capability - the business capability (L1 > L2) this API delivers, from the VAL matrix.'] : []),
  ]);

  const footer = `TraceGuard - Release ${ver}${ctry ? ' · ' + ctry : ''}${app ? ' · ' + app : ''} - Test Summary`;
  r.save(`TraceGuard-Release-${ver}-Test-Summary.pdf`, footer);
}
