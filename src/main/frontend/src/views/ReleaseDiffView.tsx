import { useMemo, useState } from 'react';
import { fetchVersionDiff } from '../api';
import type { ApiDiff, RouteStepDiff, VersionDiffReport } from '../types';
import { downloadText } from '../spl';
import Loader from '../components/Loader';
import Collapsible from '../components/Collapsible';

// Context (sourceDir + country) is remembered per application, like the other tabs.
function appKey(app: string | undefined, f: string) { return `tracer.${app || 'Mighty'}.${f}`; }

const DIFF_MESSAGES = [
  'Scanning the framework source…',
  'Resolving each API to the target version…',
  'Finding the immediate-lower version per API…',
  'Tracing both flows end to end…',
  'Diffing the route bodies…',
];

function statusLabel(s: ApiDiff['status']): string {
  return s === 'NEW' ? 'NEW' : s === 'CHANGED' ? 'CHANGED' : 'no change';
}

/** One route's +added / −removed canonical lines. */
function RouteDiffBlock({ d }: { d: RouteStepDiff }) {
  return (
    <div className="rdiff">
      <div className="rdiff-head">
        <code>{d.routeBase}</code>
        <span className="muted">{d.targetRoute} ⟵ {d.lowerRoute}</span>
      </div>
      <pre className="rdiff-body">
        {d.removed.map((l, i) => <div key={'r' + i} className="dl del">- {l}</div>)}
        {d.added.map((l, i) => <div key={'a' + i} className="dl add">+ {l}</div>)}
      </pre>
    </div>
  );
}

function ApiDiffCard({ d }: { d: ApiDiff }) {
  const svc = d.backendVersionChanges || [];
  const changes = (d.routeDiffs?.length || 0) + (d.addedRoutes?.length || 0)
    + (d.removedRoutes?.length || 0) + svc.length;
  // An UNCHANGED card with a note is a fallback API (no route at the target version).
  const fallback = d.status === 'UNCHANGED' && !!d.note;
  return (
    <div className={'diff-card ' + d.status.toLowerCase()}>
      <div className="diff-card-head row between">
        <div className="diff-card-id">
          <code>{d.api}</code>
          <span className="muted op">{d.operation}</span>
        </div>
        <span className={'diff-badge ' + d.status.toLowerCase()}>{statusLabel(d.status)}</span>
      </div>

      <div className="diff-verdict">
        {d.status === 'NEW' ? (
          <>Added in <b>{d.targetVersion}</b> — no earlier version to compare against. <span className="tag route">{d.targetRoute}</span></>
        ) : fallback ? (
          <><span className="tag route lower">{d.targetRoute}</span><span className="muted"> · {d.note}</span></>
        ) : (
          <>
            <span className="tag route">{d.targetRoute}</span>
            <span className="diff-arrow">⟵</span>
            <span className="tag route lower">{d.lowerRoute}</span>
            <span className="muted">
              {d.status === 'CHANGED'
                ? ` · ${changes} change${changes > 1 ? 's' : ''}`
                : ' · version bumped, identical flow'}
            </span>
          </>
        )}
      </div>

      {(d.addedRoutes?.length > 0 || d.removedRoutes?.length > 0) && (
        <div className="diff-routes">
          {d.addedRoutes.map((r) => <span key={'+' + r} className="tag route added" title="sub-route added by this release">+ {r}</span>)}
          {d.removedRoutes.map((r) => <span key={'-' + r} className="tag route removed" title="sub-route removed by this release">− {r}</span>)}
        </div>
      )}

      {svc.length > 0 && (
        <div className="diff-svc">
          {svc.map((s) => (
            <div key={s.backend} className="diff-svc-row">
              <span className="diff-svc-label">backend svc version</span>
              <code>{s.backend}</code>
              <span className="svc-from">{s.fromVersion}</span>
              <span className="diff-arrow">→</span>
              <span className="svc-to">{s.toVersion}</span>
            </div>
          ))}
        </div>
      )}

      {d.routeDiffs?.length > 0 && (
        <Collapsible title={`What changed (${d.routeDiffs.length} route${d.routeDiffs.length > 1 ? 's' : ''})`} hint="element-level diff">
          {d.routeDiffs.map((rd) => <RouteDiffBlock key={rd.routeBase} d={rd} />)}
        </Collapsible>
      )}
    </div>
  );
}

export default function ReleaseDiffView({ app }: { app?: string; colorMode?: 'light' | 'dark' }) {
  const [sourceDir, setSourceDir] = useState(() => localStorage.getItem(appKey(app, 'sourceDir')) ?? '');
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('');
  const [report, setReport] = useState<VersionDiffReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showUnchanged, setShowUnchanged] = useState(false);

  const load = async () => {
    localStorage.setItem(appKey(app, 'sourceDir'), sourceDir);
    localStorage.setItem(appKey(app, 'country'), country);
    setLoading(true); setError(null);
    try {
      const data = await fetchVersionDiff(sourceDir, country, version);
      setReport(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Changed + new are always shown; unchanged (version bump, no change) is behind a toggle.
  const visible = useMemo(() => {
    if (!report) return [];
    return report.apis.filter((a) => showUnchanged || a.status !== 'UNCHANGED');
  }, [report, showUnchanged]);

  const exportReport = () => {
    if (!report) return;
    const lines: string[] = [`Release diff — version ${report.version}${report.country ? ', ' + report.country : ''}`,
      `${report.changedCount} changed · ${report.newCount} new · ${report.unchangedCount} unchanged`, ''];
    for (const a of report.apis) {
      lines.push(`[${statusLabel(a.status).toUpperCase()}] ${a.api}  (${a.operation})`);
      if (a.note) lines.push(`    ${a.note}`);
      else if (a.status !== 'NEW') lines.push(`    ${a.targetRoute} <- ${a.lowerRoute}`);
      a.addedRoutes.forEach((r) => lines.push(`    + route ${r}`));
      a.removedRoutes.forEach((r) => lines.push(`    - route ${r}`));
      (a.backendVersionChanges || []).forEach((s) => lines.push(`    ~ svc ${s.backend}: ${s.fromVersion} -> ${s.toVersion}`));
      a.routeDiffs.forEach((rd) => {
        lines.push(`    ~ ${rd.routeBase}`);
        rd.removed.forEach((l) => lines.push(`        - ${l}`));
        rd.added.forEach((l) => lines.push(`        + ${l}`));
      });
      lines.push('');
    }
    downloadText(`release-diff-${report.version || 'base'}.txt`, lines.join('\n'));
  };

  return (
    <div className="impact">
      <div className="context-bar">
        <div>
          <label>Source directory</label>
          <input value={sourceDir} placeholder="defaults to server config" onChange={(e) => setSourceDir(e.target.value)} />
        </div>
        <div style={{ width: 160 }}>
          <label>Country (optional)</label>
          <input value={country} placeholder="e.g. SG" onChange={(e) => setCountry(e.target.value)} />
        </div>
        <div style={{ width: 140 }}>
          <label>Target version</label>
          <input value={version} placeholder="9.18" onChange={(e) => setVersion(e.target.value)}
                 onKeyDown={(e) => { if (e.key === 'Enter') load(); }} />
        </div>
        <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'flex-end' }} disabled={loading} onClick={load}>
          {loading ? 'Comparing…' : 'Compare'}
        </button>
      </div>

      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {loading && <div className="impact-loading"><Loader messages={DIFF_MESSAGES} note="comparing versions" /></div>}

      {!loading && !report && !error && (
        <div className="impact-empty">
          <div className="impact-empty-title">Compare a release against the one before it</div>
          <div className="sub">Enter a target version (e.g. <b>9.18</b>) and click <b>Compare</b>. For every API the release touched, TraceGuard traces the whole flow at that version and at its immediate-lower version, then highlights exactly what changed — added, removed or modified — across the resolved Camel routes.</div>
        </div>
      )}

      {!loading && report && (
        <div className="impact-body single">
          <div className="panel diff-summary">
            <div className="row between">
              <h2 style={{ margin: 0 }}>
                Release {report.version || 'BASE'}{report.country ? ` · ${report.country}` : ''}
              </h2>
              {report.apis.length > 0 && (
                <button className="minibtn" onClick={exportReport}>⤓ Export</button>
              )}
            </div>
            <div className="diff-counts">
              <span className="diff-count changed">{report.changedCount} changed</span>
              <span className="diff-count new">{report.newCount} new</span>
              <span className="diff-count unchanged">{report.unchangedCount} unchanged</span>
            </div>
            {report.unchangedCount > 0 && (
              <label className="diff-toggle">
                <input type="checkbox" checked={showUnchanged} onChange={(e) => setShowUnchanged(e.target.checked)} />
                Show unchanged APIs ({report.unchangedCount}) — version bumps &amp; APIs not touched by this release
              </label>
            )}
          </div>

          {report.warnings.length > 0 && (
            <div className="warnbox">{report.warnings.map((w, i) => <div key={i}>⚠ {w}</div>)}</div>
          )}

          {visible.length === 0 ? (
            <div className="impact-empty">
              <div className="impact-empty-title">Nothing to show</div>
              <div className="sub">
                {report.apis.length === 0
                  ? 'No API resolves to this version in the selected scope.'
                  : 'No changed or new APIs. Tick “Show version-bumped APIs” to see the rest.'}
              </div>
            </div>
          ) : (
            <div className="diff-list">
              {visible.map((d) => <ApiDiffCard key={d.api + d.operation} d={d} />)}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
