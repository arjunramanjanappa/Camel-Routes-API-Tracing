import type { ApiLogResult, BackendCallResult, BackendLogResult, LogAnalysisReport, LogStatus } from './types';
import { ReportDoc, PAL, M, CONTENT_W, stamp, generatedStamp, type Ramp } from './pdfReport';
import { backendPath } from './spl';

const ST: Record<LogStatus, { label: string; ramp: Ramp }> = {
  SUCCESS: { label: 'Passed', ramp: PAL.green },
  PARTIAL: { label: 'Partial', ramp: PAL.amber },
  FAILED: { label: 'Failed', ramp: PAL.orange },
  TIMEOUT: { label: 'Timeout', ramp: PAL.purple },
  INDETERMINATE: { label: 'Check', ramp: PAL.blue },
  NOT_TESTED: { label: 'Not tested', ramp: PAL.red },
};
// Worst first so what needs investigation leads the report.
const ORDER: LogStatus[] = ['FAILED', 'TIMEOUT', 'PARTIAL', 'INDETERMINATE', 'NOT_TESTED', 'SUCCESS'];
const SECTION_BLURB: Record<LogStatus, string> = {
  FAILED: 'Failed end-to-end - the backend returned an error. Investigate before release.',
  TIMEOUT: 'No response correlated within the window - confirm whether the call completed.',
  PARTIAL: 'Some calls succeeded and some did not - review the per-backend detail.',
  INDETERMINATE: 'Seen in the logs but the outcome could not be determined - check manually.',
  NOT_TESTED: 'No matching log lines - these APIs were not exercised by the uploaded logs.',
  SUCCESS: 'Verified end-to-end (front-end and backend) for this release.',
};

function svcText(b: { expectedServiceVersion?: string | null; loggedServiceVersion?: string | null; serviceVersionOk?: boolean | null }): string {
  if (b.serviceVersionOk === false) return `  -  service version ${b.loggedServiceVersion} (expected ${b.expectedServiceVersion})`;
  if (b.loggedServiceVersion) return `  -  service version ${b.loggedServiceVersion}`;
  return '';
}

/** Render the log/Splunk verification to a downloadable PDF report. */
export async function exportLogPdf(report: LogAnalysisReport, app?: string, version?: string, needsReview?: string[]) {
  const r = await ReportDoc.create();
  const ver = version || report.clientVersion || 'BASE';

  const counts: Record<LogStatus, number> = { SUCCESS: 0, PARTIAL: 0, FAILED: 0, TIMEOUT: 0, INDETERMINATE: 0, NOT_TESTED: 0 };
  report.apis.forEach((a) => { counts[a.status]++; });
  const total = report.apis.length;
  const passed = counts.SUCCESS;
  const notTested = counts.NOT_TESTED;
  const issues = total - passed - notTested;

  r.header('Verification Report',
    `${app ? app + '  -  ' : ''}Release ${ver}${report.country ? '  -  ' + report.country : ''}`,
    `Generated ${generatedStamp()}`);

  // ===== Verification Summary =====
  r.banner('Verification Summary', PAL.blue);
  r.statBand([
    { n: total, label: 'Total APIs', ramp: PAL.gray },
    { n: passed, label: 'Passed', ramp: PAL.green },
    { n: issues, label: 'Issues', ramp: PAL.orange },
    { n: notTested, label: 'Not tested', ramp: PAL.red },
  ]);
  r.paragraph(`Of ${total} API(s) checked for release ${ver}, ${passed} passed end-to-end, `
    + `${issues} had issues and ${notTested} were not seen in the uploaded logs.`
    + (report.backends.length ? `  ${report.backends.length} backend(s) were correlated directly.` : ''));

  // Release-wide attempt totals + the response codes that failed most across every API.
  let totAttempts = 0, totPassed = 0, totFailed = 0;
  const releaseFailures: Record<string, number> = {};
  report.apis.forEach((a) => {
    totAttempts += a.attempts; totPassed += a.successCount; totFailed += a.failureCount;
    Object.entries(a.failuresByCode || {}).forEach(([code, n]) => { releaseFailures[code] = (releaseFailures[code] || 0) + n; });
  });
  if (totAttempts > 0) {
    r.statStrip([
      { n: totAttempts, label: 'attempts', ramp: PAL.gray },
      { n: totPassed, label: 'passed', ramp: PAL.green },
      { n: totFailed, label: 'failed', ramp: PAL.red },
    ]);
  }
  // What was analysed (moved out of the header for a cleaner cover line).
  r.para(`Analysed ${report.transactions} transaction(s) from a ${report.uploadType} upload`
    + ` (${report.matchedLines} of ${report.linesScanned} log lines matched).`, M, CONTENT_W, 'normal', 9, PAL.muted, 12);
  r.y += 4;
  const topFailures = Object.entries(releaseFailures).sort((x, y) => y[1] - x[1]).slice(0, 12);
  if (topFailures.length) r.failureTable(topFailures, 'Top failing response codes across the release');

  // ===== How to read this report =====
  r.legend('How to read this report', [
    'Each API is verified end-to-end by correlation id: the front-end request paired with its backend call.',
    'Passed = success both ends; Failed/Timeout = backend error or no response; Not tested = no matching log lines.',
    'Each API shows a Total / Passed / Failed summary, the backends it calls, and a table of its failing response codes.',
    'Statuses are grouped worst-first so the items needing action lead the report.',
  ]);

  const footer = `TraceGuard - Verification ${ver}${app ? ' - ' + app : ''}`;
  if (total === 0 && report.backends.length === 0) { r.emptyNote('No APIs or backends were correlated from the logs.'); r.reviewSection(needsReview); r.save(file(ver), footer); return; }

  // ===== API breakdown =====
  r.banner('API breakdown', PAL.blue, 'Every API from the uploaded logs, grouped by outcome (worst first).');
  apiBreakdown(r, report, true);

  r.reviewSection(needsReview);
  r.save(file(ver), footer);
}

/** One module's log verification for the grouped report (or an error). */
export interface ModuleLog { name: string; report: LogAnalysisReport | null; error?: string; }

function statusCounts(report: LogAnalysisReport) {
  const c: Record<LogStatus, number> = { SUCCESS: 0, PARTIAL: 0, FAILED: 0, TIMEOUT: 0, INDETERMINATE: 0, NOT_TESTED: 0 };
  report.apis.forEach((a) => { c[a.status]++; });
  const total = report.apis.length;
  return { c, total, passed: c.SUCCESS, notTested: c.NOT_TESTED, issues: total - c.SUCCESS - c.NOT_TESTED };
}

/** The API-breakdown (grouped by outcome) + backend calls for one report — shared by single and multi. */
function apiBreakdown(r: ReportDoc, report: LogAnalysisReport, useSections: boolean) {
  for (const status of ORDER) {
    const list = report.apis.filter((a) => a.status === status).sort((a, b) => a.api.localeCompare(b.api));
    if (!list.length) continue;
    // The header (section band, or a colour-coded group pill) carries the status; rows don't repeat it.
    if (useSections) r.section(ST[status].label + ' APIs', list.length, ST[status].ramp, SECTION_BLURB[status]);
    else r.groupHead(ST[status].label, list.length, ST[status].ramp);
    list.forEach((a, i) => { if (i > 0) r.separator(); apiEntry(r, a); });
  }
  if (report.backends.length) {
    const sorted = [...report.backends].sort((a, b) => ORDER.indexOf(a.status) - ORDER.indexOf(b.status) || a.backend.localeCompare(b.backend));
    if (useSections) r.section('Backend calls', sorted.length, PAL.gray, 'Backends correlated directly from the logs (across the analysed APIs).');
    else r.groupHead('Backend calls', sorted.length, PAL.gray);
    sorted.forEach((b, i) => { if (i > 0) r.separator(); backendEntry(r, b); });
  }
}

/** Release-test readiness (issues-only rule): issues → At risk, else not-tested → Review, else Ready. */
function readinessLabel(s: { issues: number; notTested: number; total: number }): string {
  if (s.total === 0) return 'No APIs';
  if (s.issues > 0) return 'At risk';
  if (s.notTested > 0) return 'Review';
  return 'Ready';
}
function readinessRank(m: ModuleLog): number {
  if (m.error) return 0;   // not analysed → most attention
  if (!m.report) return 5;
  const s = statusCounts(m.report);
  if (s.total === 0) return 4;
  if (s.issues > 0) return 1;
  if (s.notTested > 0) return 2;
  return 3;   // ready
}

/**
 * Render a multi-module log verification to ONE PDF: a per-module coverage table (which repo's
 * APIs passed / had issues / were not tested), then each module's breakdown grouped by outcome.
 */
export async function exportLogPdfMulti(mods: ModuleLog[], app?: string, version?: string, needsReview?: string[]) {
  const r = await ReportDoc.create();
  const first = mods.find((m) => m.report)?.report;
  const ver = version || first?.clientVersion || 'BASE';
  const country = first?.country;

  // Worst first: errored, at-risk, review, ready — so what needs attention leads the report.
  const ordered = [...mods].sort((a, b) => readinessRank(a) - readinessRank(b));
  const rows = ordered.map((m) => {
    const s = m.report ? statusCounts(m.report) : { total: 0, passed: 0, issues: 0, notTested: 0 };
    return { name: m.name, error: m.error, total: s.total, passed: s.passed, issues: s.issues, notTested: s.notTested,
      status: m.error ? 'Failed' : readinessLabel(s) };
  });
  const tot = { total: 0, passed: 0, issues: 0, notTested: 0 };
  rows.forEach((x) => { tot.total += x.total; tot.passed += x.passed; tot.issues += x.issues; tot.notTested += x.notTested; });
  const attention = rows.filter((x) => x.error || x.status === 'At risk' || x.status === 'Review').length;

  r.header('Release Test Report',
    `${app ? app + '  -  ' : ''}${mods.length} module(s)  -  Release ${ver}${country ? '  -  ' + country : ''}`,
    `Generated ${generatedStamp()}`);

  // ===== Release Test Summary =====
  r.banner('Release Test Summary', PAL.blue);
  r.statBand([
    { n: tot.total, label: 'Total APIs', ramp: PAL.gray },
    { n: tot.passed, label: 'Passed', ramp: PAL.green },
    { n: tot.issues, label: 'Issues', ramp: PAL.orange },
    { n: tot.notTested, label: 'Not tested', ramp: PAL.red },
  ]);
  r.paragraph(`Release ${ver}${country ? ' in ' + country : ''} verified across ${mods.length} module(s): `
    + `${tot.passed} passed end-to-end, ${tot.issues} had issues and ${tot.notTested} were not seen in the uploaded logs`
    + (attention > 0 ? `. ${attention} module(s) need attention (at risk or to review), shown first.` : '. ')
    + ` Test coverage by module:`);
  // A zero count is shown as a dash so only the outcomes the log actually derived stand out.
  const n0 = (v: number) => (v > 0 ? v : '—');
  r.dataTable(
    ['Module (pom artifactId)', 'APIs', 'Passed', 'Issues', 'Not tested', 'Status'],
    rows.map((x) => [x.name,
      x.error ? '—' : x.total, x.error ? '—' : n0(x.passed), x.error ? '—' : n0(x.issues), x.error ? '—' : n0(x.notTested), x.status]),
    ['Total', tot.total, n0(tot.passed), n0(tot.issues), n0(tot.notTested), ''],
  );

  // ===== How to read this report =====
  r.legend('How to read this report', [
    'Modules are ordered worst-first: At risk (has issues), then Review (has not-tested APIs), then Ready — so what needs attention leads.',
    'Each API is verified end-to-end by correlation id: the front-end request paired with its backend call.',
    'Passed = success both ends; Failed/Timeout = backend error or no response; Not tested = no matching log lines for that module.',
    'A module with a high "Not tested" count is a repo whose APIs the uploaded logs did not exercise.',
  ]);

  const footer = `TraceGuard - Release test ${ver}${app ? ' - ' + app : ''}`;

  // ===== Test coverage by module =====
  r.banner('Test coverage by module', PAL.blue, 'Each module’s APIs grouped by outcome (worst first). "Not tested" = the logs did not exercise it.');
  for (const m of ordered) {
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const rep = m.report; if (!rep) continue;
    const s = statusCounts(rep);
    const ramp = s.total === 0 ? PAL.red : s.issues > 0 ? PAL.orange : s.notTested > 0 ? PAL.amber : PAL.green;
    // Only mention a count that's non-zero — a 0 means the log derived none of that outcome (noise).
    const parts: string[] = [];
    if (s.passed > 0) parts.push(`${s.passed} passed`);
    if (s.issues > 0) parts.push(`${s.issues} issue${s.issues === 1 ? '' : 's'}`);
    if (s.notTested > 0) parts.push(`${s.notTested} not tested`);
    r.section('Module — ' + m.name, s.total, ramp,
      (parts.length ? parts.join(' · ') : `${s.total} API${s.total === 1 ? '' : 's'}`)
      + `  —  ${rep.transactions} transaction(s), ${rep.matchedLines}/${rep.linesScanned} lines matched.`);
    if (s.total === 0 && rep.backends.length === 0) { r.emptyNote('No APIs or backends were correlated for this module.'); continue; }
    apiBreakdown(r, rep, false);
  }

  r.reviewSection(needsReview);
  r.save(file(ver), footer);
}

function apiEntry(r: ReportDoc, a: ApiLogResult) {
  r.ensure(60);
  // No status pill here — the group header above already states it (Passed / Failed / Not tested …).
  r.text(a.api, M, 'bold', 11, PAL.ink);   // API path only (operation name dropped)
  r.y += 18;

  // Overall numbers as a compact summary strip (replaces the old dense stats line).
  r.statStrip([
    { n: a.attempts, label: 'attempts', ramp: PAL.gray },
    { n: a.successCount, label: 'passed', ramp: PAL.green },
    { n: a.failureCount, label: 'failed', ramp: PAL.red },
  ]);
  if (a.note) r.para('Note: ' + a.note, M, CONTENT_W, 'normal', 9, PAL.muted, 12);

  // Backends this API calls, under a clear label (was an unlabelled line at the very end).
  if (a.backends && a.backends.length) {
    r.text('Backend', M, 'bold', 9, PAL.ink); r.y += 13;
    (a.backends).forEach((b: BackendCallResult) => {
      const bm = ST[b.status];
      const line = `${backendPath(b.backend)}  -  ${bm.label}`
        + (b.responseCode ? ` (code ${b.responseCode})` : '')
        + (b.latencyMs != null ? `, ${b.latencyMs} ms` : '')
        + svcText(b);
      r.para('- ' + line, M + 4, CONTENT_W - 4, 'normal', 9, bm.ramp.text, 12);
    });
    r.y += 4;
  }

  r.failureTable(Object.entries(a.failuresByCode || {}) as [string, number][]);
  r.y += 6;
}

function backendEntry(r: ReportDoc, b: BackendLogResult) {
  r.ensure(56);
  const meta = ST[b.status];
  const pw = r.pill(meta.label, M, meta.ramp.fill, meta.ramp.text, 8);
  const svc = svcText(b).trim().replace(/^-\s*/, '');
  r.text(backendPath(b.backend) + (svc ? '   ·   ' + svc : ''), M + pw + 8, 'bold', 10, PAL.ink);
  r.y += 18;
  r.statStrip([
    { n: b.attempts, label: 'attempts', ramp: PAL.gray },
    { n: b.successCount, label: 'passed', ramp: PAL.green },
    { n: b.failureCount, label: 'failed', ramp: PAL.red },
  ]);
  if (b.note) r.para('Note: ' + b.note, M, CONTENT_W, 'normal', 9, PAL.muted, 12);
  r.failureTable(Object.entries(b.failuresByCode || {}) as [string, number][]);
  r.y += 6;
}

function file(ver: string): string { return `verification-${ver === 'BASE' ? 'base' : ver}-${stamp()}.pdf`; }
