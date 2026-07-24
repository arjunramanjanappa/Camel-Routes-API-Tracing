import type { ApiLogResult, LogAnalysisReport, LogStatus } from '../types';

/**
 * The leadership Summary for the Release Test tab: a plain readiness view of an uploaded run log — how
 * many of the release's APIs passed / had issues / weren't tested, and a simple API · Result · Remark
 * table. No Splunk, response codes, latency, backends or per-attempt detail (those stay in Detailed).
 * Reuses the shared `.sumv-*` styles from the Release Impact summary.
 */

const SEVERITY: Record<LogStatus, number> = { FAILED: 0, TIMEOUT: 1, PARTIAL: 2, INDETERMINATE: 3, NOT_TESTED: 4, SUCCESS: 5 };

function resultOf(a: ApiLogResult): { cls: string; label: string } {
  switch (a.status) {
    case 'SUCCESS': return { cls: 'pass', label: '✓ Passed' };
    case 'PARTIAL': return { cls: 'fail', label: '✗ Partial' };
    case 'FAILED': return { cls: 'fail', label: '✗ Failed' };
    case 'TIMEOUT': return { cls: 'fail', label: '✗ Timeout' };
    case 'INDETERMINATE': return { cls: 'none', label: 'Check' };
    default: return { cls: 'none', label: '— Not tested' };
  }
}

function remarkOf(a: ApiLogResult): string {
  if (a.status === 'SUCCESS') return '—';
  if (a.status === 'NOT_TESTED') return a.note || 'No matching transaction in the log';
  return a.responseDescription || a.responseCode || a.note
    || (a.attempts > 0 ? `${a.failureCount}/${a.attempts} failed` : '—');
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
            <thead><tr><th>API</th><th>Result</th><th>Remark</th></tr></thead>
            <tbody>
              {apis.map((a) => {
                const r = resultOf(a);
                return (
                  <tr key={a.api + '|' + a.operation}>
                    <td className="sumv-api"><span className="path">{a.api}</span></td>
                    <td><span className={'sumv-tst ' + r.cls}>{r.label}</span></td>
                    <td style={{ color: a.status === 'SUCCESS' ? '#8497ad' : undefined, fontSize: 13 }}>{remarkOf(a)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function barW(n: number, total: number): string { return total ? (Math.round((n / total) * 1000) / 10) + '%' : '0%'; }
