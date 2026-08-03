import { Fragment } from 'react';
import type { ApiDiff, ApiLogResult, DiffStatus, VersionDiffReport } from '../types';
import { groupItemsByFeature } from '../feature';

/**
 * The leadership-facing "Summary" projection of a Release Impact report — the same data the detailed
 * view uses, with the developer layers (route chains, changed classes, Splunk) stripped out. Answers, at
 * a glance: what's changing, is it tested, how risky. No backend data is added — every value here is
 * derived from the existing {@link VersionDiffReport}.
 *
 * <p>The to-verify work is split into two clearly-separated tracks so a reader never mistakes new-app work
 * for a production regression:
 * <ul>
 *   <li><b>New app</b> (release version): new APIs, payload changes on the new-version route, backend bumps.
 *       The old (BAU) app keeps running the unchanged lower route, so these carry no production impact.</li>
 *   <li><b>BAU / existing app</b>: shared code changed, or a BAU route edited in place — the old app runs
 *       that code, so it must be regression-tested.</li>
 * </ul>
 */

type Risk = 'High' | 'Medium' | 'Low';
const RISK_RANK: Record<Risk, number> = { High: 0, Medium: 1, Low: 2 };
function riskOf(a: ApiDiff): Risk { return (a.risk as Risk) || 'Low'; }
function bauRouteModified(a: ApiDiff): boolean { return !!a.bauRouteEdits?.length; }

/** Impacts the OLD (BAU / production) app: shared code changed, or a BAU route edited in place — the old app
 *  runs that code, so it needs regression. Everything else this release touches is scoped to the NEW app. */
function isBauImpact(a: ApiDiff): boolean { return !!a.codeChanged || bauRouteModified(a); }

/** A NEW/UNCHANGED API that changed shared BAU code OR modified a BAU route in place is grouped under Changed
 *  (mirrors the backend count promotion). */
function effectiveStatus(a: ApiDiff): DiffStatus {
  return (a.status === 'NEW' || a.status === 'UNCHANGED') && (a.codeChanged || bauRouteModified(a)) ? 'CHANGED' : a.status as DiffStatus;
}

/** Plain-English "what changed" for a stakeholder — one primary reason, derived from existing fields. */
function whatChanged(a: ApiDiff): { label: string; kind: string } {
  if (bauRouteModified(a)) return { label: 'BAU route modified (PROD)', kind: 'code' };
  if (effectiveStatus(a) === 'NEW') return { label: 'New API', kind: 'new' };
  if (a.codeChanged) return { label: 'Shared code changed', kind: 'code' };
  if (a.payloadChange?.removedKeys?.length || a.payloadChange?.addedKeys?.length) return { label: 'Request/response changed · new app only', kind: 'payload' };
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

type Tally = { passed: number; failed: number; notTested: number; verified: number };
function tallyOf(items: ApiDiff[], log?: Record<string, ApiLogResult>): Tally {
  let passed = 0, failed = 0, notTested = 0;
  if (log) {
    for (const a of items) {
      const l = log[a.api];
      if (l?.tested) { if (l.status === 'SUCCESS') passed++; else failed++; } else notTested++;
    }
  }
  return { passed, failed, notTested, verified: passed + failed };
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
    ? report.apis.filter((a) => a.codeChanged || bauRouteModified(a))
    : report.apis.filter((a) => effectiveStatus(a) !== 'UNCHANGED' && a.status !== 'SNAPSHOT'))
    .sort((a, b) => RISK_RANK[riskOf(a)] - RISK_RANK[riskOf(b)]);

  // Segregate: what the old (BAU) app must regression-test vs what is scoped to the new app version.
  const bauItems = toVerify.filter(isBauImpact);
  const newItems = toVerify.filter((a) => !isBauImpact(a));

  const inScope = report.snapshotCount ?? report.apis.length;
  const code = report.codeChangedCount ?? 0;
  const high = report.highRiskCount ?? 0;
  const bc = report.backwardCompatCount ?? 0;
  const versionLabel = (report.version && report.version.trim()) || report.appVersion || '';

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
          </>
        )}
      </div>

      {/* Two clearly-separated tracks — new-app work vs old-app (BAU) regression — each with its own health. */}
      <div className="sumv-split">
        <VerifySection
          track="new"
          title={'New app' + (versionLabel ? ' · ' + versionLabel : '')}
          subtitle="Release-version work — the old (BAU) app keeps the unchanged route, so no production impact."
          items={newItems} hasLog={hasLog} log={log}
          emptyText="No new-app changes to verify in this scope." />

        <VerifySection
          track="bau"
          title="BAU app · existing production"
          subtitle="Shared code / routes the old app runs were changed — regression-test the existing app."
          items={bauItems} hasLog={hasLog} log={log}
          emptyText="No production (BAU) impact — the old app is unaffected." />
      </div>

      {!snapshot && (report.unchangedCount ?? 0) > 0 && (
        <div className="sumv-unchanged">{report.unchangedCount} unchanged API{report.unchangedCount === 1 ? '' : 's'} — carried forward, no action.</div>
      )}
    </div>
  );
}

/** One verify track (New app / BAU app): its own health line, readiness bar and what-to-verify table. */
function VerifySection({ track, title, subtitle, items, hasLog, log, emptyText }: {
  track: 'new' | 'bau';
  title: string;
  subtitle: string;
  items: ApiDiff[];
  hasLog: boolean;
  log?: Record<string, ApiLogResult>;
  emptyText: string;
}) {
  const t = tallyOf(items, log);
  const pct = items.length ? Math.round((t.verified / items.length) * 100) : 0;
  return (
    <section className={'sumv-section ' + track}>
      <div className="sumv-section-head">
        <div className="sumv-section-title">{title}</div>
        <div className="sumv-section-count">{items.length} to verify</div>
      </div>
      <div className="sumv-section-sub">{subtitle}</div>

      {items.length === 0 ? (
        <div className="sumv-section-empty">{emptyText} <span className="muted">Release health: 0 to verify.</span></div>
      ) : (
        <>
          {hasLog && (
            <div className="sumv-ready">
              <div className="sumv-ready-top">
                <div className="sumv-ready-head">Test readiness — <b>{t.verified} of {items.length}</b> verified <span className="muted">({pct}%)</span></div>
                <div className="muted" style={{ fontSize: 12.5 }}>{t.passed} passed · {t.failed} failed · {t.notTested} not tested</div>
              </div>
              <div className="sumv-bar" aria-hidden="true">
                <span className="s-pass" style={{ width: pctW(t.passed, items.length) }} />
                <span className="s-fail" style={{ width: pctW(t.failed, items.length) }} />
                <span className="s-none" style={{ width: pctW(t.notTested, items.length) }} />
              </div>
            </div>
          )}

          <div className="sumv-tablewrap">
            <table className="sumv-table">
              <thead><tr><th>API</th><th>What changed</th><th>Risk</th>{hasLog && <th>Tested</th>}</tr></thead>
              <tbody>
                {groupItemsByFeature(items, (a) => a.api).map((fg) => (
                  <Fragment key={fg.feature}>
                    <tr className="sumv-feat-row"><td colSpan={hasLog ? 4 : 3}><span className="sumv-feat-name">{fg.feature}</span><span className="sumv-feat-cnt">{fg.items.length}</span></td></tr>
                    {fg.items.map((a) => {
                      const wc = whatChanged(a);
                      const r = riskOf(a);
                      const ts = hasLog ? testedOf(log![a.api]) : null;
                      return (
                        <tr key={a.api + '|' + a.operation} data-sev={r}>
                          <td className="sumv-sev sumv-api"><span className="path">{a.api}</span></td>
                          <td><span className={'sumv-pill ' + wc.kind}>{wc.label}</span></td>
                          <td><span className={'sumv-risk ' + r}><span className="dot" />{r}</span></td>
                          {hasLog && <td><span className={'sumv-tst ' + ts!.cls}>{ts!.label}</span></td>}
                        </tr>
                      );
                    })}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}

function pctW(n: number, total: number): string { return total ? (Math.round((n / total) * 1000) / 10) + '%' : '0%'; }
