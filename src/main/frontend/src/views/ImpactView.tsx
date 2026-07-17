import { Fragment, useMemo, useState } from 'react';
import { fetchImpactIndex } from '../api';
import type { ApiImpact, DepSource, ImpactIndex, Meta } from '../types';
import { sourceParams } from '../components/SourceFields';
import ControlPanel from '../components/ControlPanel';
import ModuleSummary, { type ModuleStat } from '../components/ModuleSummary';
import NeedsReviewBox from '../components/NeedsReviewBox';
import { depParams, loadDeps, saveDeps } from '../deps';
import { backendPath } from '../spl';
import { exportImpactPdf } from '../impactPdf';
import Checklist from '../components/Checklist';
import SplunkPanel from '../components/SplunkPanel';
import LogAnalysisPanel, { type LogModule } from '../components/LogAnalysisPanel';
import ApiFlowModal from '../components/ApiFlowModal';
import Loader, { IMPACT_MESSAGES } from '../components/Loader';
import Collapsible from '../components/Collapsible';
import Steps, { type StepState } from '../components/Steps';
import { analyzeModules, markerAppFor, moduleValid, type ModuleResult } from '../modules';
import { useAppModules } from '../appModules';

// The context (country + modules) is remembered per application — Mighty and SPL are separate
// codebases — so switching apps restores that app's settings. Version is per-test, never persisted.
function appKey(app: string, f: string) { return `tracer.${app}.${f}`; }
const EMPTY_META: Meta = { countries: [], versions: [], transferTypes: [] };

export default function ImpactView({ app = 'Mighty', colorMode = 'light' }: { app?: string; colorMode?: 'light' | 'dark' }) {
  const { modules, setModules, fromConfig, hasConfig, hasLocal, resetToConfig, saveAsDefault, saving } = useAppModules(app);
  const [modulesOpen, setModulesOpen] = useState(true);   // collapses to chips after a multi-module analysis
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('N/A');   // mandatory; N/A = latest per API, else base. Per-test, never persisted
  const [deps] = useState<DepSource[]>(() => loadDeps(appKey(app, 'deps')));
  const [reports, setReports] = useState<ModuleResult<ImpactIndex>[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The user's direct picks (for the ACTIVE module). The *effective* routes/backends below are
  // derived by adding what the selected APIs (and chosen routes) pull in automatically.
  const [manualRoutes, setManualRoutes] = useState<Set<string>>(new Set());
  const [manualBackends, setManualBackends] = useState<Set<string>>(new Set());
  const [selectedApis, setSelectedApis] = useState<Set<string>>(new Set());
  const [flowApi, setFlowApi] = useState<string | null>(null);   // which API's graph is open
  const [analysed, setAnalysed] = useState(false);               // a log report has landed (drives the steps)

  const names = useMemo(() => Object.fromEntries(reports.map((r) => [r.module.id, r.name])), [reports]);
  const activeModule = modules.find((m) => m.id === activeId) || modules[0];
  const idx = reports.find((r) => r.module.id === activeId)?.result ?? null;
  const activeSrc = activeModule ? sourceParams(activeModule) : { sourceDir: '', repo: '', branch: '' };
  // The active module's log marker: the entry app's main module (index 0) uses Mighty/SPL markers;
  // every sub-module logs as SPL. Drives the Splunk query's <app>Message / <app>HostMessage shape,
  // matching how the log correlation is scoped per module. (spl-secure is auto-detected via `secure`.)
  const activeMarkerApp = markerAppFor(app, Math.max(0, modules.findIndex((m) => m.id === activeId)));

  const resetSelection = () => { setManualRoutes(new Set()); setManualBackends(new Set()); setSelectedApis(new Set()); setFlowApi(null); };

  const load = async () => {
    localStorage.setItem(appKey(app, 'country'), country);
    localStorage.removeItem(appKey(app, 'version'));   // version is per-test, never persisted
    saveDeps(appKey(app, 'deps'), deps);
    setLoading(true); setError(null); setAnalysed(false); resetSelection();
    try {
      const results = await analyzeModules(
        modules.filter(moduleValid),
        (m) => { const sp = sourceParams(m); return fetchImpactIndex(sp.sourceDir, country, version, sp.repo, sp.branch, depParams(deps), app); },
        (r) => r.moduleName,
      );
      setReports(results);
      const first = results.find((r) => r.result) || results[0];
      setActiveId(first?.module.id ?? null);
      if (results.length > 1) setModulesOpen(false);   // collapse the editor so results have the screen
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Switch which module the API picker / Splunk / footprint below is scoped to. Selection is
  // per-module (routes/backends differ), so it resets — the log verification keeps its own results.
  const selectModule = (id: string) => { setActiveId(id); resetSelection(); };

  const onField = (patch: { country?: string; version?: string }) => {
    if (patch.country !== undefined) setCountry(patch.country);
    if (patch.version !== undefined) setVersion(patch.version);
  };

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

  // Every analysed module, with its marker flavour + source, for the multi-module log verification.
  const logModules = useMemo<LogModule[]>(() =>
    reports.filter((r) => r.result).map((r) => {
      const sp = sourceParams(r.module);
      const pos = modules.findIndex((x) => x.id === r.module.id);   // module 0 = entry app's main module
      return { id: r.module.id, name: r.name, app: markerAppFor(app, pos < 0 ? 1 : pos), sourceDir: sp.sourceDir, repo: sp.repo, branch: sp.branch };
    }), [reports, modules, app]);

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
      needsReview: idx.needsReview,
    }).catch(() => {});
  };

  const statsOf = (r: ModuleResult<ImpactIndex>): ModuleStat[] => {
    const ix = r.result;
    if (!ix) return [];
    return [{ label: 'APIs', value: ix.apis.length, tone: 'info' }];
  };

  const loaded = !!idx;
  const picked = selectedApis.size > 0;
  const multi = reports.length > 1;
  const steps: { label: string; state: StepState }[] = [
    { label: 'Analyse', state: loaded ? 'done' : 'active' },
    { label: 'Pick APIs', state: !loaded ? 'todo' : (picked ? 'done' : 'active') },
    { label: 'Query / upload', state: !picked ? 'todo' : (analysed ? 'done' : 'active') },
    { label: 'Results', state: analysed ? 'active' : 'todo' },
  ];

  return (
    <div className="impact">
      <ControlPanel modules={modules} onModulesChange={setModules} names={names}
                    country={country} version={version} meta={EMPTY_META} loading={loading}
                    modulesOpen={modulesOpen} onToggleModules={() => setModulesOpen((o) => !o)}
                    onField={onField} onAnalyse={load}
                    config={{ fromConfig, hasConfig, hasLocal, onReset: resetToConfig, onSaveDefault: saveAsDefault, saving }} />

      {multi && (
        <ModuleSummary results={reports} activeId={activeId} onSelect={selectModule}
                       statsOf={statsOf} unversionedOf={(r) => !!r.result?.unversioned} />
      )}


      <Steps steps={steps} />

      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {loading && <div className="impact-loading"><Loader messages={IMPACT_MESSAGES} note="building the impact index per module" /></div>}

      {!loading && !idx && !error && (
        <div className="impact-empty">
          <div className="impact-empty-title">Ready when you are</div>
          <div className="sub">Add your modules (the entry app + its sub-modules), set <b>country</b> and <b>release version</b> above (use <b>N/A</b> for each API&rsquo;s latest version), then click <b>Analyse modules</b>. The uploaded log is then checked against every module so it&rsquo;s clear which repo&rsquo;s APIs were tested.</div>
        </div>
      )}

      {!loading && idx && (
        <>
        {multi && activeModule && (
          <div className="sub" style={{ padding: '0 18px 4px' }}>Picking APIs for module <b>{names[activeModule.id] || 'module'}</b> — the log verification below covers all {logModules.length} modules in one report.</div>
        )}
        {idx.needsReview && idx.needsReview.length > 0 && (
          <div style={{ padding: '0 18px' }}>
            <NeedsReviewBox items={idx.needsReview} />
          </div>
        )}
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
                    {/* Multi-module: the one Release Test report is the log verification below (all modules),
                        so the per-module impact-footprint export is hidden to keep a single, common export. */}
                    {!multi && (
                      <button className="minibtn" onClick={exportPdf} title="Download a shareable PDF report">⤓ Export PDF</button>
                    )}
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
                    {sortedImpacted.map((i, ix) => (
                      <Fragment key={i.api.api + i.api.operation}>
                        {ix === selectedImpactedCount && selectedImpactedCount > 0 && selectedImpactedCount < sortedImpacted.length && (
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
                title={multi ? `Splunk query — ${names[activeModule?.id ?? ''] || 'module'}` : 'Splunk query — selected APIs'}
                app={activeMarkerApp}
                version={version}
                secure={!!idx.commandDispatch}
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
          <LogAnalysisPanel version={version} country={country}
                            sourceDir={activeSrc.sourceDir} repo={activeSrc.repo} branch={activeSrc.branch} app={app}
                            selectedApis={selectedApiList} selectedBackends={selectedBackendList}
                            modules={logModules}
                            deps={depParams(deps)} needsReview={idx.needsReview}
                            onReport={setAnalysed} />
        </div>
        </>
      )}

      {flowApi && activeModule && (
        <ApiFlowModal api={flowApi} version={version} sourceDir={activeSrc.sourceDir}
                      repo={activeSrc.repo} branch={activeSrc.branch} country={country} app={app}
                      deps={depParams(deps)} colorMode={colorMode} onClose={() => setFlowApi(null)} />
      )}
    </div>
  );
}
