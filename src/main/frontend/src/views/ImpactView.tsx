import { Fragment, useMemo, useState } from 'react';
import { fetchImpactIndex } from '../api';
import type { ApiImpact, ImpactIndex, SourceType } from '../types';
import SourceFields, { sourceValid, sourceParams, type SourceState } from '../components/SourceFields';
import { backendPath } from '../spl';
import { exportImpactPdf } from '../impactPdf';
import Checklist from '../components/Checklist';
import SplunkPanel from '../components/SplunkPanel';
import LogAnalysisPanel from '../components/LogAnalysisPanel';
import ApiFlowModal from '../components/ApiFlowModal';
import Loader, { IMPACT_MESSAGES } from '../components/Loader';
import Collapsible from '../components/Collapsible';
import Steps, { type StepState } from '../components/Steps';

// The context (sourceDir + country + version) is remembered per application — Mighty
// and SPL are separate codebases — so switching apps restores that app's settings.
function appKey(app: string | undefined, f: string) { return `tracer.${app || 'Mighty'}.${f}`; }

export default function ImpactView({ app, colorMode = 'light' }: { app?: string; colorMode?: 'light' | 'dark' }) {
  const [sourceDir, setSourceDir] = useState(() => localStorage.getItem(appKey(app, 'sourceDir')) ?? '');
  const [sourceType, setSourceType] = useState<SourceType>((localStorage.getItem(appKey(app, 'sourceType')) as SourceType) || 'local');
  const [repo, setRepo] = useState(() => localStorage.getItem(appKey(app, 'repo')) ?? '');
  const [branch, setBranch] = useState(() => localStorage.getItem(appKey(app, 'branch')) ?? '');
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('');   // per-test: starts empty, never persisted
  const src: SourceState = { sourceType, sourceDir, repo, branch };
  const onSrc = (p: Partial<SourceState>) => {
    if (p.sourceType !== undefined) setSourceType(p.sourceType);
    if (p.sourceDir !== undefined) setSourceDir(p.sourceDir);
    if (p.repo !== undefined) setRepo(p.repo);
    if (p.branch !== undefined) setBranch(p.branch);
  };
  const [idx, setIdx] = useState<ImpactIndex | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The user's direct picks. The *effective* routes/backends below are derived by
  // adding what the selected APIs (and chosen routes) pull in automatically.
  const [manualRoutes, setManualRoutes] = useState<Set<string>>(new Set());
  const [manualBackends, setManualBackends] = useState<Set<string>>(new Set());
  const [selectedApis, setSelectedApis] = useState<Set<string>>(new Set());
  const [flowApi, setFlowApi] = useState<string | null>(null);   // which API's graph is open
  const [analysed, setAnalysed] = useState(false);               // a log report has landed (drives the steps)

  const load = async () => {
    // Remember this app's context (source + country).
    localStorage.setItem(appKey(app, 'sourceDir'), sourceDir);
    localStorage.setItem(appKey(app, 'sourceType'), sourceType);
    localStorage.setItem(appKey(app, 'repo'), repo);
    localStorage.setItem(appKey(app, 'branch'), branch);
    localStorage.setItem(appKey(app, 'country'), country);
    localStorage.removeItem(appKey(app, 'version'));   // version is per-test, never persisted
    setLoading(true); setError(null);
    try {
      const sp = sourceParams(src);
      const data = await fetchImpactIndex(sp.sourceDir, country, version, sp.repo, sp.branch);
      setIdx(data);
      setManualRoutes(new Set());
      setManualBackends(new Set());
      setSelectedApis(new Set());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Do NOT auto-load on mount — switching to this tab should not start the scan.
  // The user loads when ready by clicking the Load button.

  const toggle = (s: Set<string>, fn: (n: Set<string>) => void, item: string) => {
    const next = new Set(s);
    if (next.has(item)) next.delete(item); else next.add(item);
    fn(next);
  };
  const setMany = (s: Set<string>, fn: (n: Set<string>) => void, items: string[], on: boolean) => {
    const next = new Set(s);
    items.forEach((i) => (on ? next.add(i) : next.delete(i)));
    fn(next);
  };

  const apiByPath = useMemo<Record<string, ApiImpact>>(() => {
    const m: Record<string, ApiImpact> = {};
    idx?.apis.forEach((a) => { if (a.api) m[a.api] = a; });
    return m;
  }, [idx]);
  const routeBackends = useMemo<Record<string, string[]>>(() => idx?.routeBackends || {}, [idx]);

  // Forward selection: choosing an API auto-includes the routes it traverses and the
  // backends it calls; choosing a route auto-includes that route's backends. The
  // Changed-routes/backends checklists toggle only the *manual* picks on top of these.
  const effectiveRoutes = useMemo(() => {
    const s = new Set(manualRoutes);
    selectedApis.forEach((p) => apiByPath[p]?.routes.forEach((r) => s.add(r)));
    return s;
  }, [manualRoutes, selectedApis, apiByPath]);
  const effectiveBackends = useMemo(() => {
    const s = new Set(manualBackends);
    selectedApis.forEach((p) => apiByPath[p]?.backends.forEach((b) => s.add(b)));
    effectiveRoutes.forEach((r) => (routeBackends[r] || []).forEach((b) => s.add(b)));
    return s;
  }, [manualBackends, selectedApis, apiByPath, effectiveRoutes, routeBackends]);

  const impacted = useMemo<{ api: ApiImpact; viaRoutes: string[]; viaBackends: string[] }[]>(() => {
    if (!idx) return [];
    const out: { api: ApiImpact; viaRoutes: string[]; viaBackends: string[] }[] = [];
    for (const a of idx.apis) {
      const vr = a.routes.filter((r) => effectiveRoutes.has(r));
      const vb = a.backends.filter((b) => effectiveBackends.has(b));
      if (vr.length || vb.length) out.push({ api: a, viaRoutes: vr, viaBackends: vb });
    }
    return out;
  }, [idx, effectiveRoutes, effectiveBackends]);

  const hasChange = effectiveRoutes.size > 0 || effectiveBackends.size > 0;
  const feApis = useMemo(() => impacted.map((i) => i.api.api).filter(Boolean), [impacted]);
  // Show the API(s) the user actually picked first, then the ones merely sharing a
  // backend/route — so "8 of 36" reads as "your 1 + 7 that share its dependencies".
  const selectedImpactedCount = useMemo(
    () => impacted.filter((i) => selectedApis.has(i.api.api)).length, [impacted, selectedApis]);
  const sortedImpacted = useMemo(
    () => [...impacted].sort((a, b) =>
      (selectedApis.has(a.api.api) ? 0 : 1) - (selectedApis.has(b.api.api) ? 0 : 1)),
    [impacted, selectedApis]);

  // Direct API selection drives the Splunk query and scopes the log analysis.
  const allApiPaths = useMemo(() => (idx ? [...new Set(idx.apis.map((a) => a.api).filter(Boolean))] : []), [idx]);
  const selectedApiList = useMemo(() => [...selectedApis], [selectedApis]);
  // The query covers every backend implied by the current selection.
  const splBackends = useMemo(() => [...effectiveBackends], [effectiveBackends]);
  // Log analysis: a backend that was pulled in by an API is reported *under* that API
  // (its drill-down), not as a separate backend row — only a directly-ticked backend
  // gets its own backend-only section. Avoids showing one API's backend twice.
  const selectedBackendList = useMemo(() => [...manualBackends], [manualBackends]);
  // Backend URL → traced service version(s), merged across the release's APIs.
  const backendVersionMap = useMemo(() => {
    const m: Record<string, string> = {};
    idx?.apis.forEach((a) => a.backendVersions && Object.entries(a.backendVersions).forEach(([url, ver]) => {
      if (!m[url]) m[url] = ver;
      else if (!m[url].split(' / ').includes(ver)) m[url] += ' / ' + ver;
    }));
    return m;
  }, [idx]);
  // Backend api → its hosturl (what the host logs) — so the Splunk query searches the hosturl.
  const backendHosturlMap = useMemo(() => {
    const m: Record<string, string> = {};
    idx?.apis.forEach((a) => a.backendHosturls && Object.entries(a.backendHosturls).forEach(([url, h]) => { if (!m[url]) m[url] = h; }));
    return m;
  }, [idx]);

  const exportPdf = () => {
    if (!idx) return;
    exportImpactPdf({
      app, version, country,
      totalApis: idx.apis.length,
      changedRoutes: [...effectiveRoutes],
      changedBackends: [...effectiveBackends],
      rows: sortedImpacted.map((i) => ({
        api: i.api.api, operation: i.api.operation, resolvedRoute: i.api.resolvedRoute,
        selected: selectedApis.has(i.api.api), viaRoutes: i.viaRoutes, viaBackends: i.viaBackends,
      })),
      backendVersions: backendVersionMap,
    }).catch(() => {});
  };

  const loaded = !!idx;
  const picked = selectedApis.size > 0;
  const steps: { label: string; state: StepState }[] = [
    { label: 'Load', state: loaded ? 'done' : 'active' },
    { label: 'Pick APIs', state: !loaded ? 'todo' : (picked ? 'done' : 'active') },
    { label: 'Query / upload', state: !picked ? 'todo' : (analysed ? 'done' : 'active') },
    { label: 'Results', state: analysed ? 'active' : 'todo' },
  ];

  return (
    <div className="impact">
      <div className="context-bar">
        <SourceFields value={src} onChange={onSrc} bar />
        <div style={{ width: 160 }}>
          <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
          <input value={country} placeholder="SG / MY / ID / TH / VN" onChange={(e) => setCountry(e.target.value)} />
        </div>
        <div style={{ width: 140 }}>
          <label>Client version</label>
          <input value={version} placeholder="9.4" onChange={(e) => setVersion(e.target.value)} />
        </div>
        <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'flex-end' }}
                disabled={loading || !country.trim() || !sourceValid(src)} onClick={load}
                title={!sourceValid(src) ? 'Enter the source (path or Bitbucket repo + branch)' : !country.trim() ? 'Select a country first' : ''}>
          {loading ? 'Loading…' : 'Load'}
        </button>
      </div>

      <Steps steps={steps} />

      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {loading && <div className="impact-loading"><Loader messages={IMPACT_MESSAGES} note="building the impact index" /></div>}

      {!loading && !idx && !error && (
        <div className="impact-empty">
          <div className="impact-empty-title">Ready when you are</div>
          <div className="sub">Set the source directory and <b>country</b> (and optional client version) above, then click <b>Load</b> to scan the framework and list this release&rsquo;s APIs.</div>
        </div>
      )}

      {!loading && idx && (
        <>
        <div className="impact-body">
          <div className="impact-left">
            <div className="panel">
              <div className="row between">
                <h2 style={{ margin: 0 }}>APIs to analyse</h2>
                <span className="muted">{selectedApis.size}/{allApiPaths.length} selected · {idx.version || 'BASE'}{idx.country ? ', ' + idx.country : ''}</span>
              </div>
              <div className="sub">Selecting an API scopes the Splunk query + log check and auto‑ticks its routes/backends — no need to dig through the source.</div>
            </div>
            <Checklist title="APIs" items={allApiPaths} selected={selectedApis}
                       onToggle={(i) => toggle(selectedApis, setSelectedApis, i)}
                       onSetMany={(items, on) => setMany(selectedApis, setSelectedApis, items, on)}
                       renderHint={(apiPath) => {
                         const a = apiByPath[apiPath];
                         if (!a) return null;
                         const route = a.resolvedRoute || a.routes[0];
                         const bes = a.backends || [];
                         const be = bes.length === 1 ? backendPath(bes[0])
                           : bes.length > 1 ? `${bes.length} backends` : null;
                         if (!route && !be) return null;
                         return (
                           <>→ {route && <span className="hint-route">{route}</span>}
                             {be && <> → <span className="hint-be">{be}</span></>}</>
                         );
                       }} />

            {selectedApis.size > 0 && (
              <div className="panel">
                <h2>Selected — routes &amp; backends <span className="muted">{selectedApis.size} API{selectedApis.size > 1 ? 's' : ''}</span></h2>
                {selectedApiList.map((p) => {
                  const a = apiByPath[p];
                  if (!a) return null;
                  return (
                    <div className="sel-api" key={p}>
                      <div className="sel-api-path row between">
                        <code>{a.api}</code>
                        <button className="linkbtn" onClick={() => setFlowApi(a.api)}>View flow ▸</button>
                      </div>
                      <div className="sel-row">
                        <span className="sel-label">routes</span>
                        {a.routes.length ? a.routes.map((r) => <span key={r} className="tag route">{r}</span>) : <span className="muted">—</span>}
                      </div>
                      <div className="sel-row">
                        <span className="sel-label">backends</span>
                        {a.backends.length ? a.backends.map((b) => (
                          <span key={b} className="tag backend">{backendPath(b)}{backendVersionMap[b] ? ` · svc ${backendVersionMap[b]}` : ''}</span>
                        )) : <span className="muted">—</span>}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            <Collapsible title="Routes & backends" hint="advanced — tick directly">
              <div className="sub" style={{ padding: '0 2px 4px' }}>The lists are auto-ticked from the selected API(s). You can also tick a route/backend directly to find other impacted APIs — a route also pulls in the backend it calls.</div>
              <Checklist title="Changed routes" items={idx.allRoutes} selected={effectiveRoutes}
                         onToggle={(i) => toggle(manualRoutes, setManualRoutes, i)}
                         onSetMany={(items, on) => setMany(manualRoutes, setManualRoutes, items, on)} />
              <Checklist title="Changed backends" items={idx.allBackends} selected={effectiveBackends}
                         onToggle={(i) => toggle(manualBackends, setManualBackends, i)}
                         onSetMany={(items, on) => setMany(manualBackends, setManualBackends, items, on)} />
            </Collapsible>
          </div>

          <div className="impact-right">
            <div className="panel">
              <div className="row between">
                <h2 style={{ margin: 0 }}>Impacted APIs <span className="muted">{impacted.length} of {idx.apis.length}</span></h2>
                {impacted.length > 0 && (
                  <span className="row" style={{ gap: 6 }}>
                    <button className="minibtn" onClick={() => setMany(selectedApis, setSelectedApis, feApis, true)}>+ select for analysis</button>
                    <button className="minibtn" onClick={exportPdf} title="Download a shareable PDF report">⤓ Export PDF</button>
                  </span>
                )}
              </div>
              {!hasChange && <div className="sub">Select an API (or a route/backend) on the left to see impacted APIs.</div>}
              {hasChange && impacted.length === 0 && <div className="sub">No APIs are impacted by the selected change.</div>}
              {impacted.length > 0 && selectedImpactedCount > 0 && impacted.length > selectedImpactedCount && (
                <div className="sub"><b>{selectedImpactedCount}</b> you selected · <b>{impacted.length - selectedImpactedCount}</b> more share a backend or route with it.</div>
              )}
              {impacted.length > 0 && (
                <table className="grid">
                  <thead><tr><th>API</th><th>Operation</th><th>Resolves to</th><th>Impacted via</th><th></th></tr></thead>
                  <tbody>
                    {sortedImpacted.map((i, idx) => (
                      <Fragment key={i.api.api + i.api.operation}>
                        {idx === selectedImpactedCount && selectedImpactedCount > 0 && selectedImpactedCount < sortedImpacted.length && (
                          <tr className="impacted-divider"><td colSpan={5}>↳ Blast radius — {sortedImpacted.length - selectedImpactedCount} API(s) that share a backend or route with your selection</td></tr>
                        )}
                        <tr className={selectedApis.has(i.api.api) ? 'impacted-selected' : 'impacted-indirect'}>
                        <td><code>{i.api.api}</code>{selectedApis.has(i.api.api) && <span className="sel-badge">selected</span>}</td>
                        <td>{i.api.operation}</td>
                        <td><code>{i.api.resolvedRoute || '—'}</code></td>
                        <td>
                          {i.viaRoutes.map((r) => <span key={r} className="tag route">{r}</span>)}
                          {i.viaBackends.map((b) => (
                            <span key={b} className="tag backend">
                              {backendPath(b)}{backendVersionMap[b] ? ` · svc ${backendVersionMap[b]}` : ''}
                            </span>
                          ))}
                        </td>
                        <td><button className="linkbtn" onClick={() => setFlowApi(i.api.api)} title="Show this API's route graph">flow ▸</button></td>
                      </tr>
                      </Fragment>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <Collapsible title="Splunk query" hint="generate &amp; copy — for running in Splunk">
              <SplunkPanel
                title="Splunk query — selected APIs"
                app={app}
                version={version}
                frontendApis={selectedApiList}
                backendApis={splBackends}
                backendVersions={backendVersionMap}
                backendHosturls={backendHosturlMap}
                hint="Run this in Splunk, export the result (CSV/JSON), then upload it under “Verify with logs” below."
              />
            </Collapsible>
          </div>
        </div>

        <div style={{ padding: '0 18px 18px' }}>
          <LogAnalysisPanel version={version} country={country} sourceDir={sourceParams(src).sourceDir}
                            repo={sourceParams(src).repo} branch={sourceParams(src).branch} app={app}
                            selectedApis={selectedApiList} selectedBackends={selectedBackendList}
                            onReport={setAnalysed} />
        </div>
        </>
      )}

      {flowApi && (
        <ApiFlowModal api={flowApi} version={version} sourceDir={sourceParams(src).sourceDir}
                      repo={sourceParams(src).repo} branch={sourceParams(src).branch} country={country}
                      colorMode={colorMode} onClose={() => setFlowApi(null)} />
      )}
    </div>
  );
}
