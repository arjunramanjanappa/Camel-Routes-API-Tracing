import { Fragment, type ReactNode } from 'react';
import { CircleCheck, CircleX, Minus } from 'lucide-react';
import type { ApiLogResult, LogAnalysisReport, LogStatus } from '../types';
import { groupItemsByFeature } from '../feature';

/**
 * The leadership Summary for the Release Test tab: a plain readiness view of an uploaded run log — how
 * many of the release's APIs passed / had issues / weren't tested, and a simple API · Result · Remark
 * table. No Splunk, response codes, latency, backends or per-attempt detail (those stay in Detailed).
 * Reuses the shared `.sumv-*` styles from the Release Impact summary.
 */

const SEVERITY: Record<LogStatus, number> = { FAILED: 0, TIMEOUT: 1, PARTIAL: 2, INDETERMINATE: 3, NOT_TESTED: 4, SUCCESS: 5, SKIPPED: 6 };

function resultOf(a: ApiLogResult): { cls: string; label: ReactNode } {
  const pass = <CircleCheck size={13} aria-hidden="true" />;
  const fail = <CircleX size={13} aria-hidden="true" />;
  switch (a.status) {
    case 'SUCCESS': return { cls: 'pass', label: <>{pass} Passed</> };
    case 'PARTIAL': return { cls: 'fail', label: <>{fail} Partial</> };
    case 'FAILED': return { cls: 'fail', label: <>{fail} Failed</> };
    case 'TIMEOUT': return { cls: 'fail', label: <>{fail} Timeout</> };
    case 'INDETERMINATE': return { cls: 'none', label: 'Check' };
    default: return { cls: 'none', label: <><Minus size={13} aria-hidden="true" /> Not tested</> };
  }
}

/** The effective front-end pass threshold as a whole percent (default 95%). */
function thresholdPct(report: LogAnalysisReport): number {
  let frac = report.passThreshold ?? 0.95;
  if (!isFinite(frac) || frac <= 0) frac = 0.95;
  if (frac > 1) frac = frac / 100;
  return Math.round(frac * 100);
}

/** A short, precise reason for the verdict — for managers: no API path echoed (the API column shows it),
 *  and failures quantified against the pass threshold. */
function remarkOf(a: ApiLogResult, pct: number): string {
  const failLine = `${a.failureCount} out of ${a.attempts} failed (below ${pct}% pass percentage)`;
  switch (a.status) {
    case 'SUCCESS': return '—';
    case 'NOT_TESTED': return 'No logs matched for this API.';
    case 'SKIPPED': return 'Skipped — not counted in the verdict.';
    case 'TIMEOUT':
      return a.attempts > 0 ? `No response — ${a.failureCount} out of ${a.attempts} calls timed out.`
                            : 'No response — the request timed out.';
    case 'FAILED':
    case 'PARTIAL':
      if (a.attempts > 0 && a.failureCount > 0) return failLine + '.';
      if (a.status === 'PARTIAL') return 'Not all flows were exercised by the log.';
      return 'A tested flow failed — see the Detailed view.';
    case 'INDETERMINATE': return 'Result unclear — see the Detailed view.';
    default: return '—';
  }
}

export default function TestSummary({ report }: { report: LogAnalysisReport }) {
  const apis = [...report.apis].sort((a, b) => SEVERITY[a.status] - SEVERITY[b.status] || a.api.localeCompare(b.api));
  let passed = 0, issues = 0, notTested = 0;
  for (const a of apis) {
    if (a.status === 'SUCCESS') passed++;
    else if (a.status === 'NOT_TESTED') notTested++;
    else issues++;
  }
  const total = apis.length;
  const pct = total ? Math.round((passed / total) * 100) : 0;
  const threshold = thresholdPct(report);

  return (
    <div className="sumv" style={{ marginTop: 12 }}>
      <p className="sumv-eyebrow" style={{ margin: '2px 0 8px' }}>Verification readiness · from the uploaded run log</p>

      <div className="sumv-tiles">
        <div className="sumv-tile accent"><div className="n">{total}</div><div className="l">APIs checked</div></div>
        <div className="sumv-tile good"><div className="n" style={{ color: '#15803d' }}>{passed}</div><div className="l">Passed</div></div>
        {issues > 0 && <div className="sumv-tile crit"><div className="n">{issues}</div><div className="l">Issues</div></div>}
        {notTested > 0 && <div className="sumv-tile warn"><div className="n">{notTested}</div><div className="l">Not tested</div></div>}
      </div>

      {total > 0 && (
        <div className="sumv-ready">
          <div className="sumv-ready-top">
            <div className="sumv-ready-head">Verified — <b>{passed} of {total}</b> passed <span className="muted">({pct}%)</span></div>
            <div className="muted" style={{ fontSize: 12.5 }}>{passed} passed · {issues} issue{issues === 1 ? '' : 's'} · {notTested} not tested</div>
          </div>
          <div className="sumv-bar" aria-hidden="true">
            <span className="s-pass" style={{ width: barW(passed, total) }} />
            <span className="s-fail" style={{ width: barW(issues, total) }} />
            <span className="s-none" style={{ width: barW(notTested, total) }} />
          </div>
          <div className="sumv-legend">
            <span><i style={{ background: '#16a34a' }} />Passed {passed}</span>
            <span><i style={{ background: '#dc2626' }} />Issues {issues}</span>
            <span><i style={{ background: '#cfd8e3' }} />Not tested {notTested}</span>
          </div>
        </div>
      )}

      {total === 0 ? (
        <div className="sumv-empty">No API results in this log yet — upload the run log above.</div>
      ) : (
        <div className="sumv-tablewrap">
          <table className="sumv-table">
            <thead><tr><th>API</th><th>Test Result</th><th>Remark</th></tr></thead>
            <tbody>
              {groupItemsByFeature(apis, (a) => a.api).map((fg) => (
                <Fragment key={fg.feature}>
                  <tr className="sumv-feat-row"><td colSpan={3}><span className="sumv-feat-name">{fg.feature}</span><span className="sumv-feat-cnt">{fg.items.length}</span></td></tr>
                  {fg.items.map((a) => {
                    const r = resultOf(a);
                    return (
                      <tr key={a.api + '|' + a.operation}>
                        <td className="sumv-api"><span className="path">{a.api}</span></td>
                        <td><span className={'sumv-tst ' + r.cls}>{r.label}</span></td>
                        <td style={{ color: a.status === 'SUCCESS' ? '#8497ad' : undefined, fontSize: 13 }}>{remarkOf(a, threshold)}</td>
                      </tr>
                    );
                  })}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function barW(n: number, total: number): string { return total ? (Math.round((n / total) * 1000) / 10) + '%' : '0%'; }
