import type { ApiDiff, ApiLogResult, DiffStatus, VersionDiffReport } from '../types';

/**
 * The leadership-facing "Summary" projection of a Release Impact report — the same data the detailed
 * view uses, with the developer layers (route chains, changed classes, Splunk) stripped out. Answers, at
 * a glance: what's changing, is it tested, how risky. No backend data is added — every value here is
 * derived from the existing {@link VersionDiffReport}.
 */

type Risk = 'High' | 'Medium' | 'Low';
const RISK_RANK: Record<Risk, number> = { High: 0, Medium: 1, Low: 2 };
function riskOf(a: ApiDiff): Risk { return (a.risk as Risk) || 'Low'; }
/** A NEW API that changed shared BAU code is grouped under Changed (mirrors the backend promotion). */
function effectiveStatus(a: ApiDiff): DiffStatus { return a.status === 'NEW' && a.codeChanged ? 'CHANGED' : a.status as DiffStatus; }

/** Plain-English "what changed" for a stakeholder — one primary reason, derived from existing fields. */
function whatChanged(a: ApiDiff): { label: string; kind: string } {
  if (effectiveStatus(a) === 'NEW') return { label: 'New API', kind: 'new' };
  if (a.codeChanged) return { label: 'Shared code changed', kind: 'code' };
  if (a.payloadChange?.removedKeys?.length || a.payloadChange?.addedKeys?.length) return { label: 'Request/response changed', kind: 'payload' };
  if (a.backendVersionChanges?.length) return { label: 'Backend version changed', kind: 'backend' };
  return { label: 'Logic changed', kind: 'logic' };
}

/** Pass / Fail / Not-tested from an uploaded log's per-API result. Null when no log covers the API. */
function testedOf(l: ApiLogResult | undefined): { cls: string; label: string } {
  if (!l || !l.tested) return { cls: 'none', label: '— Not tested' };
  if (l.status === 'SUCCESS') return { cls: 'pass', label: '✓ Passed' };
  if (l.status === 'PARTIAL') return { cls: 'fail', label: '✗ Partial' };
  if (l.status === 'FAILED' || l.status === 'TIMEOUT') return { cls: 'fail', label: '✗ Failed' };
  return { cls: 'none', label: 'Ran' };
}

export default function ImpactSummary({ report, log }: {
  report: VersionDiffReport;
  log?: Record<string, ApiLogResult>;   // per-API results for the compared version (undefined = no log uploaded)
}) {
  const hasLog = !!log;
  const snapshot = !!report.snapshot;

  // The APIs that need verifying: Changed + New for a diff; for the N/A snapshot (no diff), the ones whose
  // shared code this release changed. Highest-risk first.
  const toVerify = (snapshot
    ? report.apis.filter((a) => a.codeChanged)
    : report.apis.filter((a) => effectiveStatus(a) !== 'UNCHANGED' && a.status !== 'SNAPSHOT'))
    .sort((a, b) => RISK_RANK[riskOf(a)] - RISK_RANK[riskOf(b)]);

  // Readiness tally over the to-verify set.
  let passed = 0, failed = 0, notTested = 0;
  if (hasLog) {
    for (const a of toVerify) {
      const l = log![a.api];
      if (l?.tested) { if (l.status === 'SUCCESS') passed++; else failed++; }
      else notTested++;
    }
  }
  const verified = passed + failed;
  const pct = toVerify.length ? Math.round((verified / toVerify.length) * 100) : 0;

  const inScope = report.snapshotCount ?? report.apis.length;
  const code = report.codeChangedCount ?? 0;
  const high = report.highRiskCount ?? 0;
  const bc = report.backwardCompatCount ?? 0;

  return (
    <div className="sumv">
      <p className="sumv-eyebrow">Release health · what this release touches</p>

      <div className="sumv-tiles">
        <div className="sumv-tile accent"><div className="n">{inScope}</div><div className="l">APIs in scope</div></div>
        {snapshot ? (
          <>
            {report.appVersion && <div className="sumv-tile violet"><div className="n">{code}</div><div className="l">Code changed</div></div>}
          </>
        ) : (
          <>
            <div className="sumv-tile accent"><div className="n">{report.newCount ?? 0}</div><div className="l">New</div></div>
            <div className="sumv-tile warn"><div className="n">{report.changedCount ?? 0}</div><div className="l">Changed</div></div>
            <div className="sumv-tile"><div className="n">{report.unchangedCount ?? 0}</div><div className="l">Unchanged</div></div>
            {high > 0 && <div className="sumv-tile crit"><div className="n">{high}</div><div className="l">High risk</div></div>}
            {bc > 0 && <div className="sumv-tile warn"><div className="n">{bc}</div><div className="l">Need backward-compat</div></div>}
            <div className="sumv-tile violet"><div className="n">{toVerify.length}</div><div className="l">To verify</div></div>
          </>
        )}
      </div>

      {hasLog && toVerify.length > 0 && (
        <div className="sumv-ready">
          <div className="sumv-ready-top">
            <div className="sumv-ready-head">Test readiness — <b>{verified} of {toVerify.length}</b> verified <span className="muted">({pct}%)</span></div>
            <div className="muted" style={{ fontSize: 12.5 }}>{passed} passed · {failed} failed · {notTested} not tested</div>
          </div>
          <div className="sumv-bar" aria-hidden="true">
            <span className="s-pass" style={{ width: pctW(passed, toVerify.length) }} />
            <span className="s-fail" style={{ width: pctW(failed, toVerify.length) }} />
            <span className="s-none" style={{ width: pctW(notTested, toVerify.length) }} />
          </div>
          <div className="sumv-legend">
            <span><i style={{ background: 'var(--good, #15803d)' }} />Passed {passed}</span>
            <span><i style={{ background: 'var(--crit, #b91c1c)' }} />Failed {failed}</span>
            <span><i style={{ background: '#cfd8e3' }} />Not tested {notTested}</span>
          </div>
        </div>
      )}

      {toVerify.length === 0 ? (
        <div className="sumv-empty">Nothing to verify — this release changed or added no APIs in this scope.</div>
      ) : (
        <div className="sumv-tablewrap">
          <table className="sumv-table">
            <thead><tr><th>API</th><th>What changed</th><th>Risk</th>{hasLog && <th>Tested</th>}</tr></thead>
            <tbody>
              {toVerify.map((a) => {
                const wc = whatChanged(a);
                const r = riskOf(a);
                const t = hasLog ? testedOf(log![a.api]) : null;
                return (
                  <tr key={a.api + '|' + a.operation} data-sev={r}>
                    <td className="sumv-sev sumv-api"><span className="path">{a.api}</span></td>
                    <td><span className={'sumv-pill ' + wc.kind}>{wc.label}</span></td>
                    <td><span className={'sumv-risk ' + r}><span className="dot" />{r}</span></td>
                    {hasLog && <td><span className={'sumv-tst ' + t!.cls}>{t!.label}</span></td>}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {!snapshot && (report.unchangedCount ?? 0) > 0 && (
        <div className="sumv-unchanged">{report.unchangedCount} unchanged API{report.unchangedCount === 1 ? '' : 's'} — carried forward, no action.</div>
      )}
    </div>
  );
}

function pctW(n: number, total: number): string { return total ? (Math.round((n / total) * 1000) / 10) + '%' : '0%'; }
