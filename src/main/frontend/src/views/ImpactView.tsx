import { useEffect, useMemo, useState } from 'react';
import { fetchImpactIndex } from '../api';
import type { ApiImpact, ImpactIndex } from '../types';
import Checklist from '../components/Checklist';

function get(k: string, d = '') { return localStorage.getItem('tracer.' + k) ?? d; }
function set(k: string, v: string) { localStorage.setItem('tracer.' + k, v); }

function backendPath(v: string) { return v.replace(/^\{\{[^}]+\}\}/, ''); }

function buildSpl(index: string, field: string, terms: string[]): string {
  if (terms.length === 0) return '';
  const ors = terms.map((t) => `${field}="${t}"`).join(' OR ');
  return `index=${index} (${ors})\n| stats count, latest(_time) as last_seen by ${field}\n| sort - count`;
}

function downloadText(name: string, text: string) {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}

function CopyBtn({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button className="minibtn" onClick={async () => { await navigator.clipboard.writeText(text); setDone(true); setTimeout(() => setDone(false), 1200); }}>
      {done ? 'Copied!' : label}
    </button>
  );
}

export default function ImpactView() {
  const [sourceDir, setSourceDir] = useState(get('sourceDir'));
  const [country, setCountry] = useState(get('country'));
  const [version, setVersion] = useState(get('version', '9.4'));
  const [idx, setIdx] = useState<ImpactIndex | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [changedRoutes, setChangedRoutes] = useState<Set<string>>(new Set());
  const [changedBackends, setChangedBackends] = useState<Set<string>>(new Set());

  const [splIndex, setSplIndex] = useState(get('splIndex', 'your_index'));
  const [feField, setFeField] = useState(get('splFeField', 'uri'));
  const [beField, setBeField] = useState(get('splBeField', 'uri'));

  const load = async () => {
    set('sourceDir', sourceDir); set('country', country); set('version', version);
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

  const toggle = (set: Set<string>, setFn: (s: Set<string>) => void, item: string) => {
    const next = new Set(set);
    next.has(item) ? next.delete(item) : next.add(item);
    setFn(next);
  };
  const setMany = (set: Set<string>, setFn: (s: Set<string>) => void, items: string[], on: boolean) => {
    const next = new Set(set);
    items.forEach((i) => (on ? next.add(i) : next.delete(i)));
    setFn(next);
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
  const feApis = useMemo(() => [...new Set(impacted.map((i) => i.api.api).filter(Boolean))], [impacted]);
  const beApis = useMemo(() => [...new Set(impacted.flatMap((i) => i.api.backends).map(backendPath))], [impacted]);

  const feSpl = buildSpl(splIndex, feField, feApis);
  const beSpl = buildSpl(splIndex, beField, beApis);

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
        <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'end' }} disabled={loading} onClick={load}>
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
              <div className="panel">
                <h2>Splunk queries</h2>
                <div className="spl-config">
                  <div><label>Index</label><input value={splIndex} onChange={(e) => { setSplIndex(e.target.value); set('splIndex', e.target.value); }} /></div>
                  <div><label>Front-end API field</label><input value={feField} onChange={(e) => { setFeField(e.target.value); set('splFeField', e.target.value); }} /></div>
                  <div><label>Backend API field</label><input value={beField} onChange={(e) => { setBeField(e.target.value); set('splBeField', e.target.value); }} /></div>
                </div>

                <div className="spl-block">
                  <div className="row between"><b>Front-end APIs ({feApis.length})</b>
                    <span className="row" style={{ gap: 6 }}><CopyBtn text={feSpl} /><button className="minibtn" onClick={() => downloadText('frontend.spl', feSpl)}>.spl</button></span>
                  </div>
                  <pre>{feSpl}</pre>
                </div>
                <div className="spl-block">
                  <div className="row between"><b>Backend APIs ({beApis.length})</b>
                    <span className="row" style={{ gap: 6 }}><CopyBtn text={beSpl} /><button className="minibtn" onClick={() => downloadText('backend.spl', beSpl)}>.spl</button></span>
                  </div>
                  <pre>{beSpl}</pre>
                </div>
                <div className="sub">Run these in Splunk and export the report — the next step will upload it and tell you which impacted APIs were actually called.</div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
