import { useEffect, useMemo, useState } from 'react';
import { fetchImpactIndex } from '../api';
import type { ApiImpact, ImpactIndex } from '../types';
import { backendPath, downloadText } from '../spl';
import Checklist from '../components/Checklist';
import SplunkPanel from '../components/SplunkPanel';

function get(k: string, d = '') { return localStorage.getItem('tracer.' + k) ?? d; }
function put(k: string, v: string) { localStorage.setItem('tracer.' + k, v); }

export default function ImpactView() {
  const [sourceDir, setSourceDir] = useState(get('sourceDir'));
  const [country, setCountry] = useState(get('country'));
  const [version, setVersion] = useState(get('version'));
  const [idx, setIdx] = useState<ImpactIndex | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [changedRoutes, setChangedRoutes] = useState<Set<string>>(new Set());
  const [changedBackends, setChangedBackends] = useState<Set<string>>(new Set());

  const load = async () => {
    put('sourceDir', sourceDir); put('country', country);   // version is not persisted (shared with Trace)
    setLoading(true); setError(null);
    try {
      const data = await fetchImpactIndex(sourceDir, country, version);
      setIdx(data);
      setChangedRoutes(new Set());
      setChangedBackends(new Set());
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
  const beApis = useMemo(() => impacted.flatMap((i) => i.api.backends), [impacted]);

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
        <div className="impact-body">
          <div className="impact-left">
            <div className="panel">
              <h2>What changed?</h2>
              <div className="sub">Select the routes and/or backend APIs that changed. APIs touching them are impacted.</div>
              <div className="kv"><b>{idx.apis.length}</b> APIs · <b>{idx.allRoutes.length}</b> routes · <b>{idx.allBackends.length}</b> backends (version {idx.version || 'BASE'}{idx.country ? ', ' + idx.country : ''})</div>
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
                {impacted.length > 0 && <button className="minibtn" onClick={exportCsv}>Export CSV</button>}
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

            {impacted.length > 0 && (
              <SplunkPanel
                title="Splunk queries — impacted APIs"
                frontendApis={feApis}
                backendApis={beApis}
                hint="Run these in Splunk and export the report — the upload + correlation step will tell you which impacted APIs were actually called."
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
