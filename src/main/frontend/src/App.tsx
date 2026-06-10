import { useEffect, useMemo, useRef, useState } from 'react';
import { analyze, fetchMeta } from './api';
import type { AnalyzeResponse, Meta, TraceParams } from './types';
import { derive, opNamesOf } from './graphModel';
import ControlPanel from './components/ControlPanel';
import ResultPanels from './components/ResultPanels';
import DetailPanel from './components/DetailPanel';
import RouteGraph, { type GraphHandle } from './components/RouteGraph';

const FIELDS: (keyof TraceParams)[] = ['api', 'version', 'transferType', 'country', 'sourceDir'];

function loadParams(): TraceParams {
  const p: TraceParams = { api: '/payment/v2/fund/submit', version: '9.4' };
  FIELDS.forEach((f) => {
    const v = localStorage.getItem('tracer.' + f);
    if (v !== null) p[f] = v;
  });
  return p;
}

const EMPTY_META: Meta = { countries: [], versions: [], transferTypes: [] };

export default function App() {
  const [params, setParams] = useState<TraceParams>(loadParams);
  const [meta, setMeta] = useState<Meta>(EMPTY_META);
  const [data, setData] = useState<AnalyzeResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const graphRef = useRef<GraphHandle>(null);

  const persist = (p: TraceParams) => FIELDS.forEach((f) => localStorage.setItem('tracer.' + f, p[f] || ''));

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

  // initial load
  useEffect(() => {
    (async () => {
      await loadMeta(params);
      await runTrace(params);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onChange = (patch: Partial<TraceParams>) => {
    const next = { ...params, ...patch };
    setParams(next);
    persist(next);
    if ('sourceDir' in patch || 'country' in patch) loadMeta(next);
  };

  const onTrace = () => runTrace(params);
  const onCatalogAll = () => { const next = { ...params, api: '' }; setParams(next); runTrace(next); };
  const onBackToCatalog = onCatalogAll;
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

  const copyJson = async () => { if (data) await navigator.clipboard.writeText(JSON.stringify(data, null, 2)); };

  return (
    <div className="app">
      <header>
        <h1>Camel Route Tracer</h1>
        <span>API → Resolved Route → Sub-routes → Backend APIs</span>
      </header>
      <div className="layout">
        <aside className="sidebar">
          <ControlPanel params={params} meta={meta} loading={loading}
                        onChange={onChange} onTrace={onTrace} onCatalogAll={onCatalogAll} />
          {error && <div className="err">Error: {error}</div>}
          {selectedNode && <DetailPanel node={selectedNode} onClose={() => setSelectedId(null)} />}
          {data && <ResultPanels data={data} onBackToCatalog={onBackToCatalog} onOpenApi={onOpenApi} />}
        </aside>
        <div className="main">
          {derived && <RouteGraph ref={graphRef} derived={derived} selectedId={selectedId} search={search} onSelect={setSelectedId} />}
          <div className="toolbar">
            <input placeholder="Search nodes…" value={search} onChange={(e) => setSearch(e.target.value)} />
            <button className="minibtn" onClick={() => graphRef.current?.fit()} title="Zoom out to the whole graph">Fit</button>
            <button className="minibtn" onClick={() => graphRef.current?.exportPng()}>PNG</button>
            <button className="minibtn" onClick={copyJson}>JSON</button>
          </div>
          <div className="toolhint">drag to pan · scroll to zoom · click an API for its own flow</div>
          {loading && <div className="overlay"><div className="spinner" /><div className="spin-label">Scanning source…</div></div>}
          <Legend />
        </div>
      </div>
    </div>
  );
}

function Legend() {
  return (
    <div className="legend">
      <div><span className="dot" style={{ background: '#2563eb' }} />API</div>
      <div><span className="dot" style={{ background: '#059669' }} />Versioned route</div>
      <div><span className="dot" style={{ background: '#d97706' }} />BASE route</div>
      <div><span className="dot" style={{ background: '#0891b2' }} />Shared / host route</div>
      <div><span className="dot" style={{ background: '#ea580c' }} />Backend API</div>
      <div><span className="dot ring" />Entry route</div>
      <div><span className="dot vring" />Matches client version</div>
      <div><span className="dot barrel" />Host call (CamelHttpUri)</div>
    </div>
  );
}
