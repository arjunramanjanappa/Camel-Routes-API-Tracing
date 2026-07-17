import { useEffect, useMemo, useRef, useState } from 'react';
import { analyze, fetchMeta } from '../api';
import type { AnalyzeResponse, CatalogResponse, DepSource, Meta } from '../types';
import { derive, opNamesOf } from '../graphModel';
import { apiIdsForGroup, baseCount, filterGraphByApis, inScopeCount, isNaVersion, NO_ROUTE } from '../catalog';
import ControlPanel from '../components/ControlPanel';
import { sourceParams } from '../components/SourceFields';
import ModuleSummary, { type ModuleStat } from '../components/ModuleSummary';
import NeedsReviewBox from '../components/NeedsReviewBox';
import { depParams, loadDeps, saveDeps } from '../deps';
import ResultPanels from '../components/ResultPanels';
import DetailPanel from '../components/DetailPanel';
import RouteGraph, { type GraphHandle } from '../components/RouteGraph';
import Legend from '../components/Legend';
import Loader, { SCAN_MESSAGES } from '../components/Loader';
import { exportApiTracePdf } from '../apiTracePdf';
import { analyzeModules, moduleValid, type ModuleResult, type ModuleSource } from '../modules';
import { useAppModules } from '../appModules';

function appKey(app: string, f: string) { return `tracer.${app}.${f}`; }

const EMPTY_META: Meta = { countries: [], versions: [], transferTypes: [] };

function asCatalog(r: AnalyzeResponse | null): CatalogResponse | null {
  return r && r.mode === 'catalog' ? r : null;
}

export default function TraceView({ app = 'Mighty', colorMode }: { app?: string; colorMode: 'light' | 'dark' }) {
  const { modules, setModules, fromConfig, hasConfig, hasLocal, resetToConfig, saveAsDefault, saving } = useAppModules(app);
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) || '');
  const [version, setVersion] = useState('N/A');   // mandatory; N/A = latest per API, else base (per-run)
  const [meta, setMeta] = useState<Meta>(EMPTY_META);
  const [catalogs, setCatalogs] = useState<ModuleResult<AnalyzeResponse>[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [data, setData] = useState<AnalyzeResponse | null>(null);   // the active view (catalog or a single API)
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [graphGroup, setGraphGroup] = useState<string>('ALL');   // which release version's subgraph the graph shows
  const [exporting, setExporting] = useState(false);
  const [modulesOpen, setModulesOpen] = useState(true);   // collapses to chips after a successful analysis
  const [deps] = useState<DepSource[]>(() => loadDeps(appKey(app, 'deps')));
  const graphRef = useRef<GraphHandle>(null);

  const names = useMemo(() => Object.fromEntries(
    catalogs.map((r) => [r.module.id, r.name])), [catalogs]);
  const activeModule = modules.find((m) => m.id === activeId) || modules[0];

  // Country/version dropdowns come from the first (main) module.
  const loadMeta = async (m: ModuleSource) => {
    const sp = sourceParams(m);
    setMeta(await fetchMeta(sp.sourceDir, country, sp.repo, sp.branch));
  };

  const analyseAll = async () => {
    localStorage.setItem(appKey(app, 'country'), country);
    saveDeps(appKey(app, 'deps'), deps);
    setLoading(true); setError(null); setSelectedId(null); setSearch('');
    try {
      const results = await analyzeModules(
        modules.filter(moduleValid),
        (m) => analyze({ version, country, api: '', app, dep: depParams(deps), ...sourceParams(m) }),
        (r) => asCatalog(r)?.moduleName,
      );
      setCatalogs(results);
      const first = results.find((r) => r.result) || results[0];
      setActiveId(first?.module.id ?? null);
      setData(first?.result ?? null);
      if (results.length > 1) setModulesOpen(false);   // collapse the editor so results have the screen
      const cs = asCatalog(first?.result ?? null)?.availableCountries;
      if (cs && cs.length) setMeta((mm) => ({ ...mm, countries: cs }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setLoading(false); }
  };

  const selectModule = (id: string) => {
    setActiveId(id); setSelectedId(null); setSearch('');
    setData(catalogs.find((r) => r.module.id === id)?.result ?? null);
  };
  const onCatalogAll = () => setData(catalogs.find((r) => r.module.id === activeId)?.result ?? null);

  // Click an API → trace just that one, within the ACTIVE module, to show its flow graph.
  const onOpenApi = async (api: string, v: string | undefined) => {
    if (!activeModule) return;
    setLoading(true); setError(null); setSelectedId(null);
    try {
      const res = await analyze({ version: v || version, country, api, app, dep: depParams(deps), ...sourceParams(activeModule) });
      setData(res);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  };

  const clientVersion = (data && data.requestedVersion) || '';

  // Release Scope graph is grouped by release version so it isn't one big undifferentiated chunk.
  // With a concrete release the graph defaults to that release's APIs; N/A/base and All are a click away.
  const catForGraph = data?.mode === 'catalog' ? data : null;
  const graphGroups = useMemo(
    () => (catForGraph ? catForGraph.groups.filter((g) => g.version !== NO_ROUTE) : []),
    [catForGraph]
  );
  useEffect(() => {
    if (graphGroups.length > 1) {
      setGraphGroup((prev) => (graphGroups.some((g) => g.version === prev)
        ? prev
        : (graphGroups.find((g) => g.version !== 'N/A')?.version ?? graphGroups[0].version)));
    } else {
      setGraphGroup('ALL');
    }
  }, [graphGroups]);

  const graphForRender = useMemo(() => {
    const g = data?.graph || { nodes: [], edges: [] };
    if (!catForGraph || graphGroup === 'ALL') return g;
    const grp = catForGraph.groups.find((x) => x.version === graphGroup);
    return grp ? filterGraphByApis(g, apiIdsForGroup(grp)) : g;
  }, [data, catForGraph, graphGroup]);

  const derived = useMemo(
    () => (data ? derive(graphForRender, opNamesOf(data), clientVersion) : null),
    [data, graphForRender, clientVersion]
  );
  const selectedNode = selectedId && derived ? derived.byId.get(selectedId) || null : null;

  const exportPdf = async () => {
    setExporting(true); setError(null);
    try {
      const cats = catalogs.map((r) => ({ name: r.name, cat: asCatalog(r.result), error: r.error }))
        .filter((c) => c.cat || c.error);
      if (cats.length) await exportApiTracePdf(cats as { name: string; cat: CatalogResponse | null; error?: string }[], app, version, country);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setExporting(false); }
  };

  const onField = (patch: { country?: string; version?: string }) => {
    if (patch.country !== undefined) { setCountry(patch.country); if (modules[0]) loadMeta(modules[0]); }
    if (patch.version !== undefined) setVersion(patch.version);
  };

  const scopeRollup = useMemo<ModuleStat[]>(() => {
    if (catalogs.length <= 1) return [];
    const cats = catalogs.map((r) => asCatalog(r.result)).filter((c): c is CatalogResponse => !!c);
    const apis = cats.reduce((n, c) => n + inScopeCount(c), 0);
    const base = cats.reduce((n, c) => n + baseCount(c), 0);
    const tiles: ModuleStat[] = [
      { label: 'modules', value: catalogs.length, tone: 'muted' },
      { label: 'APIs in scope', value: apis, tone: 'info' },
    ];
    if (base > 0) tiles.push({ label: 'base', value: base, tone: 'muted' });
    return tiles;
  }, [catalogs]);

  const statsOf = (r: ModuleResult<AnalyzeResponse>): ModuleStat[] => {
    const cat = asCatalog(r.result);
    if (!cat) return [];
    const stats: ModuleStat[] = [{ label: 'APIs', value: inScopeCount(cat), tone: 'info' }];
    // For a concrete release, note the base-fallback (N/A) APIs separately so the "APIs"
    // number reads as "changed this release", not "everything in the repo".
    const base = baseCount(cat);
    if (base > 0 && !isNaVersion(cat.requestedVersion) && !cat.unversioned) {
      stats.push({ label: 'base', value: base, tone: 'muted' });
    }
    return stats;
  };

  return (
    <div className="scope">
      <ControlPanel modules={modules} onModulesChange={setModules} names={names}
                    country={country} version={version} meta={meta} loading={loading}
                    modulesOpen={modulesOpen} onToggleModules={() => setModulesOpen((o) => !o)}
                    onField={onField} onAnalyse={analyseAll}
                    config={{ fromConfig, hasConfig, hasLocal, onReset: resetToConfig, onSaveDefault: saveAsDefault, saving }} />
      {catalogs.length > 1 && (
        <ModuleSummary results={catalogs} activeId={activeId} onSelect={selectModule}
                       statsOf={statsOf} unversionedOf={(r) => !!asCatalog(r.result)?.unversioned}
                       rollup={scopeRollup}
                       onExport={exportPdf} exportDisabled={exporting || !catalogs.length}
                       exportLabel={exporting ? 'PDF…' : '⤓ Export PDF'}
                       exportTitle="One PDF covering every module for this release" />
      )}
      <div className="layout">
        <aside className="sidebar">
          {catalogs.length > 1 && activeModule && (
            <div className="sub" style={{ padding: '2px 2px 8px' }}>Showing module <b>{names[activeModule.id] || 'module'}</b> — the report (PDF) covers all {catalogs.length}.</div>
          )}
          {error && <div className="err">Error: {error}</div>}
          {data && data.needsReview && data.needsReview.length > 0 && (
            <NeedsReviewBox items={data.needsReview} />
          )}
          {selectedNode && <DetailPanel node={selectedNode} onClose={() => setSelectedId(null)} />}
          {data && <ResultPanels data={data} onBackToCatalog={onCatalogAll} onOpenApi={onOpenApi} />}
          {!data && !error && (
            <div className="sub" style={{ padding: '4px 2px' }}>Add your modules, set country and version above, then <b>Analyse modules</b>.</div>
          )}
        </aside>
        <div className="main">
        {graphGroups.length > 1 && (
          <div className="graph-groups">
            <span className="gg-label">Graph:</span>
            {graphGroups.map((g) => (
              <button key={g.version}
                      className={'gg-chip' + (g.version === 'N/A' ? ' na' : '') + (graphGroup === g.version ? ' on' : '')}
                      onClick={() => setGraphGroup(g.version)}
                      title={g.version === 'N/A' ? 'Base-route APIs — no route at this release' : 'APIs on release ' + g.version}>
                {g.version === 'N/A' ? 'Base' : 'R' + g.version}<b>{g.traces.length}</b>
              </button>
            ))}
            <button className={'gg-chip' + (graphGroup === 'ALL' ? ' on' : '')} onClick={() => setGraphGroup('ALL')}
                    title="All APIs in the catalog">
              All<b>{graphGroups.reduce((n, g) => n + g.traces.length, 0)}</b>
            </button>
          </div>
        )}
        {derived && <RouteGraph ref={graphRef} derived={derived} selectedId={selectedId} search={search} colorMode={colorMode} onSelect={setSelectedId} />}
        <div className="toolbar">
          <input placeholder="Search nodes…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="minibtn" onClick={() => graphRef.current?.fit()} title="Zoom out to the whole graph">Fit</button>
          <button className="minibtn" onClick={() => graphRef.current?.exportPng()}>PNG</button>
          {/* Multi-module: the report export lives in the common "By module" strip header instead. */}
          {catalogs.length <= 1 && (
            <button className="minibtn" onClick={exportPdf} disabled={exporting || !catalogs.length}
                    title="Export the report for this release">
              {exporting ? 'PDF…' : 'PDF'}
            </button>
          )}
        </div>
        <div className="toolhint">drag to pan · scroll to zoom · click an API for its own flow</div>
        {loading && <div className="overlay"><Loader messages={SCAN_MESSAGES} note="multi-module analysis" /></div>}
        {!derived && !loading && !error && (
          <div className="graph-empty"><div className="impact-empty">
            <div className="impact-empty-title">Ready when you are</div>
            <div className="sub">Add each module (Mighty + its sub-modules), set country + version, then <b>Analyse modules</b>.</div>
          </div></div>
        )}
        <Legend />
        </div>
      </div>
    </div>
  );
}
