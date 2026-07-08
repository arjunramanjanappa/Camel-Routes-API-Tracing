import { useMemo, useState } from 'react';
import { fetchVersionDiff } from '../api';
import type { ApiDiff, DepSource, DiffStatus, RouteStepDiff, SourceType, VersionDiffReport } from '../types';
import { exportDiffPdf } from '../diffPdf';
import Loader from '../components/Loader';
import ApiFlowModal from '../components/ApiFlowModal';
import SourceFields, { sourceValid, sourceParams, type SourceState } from '../components/SourceFields';
import DependencyEditor from '../components/DependencyEditor';
import NeedsReviewBox from '../components/NeedsReviewBox';
import { depParams, loadDeps, saveDeps } from '../deps';

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
      {d.changedBy && d.changedBy.length > 0 && (
        <div className="rdiff-by"><span className="rdiff-by-label">Changed by</span> {d.changedBy.join(', ')}</div>
      )}
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
          <>Added in <b>{d.targetVersion}</b> — no earlier version to compare against. <span className="tag route">{d.targetRoute}</span>
            {d.authors && d.authors.length > 0 && (
              <span className="diff-added-by"><span className="rdiff-by-label">Added by</span> {d.authors.join(', ')}</span>
            )}</>
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

      {d.payloadChange && (d.payloadChange.addedKeys.length > 0 || d.payloadChange.removedKeys.length > 0) && (
        <div className="diff-payload" title="JSON keys added/removed in the request-body template (.ftl/.vm) — serviceVersionNumber excluded">
          <span className="diff-payload-label">Payload change</span>
          {d.payloadChange.addedKeys.map((k) => <span key={'+' + k} className="pk add">+ {k}</span>)}
          {d.payloadChange.removedKeys.map((k) => <span key={'-' + k} className="pk del">− {k}</span>)}
        </div>
      )}

      <div className="diff-actions">
        <button className="linkbtn" onClick={onViewFlow}>View flow ▸</button>
        <button className="linkbtn" onClick={onCopy}>{copied ? 'Copied ✓' : 'Copy'}</button>
      </div>
    </div>
  );
}

const ALL_STATUSES: DiffStatus[] = ['CHANGED', 'NEW', 'UNCHANGED'];
const GROUP_LABEL: Record<DiffStatus, string> = { CHANGED: 'Changed', NEW: 'New', UNCHANGED: 'Unchanged' };

export default function ReleaseDiffView({ app, colorMode = 'light' }: { app?: string; colorMode?: 'light' | 'dark' }) {
  const [sourceDir, setSourceDir] = useState(() => localStorage.getItem(appKey(app, 'sourceDir')) ?? '');
  const [sourceType, setSourceType] = useState<SourceType>((localStorage.getItem(appKey(app, 'sourceType')) as SourceType) || 'local');
  const [repo, setRepo] = useState(() => localStorage.getItem(appKey(app, 'repo')) ?? '');
  const [branch, setBranch] = useState(() => localStorage.getItem(appKey(app, 'branch')) ?? '');
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('');
  const [deps, setDeps] = useState<DepSource[]>(() => loadDeps(appKey(app, 'deps')));
  const src: SourceState = { sourceType, sourceDir, repo, branch };
  const onSrc = (p: Partial<SourceState>) => {
    if (p.sourceType !== undefined) setSourceType(p.sourceType);
    if (p.sourceDir !== undefined) setSourceDir(p.sourceDir);
    if (p.repo !== undefined) setRepo(p.repo);
    if (p.branch !== undefined) setBranch(p.branch);
  };
  const [report, setReport] = useState<VersionDiffReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One group is shown at a time, picked from the left-hand nav. Defaults to Changed.
  const [activeGroup, setActiveGroup] = useState<DiffStatus>('CHANGED');
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [flowApi, setFlowApi] = useState<{ api: string; version?: string } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const load = async () => {
    localStorage.setItem(appKey(app, 'sourceDir'), sourceDir);
    localStorage.setItem(appKey(app, 'sourceType'), sourceType);
    localStorage.setItem(appKey(app, 'repo'), repo);
    localStorage.setItem(appKey(app, 'branch'), branch);
    localStorage.setItem(appKey(app, 'country'), country);
    saveDeps(appKey(app, 'deps'), deps);
    setLoading(true); setError(null);
    try {
      const sp = sourceParams(src);
      const data = await fetchVersionDiff(sp.sourceDir, country, version, sp.repo, sp.branch, depParams(deps));
      setReport(data);
      setExpanded(new Set());
      setQuery('');
      // Preselect Changed; fall back to the first non-empty group so we don't land on an empty one.
      setActiveGroup(data.changedCount > 0 ? 'CHANGED'
        : data.newCount > 0 ? 'NEW'
          : data.unchangedCount > 0 ? 'UNCHANGED' : 'CHANGED');
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
  const visible = useMemo(() => {
    if (!report) return [];
    const q = query.trim().toLowerCase();
    return report.apis
      .filter((a) => a.status === activeGroup)
      .filter((a) => !q || searchHaystack(a).includes(q));
  }, [report, activeGroup, query]);

  const expandableKeys = useMemo(
    () => visible.filter((d) => d.routeDiffs?.length > 0).map(cardKey), [visible]);
  const allOpen = expandableKeys.length > 0 && expandableKeys.every((k) => expanded.has(k));

  const toggleOne = (k: string) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(k)) next.delete(k); else next.add(k);
    return next;
  });
  const toggleAll = () => setExpanded(allOpen ? new Set() : new Set(expandableKeys));

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

  // The PDF is the full release report (Changed + New), independent of which group is
  // on screen — the left-hand nav is for browsing, not for scoping the export.
  const exportApis = useMemo(
    () => (report ? report.apis.filter((a) => a.status !== 'UNCHANGED') : []), [report]);

  const exportPdf = () => {
    if (!report) return;
    exportDiffPdf(report, exportApis, false, app).catch(() => {});
  };

  return (
    <div className="impact">
      <div className="context-bar">
        <SourceFields value={src} onChange={onSrc} bar />
        <div style={{ width: 160 }}>
          <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
          <input value={country} placeholder="SG / MY / ID / TH / VN" onChange={(e) => setCountry(e.target.value)} />
        </div>
        <div style={{ width: 140 }}>
          <label>Target version <span style={{ color: '#dc2626' }}>*</span></label>
          <input list="diffVersionList" value={version} placeholder="9.18 or N/A" onChange={(e) => setVersion(e.target.value)}
                 onKeyDown={(e) => { if (e.key === 'Enter' && country.trim() && sourceValid(src) && version.trim()) load(); }} />
          <datalist id="diffVersionList">
            <option value="N/A" label="latest per API, else base route (unversioned repos)" />
          </datalist>
        </div>
        <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'flex-end' }}
                disabled={loading || !country.trim() || !sourceValid(src) || !version.trim()} onClick={load}
                title={!sourceValid(src) ? 'Enter the source (path or Bitbucket repo + branch)' : !country.trim() ? 'Enter a country first' : !version.trim() ? 'Enter a target version' : ''}>
          {loading ? 'Comparing…' : 'Compare'}
        </button>
      </div>

      {(report?.needsReview?.length ?? 0) > 0 && (
        <div className="dep-zone"><DependencyEditor deps={deps} onChange={setDeps} /></div>
      )}

      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {loading && <div className="impact-loading"><Loader messages={DIFF_MESSAGES} note="comparing versions" /></div>}

      {!loading && !report && !error && (
        <div className="impact-empty">
          <div className="impact-empty-title">Compare a release against the one before it</div>
          <div className="sub">Enter a target version (e.g. <b>9.18</b>) and click <b>Compare</b>. For every API the release touched, TraceGuard traces the whole flow at that version and at its immediate-lower version, then highlights exactly what changed — added, removed or modified — across the resolved Camel routes.</div>
        </div>
      )}

      {!loading && report && (
        <div className="impact-body diff-layout">
          <div className="diff-nav">
            <div className="diff-nav-head">Release {report.version || 'BASE'}{report.country ? ` · ${report.country}` : ''}</div>
            {ALL_STATUSES.map((s) => (
              <button key={s} className={'diff-nav-item ' + s.toLowerCase() + (activeGroup === s ? ' active' : '')}
                      aria-pressed={activeGroup === s} onClick={() => setActiveGroup(s)}>
                <span className="diff-nav-label">{GROUP_LABEL[s]}</span>
                <span className="diff-nav-count">{counts[s]}</span>
              </button>
            ))}
            {report.apis.length > 0 && (
              <button className="minibtn diff-nav-export" onClick={exportPdf} title="Download the full report as a PDF">⤓ Export PDF</button>
            )}
          </div>

          <div className="diff-main">
            <NeedsReviewBox items={report.needsReview ?? []} />
            {(() => {
              // The needs-review items are shown in their own highlighted box above; keep them out of
              // the plain warning banner so the two sections don't repeat the same lines.
              const review = new Set(report.needsReview ?? []);
              const other = report.warnings.filter((w) => !review.has(w));
              return other.length > 0 ? (
                <div className="warnbox">{other.map((w, i) => <div key={i}>⚠ {w}</div>)}</div>
              ) : null;
            })()}

            <div className="diff-main-head row between">
              <h2 style={{ margin: 0 }}>{GROUP_LABEL[activeGroup]} APIs <span className="muted">{visible.length}</span></h2>
              <span className="row" style={{ gap: 8 }}>
                <input className="diff-search" placeholder="🔍 filter by path, route or backend"
                       value={query} onChange={(e) => setQuery(e.target.value)} />
                {expandableKeys.length > 0 && (
                  <button className="linkbtn" onClick={toggleAll}>{allOpen ? 'Collapse all' : 'Expand all'}</button>
                )}
              </span>
            </div>

            {visible.length === 0 ? (
              <div className="impact-empty">
                <div className="impact-empty-title">
                  {report.apis.length === 0 ? 'Nothing to compare'
                    : query.trim() ? 'No matches'
                      : `No ${GROUP_LABEL[activeGroup].toLowerCase()} APIs`}
                </div>
                <div className="sub">
                  {report.apis.length === 0
                    ? 'No API resolves to this version in the selected scope.'
                    : query.trim()
                      ? <>Nothing matches “{query.trim()}” in this group. Clear the search or pick another group.</>
                      : `This release has no ${GROUP_LABEL[activeGroup].toLowerCase()} APIs.`}
                </div>
              </div>
            ) : (
              <div className="diff-list">
                {visible.map((d) => (
                  <ApiDiffCard key={cardKey(d)} d={d}
                               open={expanded.has(cardKey(d))} onToggle={() => toggleOne(cardKey(d))}
                               onViewFlow={() => setFlowApi({ api: d.api, version: d.targetVersion || report.version || undefined })}
                               onCopy={() => copyOne(d)} copied={copiedKey === cardKey(d)} />
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {flowApi && (
        <ApiFlowModal api={flowApi.api} version={flowApi.version} sourceDir={sourceParams(src).sourceDir}
                      repo={sourceParams(src).repo} branch={sourceParams(src).branch} country={country}
                      deps={depParams(deps)} colorMode={colorMode} onClose={() => setFlowApi(null)} />
      )}
    </div>
  );
}
