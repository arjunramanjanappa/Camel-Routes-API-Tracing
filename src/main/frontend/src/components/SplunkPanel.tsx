import { useState } from 'react';
import { backendPath, buildEventsSpl, downloadText, TIME_PRESETS } from '../spl';
import CopyBtn from './CopyBtn';

interface Props {
  title?: string;
  frontendApis: string[];
  backendApis: string[];
  /** Backend URL → traced service version(s), e.g. "2.2" or "2.2 / 3.3". */
  backendVersions?: Record<string, string>;
  /** Backend api → its hosturl (the path the host actually logs) — searched instead of the api. */
  backendHosturls?: Record<string, string>;
  hint?: string;
  /** Selected application — its markers (<app>Message / <app>HostMessage) scope the query. */
  app?: string;
}

function pref(k: string, d: string) { return localStorage.getItem('tracer.' + k) ?? d; }

/**
 * Builds one Splunk search for the selected APIs (front-end paths + their
 * backends) that returns raw events as a single _raw column — the exact shape the
 * log analyser reads back, so the exported report drives the correlation. Each
 * backend is searched together with its traced service version.
 */
export default function SplunkPanel({ title = 'Splunk query', frontendApis, backendApis, backendVersions = {}, backendHosturls = {}, hint, app }: Props) {
  const application = app && app.trim() ? app.trim() : 'Mighty';
  const feMarker = application + 'Message';        // front-end log lines
  const beMarker = application + 'HostMessage';    // backend log lines
  const [mode, setMode] = useState<'scoped' | 'all'>(pref('splMode', 'scoped') === 'all' ? 'all' : 'scoped');
  const [index, setIndex] = useState(pref('splIndex', 'your_index'));
  // Blank = search the raw event (the default, since the path is plain text in the log).
  // Set a field name only if your Splunk has extracted fields (e.g. uri).
  const [feField, setFeField] = useState(pref('splFeField', ''));
  const [beField, setBeField] = useState(pref('splBeField', ''));
  const [svcField, setSvcField] = useState(pref('splSvcField', 'serviceVersionNumber'));
  const [earliest, setEarliest] = useState(pref('splEarliest', '-24h'));
  const [wildcard, setWildcard] = useState(pref('splWildcard', '1') === '1');

  const set = (k: string, v: string, fn: (s: string) => void) => { fn(v); localStorage.setItem('tracer.' + k, v); };

  const fe = [...new Set(frontendApis.filter(Boolean))];
  // Search the backend by its hosturl (what the host actually logs) when known, else the api path.
  const bePathOf = (url: string) => backendPath(backendHosturls[url] || url);
  const be = [...new Set(backendApis.map(bePathOf).filter(Boolean))];
  // Map the searchable backend path → its traced service version (keyed originally by URL).
  const beVer: Record<string, string> = {};
  backendApis.forEach((url) => { const p = bePathOf(url); if (p && backendVersions[url]) beVer[p] = backendVersions[url]; });
  const versioned = be.filter((p) => beVer[p]).length;
  const spl = buildEventsSpl(index, feField, fe, beField, be, earliest, beVer, svcField, wildcard, feMarker, beMarker, mode);
  const rangeLabel = TIME_PRESETS.find((p) => p.earliest === earliest)?.label ?? earliest;

  return (
    <div className="panel">
      <div className="row between">
        <h2 style={{ margin: 0 }}>{title}</h2>
        <div className="seg">
          <button className={mode === 'scoped' ? 'on' : ''} onClick={() => set('splMode', 'scoped', (v) => setMode(v as 'scoped'))}>Scoped</button>
          <button className={mode === 'all' ? 'on' : ''} onClick={() => set('splMode', 'all', (v) => setMode(v as 'all'))}>All log lines</button>
        </div>
      </div>
      <div className="sub" style={{ marginTop: 4 }}>
        {mode === 'scoped'
          ? <>Only the selected API(s): their front-end paths + backend (hosturl) paths.</>
          : <>Every <code>{feMarker}</code> + <code>{beMarker}</code> line in the window — the analyser scopes to your selection on upload (can&rsquo;t miss a line).</>}
      </div>

      <div className="spl-config">
        <div><label>Index</label><input value={index} onChange={(e) => set('splIndex', e.target.value, setIndex)} /></div>
        {mode === 'scoped' && <>
          <div><label>Front-end field <span className="muted">(blank = raw)</span></label><input value={feField} placeholder="search _raw" onChange={(e) => set('splFeField', e.target.value, setFeField)} /></div>
          <div><label>Backend field <span className="muted">(blank = raw)</span></label><input value={beField} placeholder="search _raw" onChange={(e) => set('splBeField', e.target.value, setBeField)} /></div>
          <div><label>Service version field</label><input value={svcField} onChange={(e) => set('splSvcField', e.target.value, setSvcField)} /></div>
        </>}
      </div>

      <label>Time range <span className="muted">(query window — max 30 days)</span></label>
      <div className="timerange">
        {TIME_PRESETS.map((p) => (
          <button key={p.earliest} className={'tpill' + (earliest === p.earliest ? ' on' : '')}
                  onClick={() => set('splEarliest', p.earliest, setEarliest)}>{p.label}</button>
        ))}
      </div>

      {mode === 'scoped' && (feField.trim() || beField.trim()) && (
        <label className="check" style={{ marginTop: 8 }}>
          <input type="checkbox" checked={wildcard}
                 onChange={(e) => { setWildcard(e.target.checked); localStorage.setItem('tracer.splWildcard', e.target.checked ? '1' : '0'); }} />
          Match path suffix (<code>field="*path"</code>) — handles a logged context prefix like <code>/mty-banking-01/</code>
        </label>
      )}

      <div className="sub" style={{ marginTop: 8 }}>
        {mode === 'all'
          ? <>Returns the last <b>{rangeLabel}</b> of <b>all</b> <code>{feMarker}</code> + <code>{beMarker}</code> events (<code>_raw</code>) — same as a raw output log.</>
          : <>Searches the last <b>{rangeLabel}</b> and returns raw events (<code>_raw</code>) for <b>{fe.length}</b> front-end
            + <b>{be.length}</b> backend path(s){versioned > 0 ? <> — each backend is filtered to its traced <b>service version</b> ({versioned} of {be.length} known)</> : null}.
            Front-end paths are scoped to the <code>{feMarker}</code> log lines and backends (by hosturl) to <code>{beMarker}</code>.</>}
        {' '}Export the result as CSV (or JSON) and upload it under <b>Verify with logs</b>.
      </div>

      {mode === 'scoped' && be.length > 0 && Object.keys(beVer).length > 0 && (
        <div className="kv" style={{ marginTop: 4 }}>
          {be.map((p) => (
            <span key={p} className="tag backend" style={{ marginRight: 6 }}>
              {p}{beVer[p] ? ' · svc ' + beVer[p] : ''}
            </span>
          ))}
        </div>
      )}

      <div className="spl-block">
        <div className="row between">
          <b>Splunk search → export → upload</b>
          <span className="row" style={{ gap: 6 }}>
            <CopyBtn text={spl} />
            <button className="minibtn" disabled={!spl} onClick={() => downloadText('analysis.spl', spl)}>.spl</button>
          </span>
        </div>
        <pre>{spl || '— select one or more APIs to build the query —'}</pre>
      </div>

      {hint && <div className="sub">{hint}</div>}
    </div>
  );
}
