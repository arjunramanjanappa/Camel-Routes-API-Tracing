import type { ApiLogResult, BackendCallResult, BackendLogResult, LogAnalysisReport, LogStatus } from './types';
import { ReportDoc, PAL, M, CONTENT_W, stamp, type Ramp } from './pdfReport';
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
  if (b.serviceVersionOk === true) return `  svc ${b.loggedServiceVersion} OK`;
  if (b.serviceVersionOk === false) return `  svc ${b.loggedServiceVersion} MISMATCH (expected ${b.expectedServiceVersion})`;
  if (b.loggedServiceVersion) return `  svc ${b.loggedServiceVersion}`;
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
    `End-to-end log verification - ${report.transactions} transactions, ${report.matchedLines}/${report.linesScanned} lines`
    + ` (${report.uploadType}). Generated ${new Date().toLocaleString()}.`);

  r.statBand([
    { n: passed, label: 'Passed', ramp: PAL.green },
    { n: issues, label: 'Issues', ramp: PAL.orange },
    { n: notTested, label: 'Not tested', ramp: PAL.red },
  ]);

  r.paragraph(`Of ${total} API(s) checked for release ${ver}, ${passed} passed end-to-end, `
    + `${issues} had issues and ${notTested} were not seen in the uploaded logs.`
    + (report.backends.length ? `  ${report.backends.length} backend(s) were correlated directly.` : ''));

  r.legend('How to read this report', [
    'Each API is verified end-to-end by correlation id: the front-end request paired with its backend call.',
    'Passed = success both ends; Failed/Timeout = backend error or no response; Not tested = no matching log lines.',
    'Each API lists its backend calls; svc shows the logged service version (and MISMATCH vs expected).',
    'Statuses are grouped worst-first so the items needing action lead the report.',
  ]);

  const footer = `TraceGuard - Verification ${ver}${app ? ' - ' + app : ''}`;
  if (total === 0 && report.backends.length === 0) { r.emptyNote('No APIs or backends were correlated from the logs.'); r.reviewSection(needsReview); r.save(file(ver), footer); return; }

  for (const status of ORDER) {
    const list = report.apis.filter((a) => a.status === status).sort((a, b) => a.api.localeCompare(b.api));
    if (!list.length) continue;
    r.section(ST[status].label + ' APIs', list.length, ST[status].ramp, SECTION_BLURB[status]);
    list.forEach((a, i) => { if (i > 0) r.separator(); apiEntry(r, a); });
  }

  if (report.backends.length) {
    const sorted = [...report.backends].sort((a, b) => ORDER.indexOf(a.status) - ORDER.indexOf(b.status) || a.backend.localeCompare(b.backend));
    r.section('Backend calls', sorted.length, PAL.gray, 'Backends correlated directly from the logs (across the analysed APIs).');
    sorted.forEach((b, i) => { if (i > 0) r.separator(); backendEntry(r, b); });
  }

  r.reviewSection(needsReview);
  r.save(file(ver), footer);
}

function apiEntry(r: ReportDoc, a: ApiLogResult) {
  r.ensure(36);
  const meta = ST[a.status];
  const pw = r.pill(meta.label, M, meta.ramp.fill, meta.ramp.text, 8);
  const w = r.text(a.api, M + pw + 8, 'bold', 11, PAL.ink);
  r.text(a.operation, M + pw + 8 + w + 8, 'normal', 9, PAL.muted);
  r.y += 15;

  const parts: string[] = [];
  if (a.responseCode) parts.push('code ' + a.responseCode);
  if (a.responseDescription) parts.push(a.responseDescription);
  if (a.feLatencyMs != null) parts.push(a.feLatencyMs + ' ms');
  parts.push(`${a.attempts} attempt(s), ${a.successCount} ok / ${a.failureCount} failed`);
  if (a.correlationId) parts.push('corr ' + a.correlationId);
  r.para(parts.join('  -  '), M, CONTENT_W, 'normal', 9, PAL.body, 12);
  r.chips('Failed by code:', failureChips(a.failuresByCode), PAL.red, M + 4);
  if (a.note) r.para('Note: ' + a.note, M, CONTENT_W, 'normal', 9, PAL.muted, 12);

  (a.backends || []).forEach((b: BackendCallResult) => {
    const bm = ST[b.status];
    const line = `${backendPath(b.backend)} - ${bm.label}`
      + (b.responseCode ? ` (code ${b.responseCode})` : '')
      + (b.latencyMs != null ? `, ${b.latencyMs} ms` : '')
      + svcText(b);
    r.para('- ' + line, M + 4, CONTENT_W - 4, 'normal', 9, bm.ramp.text, 12);
  });
  r.y += 6;
}

function backendEntry(r: ReportDoc, b: BackendLogResult) {
  r.ensure(28);
  const meta = ST[b.status];
  const pw = r.pill(meta.label, M, meta.ramp.fill, meta.ramp.text, 8);
  r.text(backendPath(b.backend), M + pw + 8, 'bold', 10, PAL.ink);
  r.y += 14;
  const parts: string[] = [];
  if (b.responseCode) parts.push('code ' + b.responseCode);
  if (b.responseDescription) parts.push(b.responseDescription);
  if (b.latencyMs != null) parts.push(b.latencyMs + ' ms');
  parts.push(`${b.attempts} attempt(s), ${b.successCount} ok / ${b.failureCount} failed`);
  const svc = svcText(b).trim();
  if (svc) parts.push(svc);
  if (b.note) parts.push(b.note);
  r.para(parts.join('  -  '), M, CONTENT_W, 'normal', 9, PAL.body, 12);
  r.chips('Failed by code:', failureChips(b.failuresByCode), PAL.red, M + 4);
  r.y += 6;
}

/** ["00911 (2)", "00999 (1)"] — failed attempts grouped by response code / reason, most-frequent first. */
function failureChips(m?: Record<string, number> | null): string[] {
  return m ? Object.entries(m).map(([code, n]) => `${code} (${n})`) : [];
}

function file(ver: string): string { return `verification-${ver === 'BASE' ? 'base' : ver}-${stamp()}.pdf`; }
