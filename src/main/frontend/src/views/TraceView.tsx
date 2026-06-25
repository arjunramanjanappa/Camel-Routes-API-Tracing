import { useMemo, useRef, useState } from 'react';
import { analyze, fetchMeta } from '../api';
import type { AnalyzeResponse, Meta, TraceParams } from '../types';
import { derive, opNamesOf } from '../graphModel';
import ControlPanel from '../components/ControlPanel';
import ResultPanels from '../components/ResultPanels';
import DetailPanel from '../components/DetailPanel';
import RouteGraph, { type GraphHandle } from '../components/RouteGraph';
import Legend from '../components/Legend';
import Loader, { SCAN_MESSAGES } from '../components/Loader';
import { exportApiTracePdf } from '../apiTracePdf';

// Only the app CONTEXT (sourceDir + country) is remembered per application — Mighty
// and SPL are separate codebases — so switching apps restores that app's settings.
// The "what" inputs (api/version/transferType) start EMPTY each load (per-test).
const PERSIST: (keyof TraceParams)[] = ['sourceDir', 'country'];

function appKey(app: string, f: string) { return `tracer.${app}.${f}`; }

function loadParams(app: string): TraceParams {
  const p: TraceParams = {};
  PERSIST.forEach((f) => {
    const v = localStorage.getItem(appKey(app, f));
    if (v !== null) p[f] = v;
  });
  return p;
}

const EMPTY_META: Meta = { countries: [], versions: [], transferTypes: [] };

export default function TraceView({ app = 'Mighty', colorMode }: { app?: string; colorMode: 'light' | 'dark' }) {
  const [params, setParams] = useState<TraceParams>(() => loadParams(app));
  const [meta, setMeta] = useState<Meta>(EMPTY_META);
  const [data, setData] = useState<AnalyzeResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [exporting, setExporting] = useState(false);
  const graphRef = useRef<GraphHandle>(null);

  const persist = (p: TraceParams) => {
    PERSIST.forEach((f) => localStorage.setItem(appKey(app, f), p[f] || ''));
    localStorage.removeItem(appKey(app, 'version'));   // clear any version persisted by an older build
  };
  const loadMeta = async (p: TraceParams) => setMeta(await fetchMeta(p.sourceDir, p.country));

  const runTrace = async (p: TraceParams) => {
    persist(p);
    setLoading(true);
    setError(null);
    setSelectedId(null);
    setSearch('');
    try {
      const res = await analyze(p);
      setData(res);
      if (res.availableCountries && res.availableCountries.length) {
        setMeta((m) => ({ ...m, countries: res.availableCountries }));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Do NOT auto-trace on mount — picking an app should not start the (long) scan.
  // The user traces when ready by clicking Trace; meta (dropdowns) loads then too.

  const onChange = (patch: Partial<TraceParams>) => {
    const next = { ...params, ...patch };
    setParams(next);
    persist(next);
    if ('sourceDir' in patch || 'country' in patch) loadMeta(next);
  };
  const onTrace = () => runTrace(params);
  const onCatalogAll = () => { const next = { ...params, api: '' }; setParams(next); runTrace(next); };
  const onOpenApi = (api: string, version: string | undefined) => {
    const next = { ...params, api, version: version || '' };
    setParams(next);
    runTrace(next);
  };

  const clientVersion = (data && data.requestedVersion) || '';
  const derived = useMemo(
    () => (data ? derive(data.graph || { nodes: [], edges: [] }, opNamesOf(data), clientVersion) : null),
    [data, clientVersion]
  );
  const selectedNode = selectedId && derived ? derived.byId.get(selectedId) || null : null;
  // PDF export is always the WHOLE release catalog (every impacted API), even when
  // the current view is a single API — re-fetch the catalog so the report can't
  // silently under-report what a release touches.
  const exportPdf = async () => {
    setExporting(true);
    setError(null);
    try {
      const cat = await analyze({ ...params, api: '' });
      if (cat.mode === 'catalog') await exportApiTracePdf(cat, app);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="layout">
      <aside className="sidebar">
        <ControlPanel params={params} meta={meta} loading={loading}
                      onChange={onChange} onTrace={onTrace} onCatalogAll={onCatalogAll} />
        {error && <div className="err">Error: {error}</div>}
        {selectedNode && <DetailPanel node={selectedNode} onClose={() => setSelectedId(null)} />}
        {data && <ResultPanels data={data} onBackToCatalog={onCatalogAll} onOpenApi={onOpenApi} />}
      </aside>
      <div className="main">
        {derived && <RouteGraph ref={graphRef} derived={derived} selectedId={selectedId} search={search} colorMode={colorMode} onSelect={setSelectedId} />}
        <div className="toolbar">
          <input placeholder="Search nodes…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="minibtn" onClick={() => graphRef.current?.fit()} title="Zoom out to the whole graph">Fit</button>
          <button className="minibtn" onClick={() => graphRef.current?.exportPng()}>PNG</button>
          <button className="minibtn" onClick={exportPdf} disabled={exporting || !data}
                  title="Export every impacted API for this release as a PDF report">
            {exporting ? 'PDF…' : 'PDF'}
          </button>
        </div>
        <div className="toolhint">drag to pan · scroll to zoom · click an API for its own flow</div>
        {loading && <div className="overlay"><Loader messages={SCAN_MESSAGES} note="bulk source analysis" /></div>}
        {!derived && !loading && !error && (
          <div className="graph-empty"><div className="impact-empty">
            <div className="impact-empty-title">Ready when you are</div>
            <div className="sub">Enter an API path above (or leave it blank to catalog all), then click <b>Trace</b>.</div>
          </div></div>
        )}
        <Legend />
      </div>
    </div>
  );
}
