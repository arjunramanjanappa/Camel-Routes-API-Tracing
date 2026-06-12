import { useEffect, useMemo, useState } from 'react';
import { fetchImpactIndex } from '../api';
import type { ApiImpact, ImpactIndex } from '../types';
import { backendPath, downloadText } from '../spl';
import Checklist from '../components/Checklist';
import SplunkPanel from '../components/SplunkPanel';
import LogAnalysisPanel from '../components/LogAnalysisPanel';

function get(k: string, d = '') { return localStorage.getItem('tracer.' + k) ?? d; }
function put(k: string, v: string) { localStorage.setItem('tracer.' + k, v); }

export default function ImpactView({ app }: { app?: string }) {
  const [sourceDir, setSourceDir] = useState('');   // not pre-filled; blank => server's tracer.source-dir
  const [country, setCountry] = useState(get('country'));
  const [version, setVersion] = useState(get('version'));
  const [idx, setIdx] = useState<ImpactIndex | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [changedRoutes, setChangedRoutes] = useState<Set<string>>(new Set());
  const [changedBackends, setChangedBackends] = useState<Set<string>>(new Set());
  const [selectedApis, setSelectedApis] = useState<Set<string>>(new Set());

  const load = async () => {
    put('country', country);   // sourceDir & version are not persisted — they start empty each load
    setLoading(true); setError(null);
    try {
      const data = await fetchImpactIndex(sourceDir, country, version);
      setIdx(data);
      setChangedRoutes(new Set());
      setChangedBackends(new Set());
      setSelectedApis(new Set());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

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

  const impacted = useMemo<{ api: ApiImpact; viaRoutes: string[]; viaBackends: string[] }[]>(() => {
    if (!idx) return [];
    const out: { api: ApiImpact; viaRoutes: string[]; viaBackends: string[] }[] = [];
    for (const a of idx.apis) {
      const vr = a.routes.filter((r) => changedRoutes.has(r));
      const vb = a.backends.filter((b) => changedBackends.has(b));
      if (vr.length || vb.length) out.push({ api: a, viaRoutes: vr, viaBackends: vb });
    }
    return out;
  }, [idx, changedRoutes, changedBackends]);

  const hasChange = changedRoutes.size > 0 || changedBackends.size > 0;
  const feApis = useMemo(() => impacted.map((i) => i.api.api).filter(Boolean), [impacted]);

  // Direct API selection drives the Splunk query and scopes the log analysis.
  const allApiPaths = useMemo(() => (idx ? [...new Set(idx.apis.map((a) => a.api).filter(Boolean))] : []), [idx]);
  const selectedApiList = useMemo(() => [...selectedApis], [selectedApis]);
  const selectedBackendList = useMemo(() => [...changedBackends], [changedBackends]);
  // The query covers the selected APIs' backends plus any directly-chosen backends.
  const splBackends = useMemo(() => {
    const set = new Set<string>(changedBackends);
    if (idx) idx.apis.forEach((a) => { if (selectedApis.has(a.api)) a.backends.forEach((b) => set.add(b)); });
    return [...set];
  }, [idx, selectedApis, changedBackends]);
  // Backend URL → traced service version(s), merged across the release's APIs.
  const backendVersionMap = useMemo(() => {
    const m: Record<string, string> = {};
    idx?.apis.forEach((a) => a.backendVersions && Object.entries(a.backendVersions).forEach(([url, ver]) => {
      if (!m[url]) m[url] = ver;
      else if (!m[url].split(' / ').includes(ver)) m[url] += ' / ' + ver;
    }));
    return m;
  }, [idx]);

  const exportCsv = () => {
    const rows = [['api', 'operation', 'resolvedRoute', 'impactedViaRoutes', 'impactedViaBackends', 'backends']];
    impacted.forEach((i) => rows.push([
      i.api.api, i.api.operation, i.api.resolvedRoute || '',
      i.viaRoutes.join('; '), i.viaBackends.join('; '), i.api.backends.join('; '),
    ]));
    downloadText('impacted-apis.csv', rows.map((r) => r.map((c) => `"${(c || '').replace(/"/g, '""')}"`).join(',')).join('\n'));
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
          <label>Client version</label>
          <input value={version} placeholder="9.4" onChange={(e) => setVersion(e.target.value)} />
        </div>
        <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'flex-end' }} disabled={loading} onClick={load}>
          {loading ? 'Loading…' : 'Load'}
        </button>
      </div>

      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {idx && (
        <>
        <div className="impact-body">
          <div className="impact-left">
            <div className="panel">
              <h2>APIs to analyse</h2>
              <div className="sub">Pick the APIs (single, several, or all) to build a Splunk query for and to scope the log analysis. The query covers each API plus the backends it calls.</div>
              <div className="kv"><b>{selectedApis.size}</b> of {allApiPaths.length} selected · version {idx.version || 'BASE'}{idx.country ? ', ' + idx.country : ''}</div>
            </div>
            <Checklist title="APIs" items={allApiPaths} selected={selectedApis}
                       onToggle={(i) => toggle(selectedApis, setSelectedApis, i)}
                       onSetMany={(items, on) => setMany(selectedApis, setSelectedApis, items, on)} />

            <div className="panel">
              <h2>What changed? <span className="muted">optional</span></h2>
              <div className="sub">Or select routes/backends that changed to find — and select — the impacted APIs.</div>
            </div>
            <Checklist title="Changed routes" items={idx.allRoutes} selected={changedRoutes}
                       onToggle={(i) => toggle(changedRoutes, setChangedRoutes, i)}
                       onSetMany={(items, on) => setMany(changedRoutes, setChangedRoutes, items, on)} />
            <Checklist title="Changed backends" items={idx.allBackends} selected={changedBackends}
                       onToggle={(i) => toggle(changedBackends, setChangedBackends, i)}
                       onSetMany={(items, on) => setMany(changedBackends, setChangedBackends, items, on)} />
          </div>

          <div className="impact-right">
            <div className="panel">
              <div className="row between">
                <h2 style={{ margin: 0 }}>Impacted APIs <span className="muted">{impacted.length} of {idx.apis.length}</span></h2>
                {impacted.length > 0 && (
                  <span className="row" style={{ gap: 6 }}>
                    <button className="minibtn" onClick={() => setMany(selectedApis, setSelectedApis, feApis, true)}>+ select for analysis</button>
                    <button className="minibtn" onClick={exportCsv}>Export CSV</button>
                  </span>
                )}
              </div>
              {!hasChange && <div className="sub">Select what changed on the left to see impacted APIs.</div>}
              {hasChange && impacted.length === 0 && <div className="sub">No APIs are impacted by the selected change.</div>}
              {impacted.length > 0 && (
                <table className="grid">
                  <thead><tr><th>API</th><th>Operation</th><th>Resolves to</th><th>Impacted via</th></tr></thead>
                  <tbody>
                    {impacted.map((i) => (
                      <tr key={i.api.api + i.api.operation}>
                        <td><code>{i.api.api}</code></td>
                        <td>{i.api.operation}</td>
                        <td><code>{i.api.resolvedRoute || '—'}</code></td>
                        <td>
                          {i.viaRoutes.map((r) => <span key={r} className="tag route">{r}</span>)}
                          {i.viaBackends.map((b) => <span key={b} className="tag backend">{backendPath(b)}</span>)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <SplunkPanel
              title="Splunk query — selected APIs"
              frontendApis={selectedApiList}
              backendApis={splBackends}
              backendVersions={backendVersionMap}
              hint="Run this in Splunk, export the result (CSV/JSON), then upload it under “Verify with logs” below."
            />
          </div>
        </div>

        <div style={{ padding: '0 18px 18px' }}>
          <LogAnalysisPanel version={version} country={country} sourceDir={sourceDir} app={app}
                            selectedApis={selectedApiList} selectedBackends={selectedBackendList} />
        </div>
        </>
      )}
    </div>
  );
}
