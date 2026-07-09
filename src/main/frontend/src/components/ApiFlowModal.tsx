import { useEffect, useMemo, useState } from 'react';
import { analyze } from '../api';
import type { AnalyzeResponse } from '../types';
import { derive, opNamesOf } from '../graphModel';
import RouteGraph from './RouteGraph';
import Legend from './Legend';

/**
 * A modal that traces ONE API on demand and shows its full route graph — the same
 * React Flow graph as the Trace tab — so the Impact tab can stay a quick list with
 * the detailed flow one click away.
 */
export default function ApiFlowModal({ api, version, sourceDir, repo, branch, country, deps, app, colorMode, onClose }: {
  api: string;
  version?: string;
  sourceDir?: string;
  repo?: string;
  branch?: string;
  country?: string;
  deps?: string[];
  app?: string;
  colorMode: 'light' | 'dark';
  onClose: () => void;
}) {
  const depKey = (deps || []).join('|');
  const [data, setData] = useState<AnalyzeResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    analyze({ api, version, sourceDir, repo, branch, country, dep: deps, app })
      .then((d) => { if (alive) setData(d); })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, version, sourceDir, repo, branch, country, depKey, app]);

  useEffect(() => {
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onEsc);
    return () => window.removeEventListener('keydown', onEsc);
  }, [onClose]);

  const clientVersion = (data && data.requestedVersion) || '';
  const derived = useMemo(
    () => (data ? derive(data.graph || { nodes: [], edges: [] }, opNamesOf(data), clientVersion) : null),
    [data, clientVersion]
  );

  return (
    <div className="flow-modal-backdrop" onClick={onClose}>
      <div className="flow-modal" onClick={(e) => e.stopPropagation()}>
        <div className="flow-modal-head">
          <span className="flow-modal-title">Flow · <code>{api}</code>{version ? ` · R${version}` : ''}</span>
          <span className="row" style={{ gap: 6 }}>
            <input className="search-mini" style={{ width: 170 }} placeholder="Search nodes…"
                   value={search} onChange={(e) => setSearch(e.target.value)} />
            <button className="minibtn" onClick={onClose}>✕ Close</button>
          </span>
        </div>
        <div className="flow-modal-body">
          {loading && <div className="overlay"><div className="spinner" /><div className="spin-label">Tracing…</div></div>}
          {error && <div className="err" style={{ padding: 16 }}>Error: {error}</div>}
          {derived && <RouteGraph derived={derived} selectedId={selectedId} search={search} colorMode={colorMode} onSelect={setSelectedId} />}
          {derived && <Legend />}
        </div>
        <div className="flow-modal-foot">drag to pan · scroll to zoom · click a node to highlight its flow</div>
      </div>
    </div>
  );
}
