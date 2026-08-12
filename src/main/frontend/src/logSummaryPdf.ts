import type { ApiLogResult, LogAnalysisReport, LogStatus } from './types';
import type { CapabilityResult } from './api';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp, type Ramp } from './pdfReport';
import { groupItemsByFeature } from './feature';

/** FE API path → its distinct top-level "L1 › L2" capability labels (from the VAL Capability Matrix join). */
function capabilityLabels(caps?: CapabilityResult): Map<string, string[]> {
  const byApi = new Map<string, string[]>();
  for (const m of caps?.matched || []) {
    if (!m.fe) continue;   // the summary lists front-end APIs
    const labels = [...new Set(m.capabilities.map((c) => [c.l1, c.l2].filter(Boolean).join(' > ')).filter(Boolean))];
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
    ]);

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
