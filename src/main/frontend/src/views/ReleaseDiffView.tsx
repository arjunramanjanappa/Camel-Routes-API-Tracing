import { useMemo, useState } from 'react';
import { fetchVersionDiff } from '../api';
import type { ApiDiff, DiffStatus, RouteStepDiff, VersionDiffReport } from '../types';
import { downloadText } from '../spl';
import Loader from '../components/Loader';
import ApiFlowModal from '../components/ApiFlowModal';

// Context (sourceDir + country) is remembered per application, like the other tabs.
function appKey(app: string | undefined, f: string) { return `tracer.${app || 'Mighty'}.${f}`; }
function cardKey(d: ApiDiff) { return d.api + '|' + d.operation; }

const DIFF_MESSAGES = [
  'Scanning the framework source…',
  'Resolving each API to the target version…',
  'Finding the immediate-lower version per API…',
  'Tracing both flows end to end…',
  'Diffing the route bodies…',
];

function statusLabel(s: DiffStatus): string {
  return s === 'NEW' ? 'New' : s === 'CHANGED' ? 'Changed' : 'No change';
}

/** Everything an API diff matches against in the search box. */
function searchHaystack(a: ApiDiff): string {
  return [a.api, a.operation, a.targetRoute, a.lowerRoute,
    ...(a.addedRoutes || []), ...(a.removedRoutes || []),
    ...(a.routeDiffs || []).map((r) => r.routeBase),
    ...(a.backendVersionChanges || []).map((s) => s.backend)]
    .filter(Boolean).join(' ').toLowerCase();
}

/** A plain-text rendering of one API's diff (for copy + export). */
function apiDiffText(a: ApiDiff): string {
  const lines = [`[${a.status}] ${a.api}  (${a.operation})`];
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
  return lines.join('\n');
}

/** At-a-glance change chips: which routes were edited / added / removed. */
function changeChips(d: ApiDiff) {
  const chips: { key: string; cls: string; sym: string; text: string; title: string }[] = [];
  (d.routeDiffs || []).forEach((rd) =>
    chips.push({ key: 'e' + rd.routeBase, cls: 'edited', sym: '✎', text: rd.routeBase, title: 'route body changed' }));
  (d.addedRoutes || []).forEach((r) =>
    chips.push({ key: '+' + r, cls: 'added', sym: '+', text: r, title: 'sub-route added by this release' }));
  (d.removedRoutes || []).forEach((r) =>
    chips.push({ key: '-' + r, cls: 'removed', sym: '−', text: r, title: 'sub-route removed by this release' }));
  return chips;
}

/** One route's +added / −removed canonical lines, with a per-route line tally. */
function RouteDiffBlock({ d }: { d: RouteStepDiff }) {
  return (
    <div className="rdiff">
      <div className="rdiff-head">
        <code>{d.routeBase}</code>
        <span className="row" style={{ gap: 8 }}>
          <span className="rdiff-tally"><span className="add">+{d.added.length}</span> <span className="del">−{d.removed.length}</span></span>
          <span className="muted">{d.targetRoute} ⟵ {d.lowerRoute}</span>
        </span>
      </div>
      <pre className="rdiff-body">
        {d.removed.map((l, i) => <div key={'r' + i} className="dl del">- {l}</div>)}
        {d.added.map((l, i) => <div key={'a' + i} className="dl add">+ {l}</div>)}
      </pre>
    </div>
  );
}

function ApiDiffCard({ d, open, onToggle, onViewFlow, onCopy, copied }: {
  d: ApiDiff; open: boolean; onToggle: () => void;
  onViewFlow: () => void; onCopy: () => void; copied: boolean;
}) {
  const svc = d.backendVersionChanges || [];
  // An UNCHANGED card with a note is a fallback API (no route at the target version).
  const fallback = d.status === 'UNCHANGED' && !!d.note;
  const showPill = !!d.lowerVersion && (d.status === 'CHANGED' || (d.status === 'UNCHANGED' && !fallback));
  const chips = changeChips(d);
  return (
    <div className={'diff-card ' + d.status.toLowerCase()}>
      <div className="diff-card-head row between">
        <div className="diff-card-id">
          <code>{d.api}</code>
          <span className="muted op">{d.operation}</span>
        </div>
        <span className="row" style={{ gap: 6 }}>
          {showPill && (
            <span className="ver-pill"><b>{d.lowerVersion}</b><span className="ver-arrow">→</span><b>{d.targetVersion}</b></span>
          )}
          <span className={'diff-badge ' + d.status.toLowerCase()}>{statusLabel(d.status)}</span>
        </span>
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
            {d.status === 'UNCHANGED' && <span className="muted"> · version bumped, identical flow</span>}
          </>
        )}
      </div>

      {chips.length > 0 && (
        <div className="diff-changes">
          {chips.map((c) => (
            <span key={c.key} className={'chg ' + c.cls} title={c.title}>
              <span className="chg-sym">{c.sym}</span> {c.text}
            </span>
          ))}
        </div>
      )}

      {svc.length > 0 && (
        <div className="diff-svc">
          {svc.map((s) => (
            <div key={s.backend} className="diff-svc-row">
              <span className="diff-svc-label">backend service version</span>
              <code>{s.backend}</code>
              <span className="svc-from">{s.fromVersion}</span>
              <span className="diff-arrow">→</span>
              <span className="svc-to">{s.toVersion}</span>
            </div>
          ))}
        </div>
      )}

      {d.routeDiffs?.length > 0 && (
        <>
          <button type="button" className="rdiff-toggle" aria-expanded={open} onClick={onToggle}>
            <span className="collapse-caret">{open ? '▾' : '▸'}</span>
            <span className="rdiff-toggle-title">What changed ({d.routeDiffs.length} route{d.routeDiffs.length > 1 ? 's' : ''})</span>
            <span className="muted">element-level diff</span>
          </button>
          {open && d.routeDiffs.map((rd) => <RouteDiffBlock key={rd.routeBase} d={rd} />)}
        </>
      )}

      <div className="diff-actions">
        <button className="linkbtn" onClick={onViewFlow}>View flow ▸</button>
        <button className="linkbtn" onClick={onCopy}>{copied ? 'Copied ✓' : 'Copy'}</button>
      </div>
    </div>
  );
}

const ALL_STATUSES: DiffStatus[] = ['CHANGED', 'NEW', 'UNCHANGED'];

export default function ReleaseDiffView({ app, colorMode = 'light' }: { app?: string; colorMode?: 'light' | 'dark' }) {
  const [sourceDir, setSourceDir] = useState(() => localStorage.getItem(appKey(app, 'sourceDir')) ?? '');
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('');
  const [report, setReport] = useState<VersionDiffReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Changed + new shown by default; unchanged is opt-in. The counts double as filters.
  const [filters, setFilters] = useState<Record<DiffStatus, boolean>>({ CHANGED: true, NEW: true, UNCHANGED: false });
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [flowApi, setFlowApi] = useState<{ api: string; version?: string } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const load = async () => {
    localStorage.setItem(appKey(app, 'sourceDir'), sourceDir);
    localStorage.setItem(appKey(app, 'country'), country);
    setLoading(true); setError(null);
    try {
      const data = await fetchVersionDiff(sourceDir, country, version);
      setReport(data);
      setExpanded(new Set());
      setQuery('');
      setFilters({ CHANGED: true, NEW: true, UNCHANGED: false });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const counts: Record<DiffStatus, number> = {
    CHANGED: report?.changedCount ?? 0,
    NEW: report?.newCount ?? 0,
    UNCHANGED: report?.unchangedCount ?? 0,
  };
  const countLabel: Record<DiffStatus, string> = { CHANGED: 'changed', NEW: 'new', UNCHANGED: 'unchanged' };

  const visible = useMemo(() => {
    if (!report) return [];
    const q = query.trim().toLowerCase();
    return report.apis
      .filter((a) => filters[a.status])
      .filter((a) => !q || searchHaystack(a).includes(q));
  }, [report, filters, query]);

  const expandableKeys = useMemo(
    () => visible.filter((d) => d.routeDiffs?.length > 0).map(cardKey), [visible]);
  const allOpen = expandableKeys.length > 0 && expandableKeys.every((k) => expanded.has(k));

  const toggleOne = (k: string) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(k)) next.delete(k); else next.add(k);
    return next;
  });
  const toggleAll = () => setExpanded(allOpen ? new Set() : new Set(expandableKeys));
  const toggleFilter = (s: DiffStatus) => setFilters((prev) => ({ ...prev, [s]: !prev[s] }));

  const copyOne = (d: ApiDiff) => {
    const text = apiDiffText(d);
    const done = () => {
      setCopiedKey(cardKey(d));
      window.setTimeout(() => setCopiedKey((k) => (k === cardKey(d) ? null : k)), 1400);
    };
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
        done();
      } catch { /* clipboard unavailable — silently ignore */ }
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(fallback);
    } else {
      fallback();
    }
  };

  const exportReport = () => {
    if (!report) return;
    const lines: string[] = [`Release diff — version ${report.version}${report.country ? ', ' + report.country : ''}`,
      `${report.changedCount} changed · ${report.newCount} new · ${report.unchangedCount} unchanged`, ''];
    for (const a of report.apis) { lines.push(apiDiffText(a), ''); }
    downloadText(`release-diff-${report.version || 'base'}.txt`, lines.join('\n'));
  };

  const showUnchanged = () => setFilters((prev) => ({ ...prev, UNCHANGED: true }));

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
          <div className="diff-head row between">
            <h2 style={{ margin: 0 }}>Release {report.version || 'BASE'}{report.country ? ` · ${report.country}` : ''}</h2>
            {report.apis.length > 0 && <button className="minibtn" onClick={exportReport}>⤓ Export</button>}
          </div>

          {report.apis.length > 0 && (
            <div className="diff-toolbar">
              <div className="diff-filters">
                {ALL_STATUSES.map((s) => (
                  <button key={s} className={'diff-count ' + s.toLowerCase() + (filters[s] ? '' : ' off')}
                          aria-pressed={filters[s]} onClick={() => toggleFilter(s)}>
                    {counts[s]} {countLabel[s]}
                  </button>
                ))}
              </div>
              <input className="diff-search" placeholder="🔍 filter by path, route or backend"
                     value={query} onChange={(e) => setQuery(e.target.value)} />
            </div>
          )}

          {report.warnings.length > 0 && (
            <div className="warnbox">{report.warnings.map((w, i) => <div key={i}>⚠ {w}</div>)}</div>
          )}

          {visible.length === 0 ? (
            <div className="impact-empty">
              <div className="impact-empty-title">
                {report.apis.length === 0 ? 'Nothing to compare'
                  : query.trim() ? 'No matches'
                    : 'No changed or new APIs'}
              </div>
              <div className="sub">
                {report.apis.length === 0
                  ? 'No API resolves to this version in the selected scope.'
                  : query.trim()
                    ? <>Nothing matches “{query.trim()}”. Clear the search or turn a filter back on.</>
                    : <>This release didn’t add or change any API here.{counts.UNCHANGED > 0 && !filters.UNCHANGED
                        ? <> <button className="linkbtn" onClick={showUnchanged}>Show {counts.UNCHANGED} unchanged ▸</button></> : null}</>}
              </div>
            </div>
          ) : (
            <>
              {expandableKeys.length > 0 && (
                <div className="diff-list-head">
                  <span className="muted">{visible.length} API{visible.length > 1 ? 's' : ''}</span>
                  <button className="linkbtn" onClick={toggleAll}>{allOpen ? 'Collapse all' : 'Expand all'}</button>
                </div>
              )}
              <div className="diff-list">
                {visible.map((d) => (
                  <ApiDiffCard key={cardKey(d)} d={d}
                               open={expanded.has(cardKey(d))} onToggle={() => toggleOne(cardKey(d))}
                               onViewFlow={() => setFlowApi({ api: d.api, version: d.targetVersion || report.version || undefined })}
                               onCopy={() => copyOne(d)} copied={copiedKey === cardKey(d)} />
                ))}
              </div>
            </>
          )}
        </div>
      )}

      {flowApi && (
        <ApiFlowModal api={flowApi.api} version={flowApi.version} sourceDir={sourceDir} country={country}
                      colorMode={colorMode} onClose={() => setFlowApi(null)} />
      )}
    </div>
  );
}
