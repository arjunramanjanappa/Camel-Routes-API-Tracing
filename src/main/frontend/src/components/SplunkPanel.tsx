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
  /** Client release version — ANDed into the query so only that release's lines are fetched. */
  version?: string;
  /** Auto-detected SPL-Secure flavour — uses the SPLAppLog/SPLWSAppLog/SPLHostMessage query shape. */
  secure?: boolean;
}

function pref(k: string, d: string) { return localStorage.getItem('tracer.' + k) ?? d; }

/**
 * Builds one Splunk search for the selected APIs (front-end paths + their
 * backends) that returns raw events as a single _raw column — the exact shape the
 * log analyser reads back, so the exported report drives the correlation. Each
 * backend is searched together with its traced service version.
 */
export default function SplunkPanel({ title = 'Splunk query', frontendApis, backendApis, backendVersions = {}, backendHosturls = {}, hint, app, version, secure = false }: Props) {
  const application = app && app.trim() ? app.trim() : 'Mighty';
  // SPL-Secure front-end lines use two loggers; the backend stays <app>HostMessage.
  const feMarker = secure ? 'SPLAppLog / SPLWSAppLog' : application + 'Message';   // front-end log lines
  const beMarker = secure ? 'SPLHostMessage' : application + 'HostMessage';        // backend log lines
  const [mode, setMode] = useState<'scoped' | 'all'>(pref('splMode', 'scoped') === 'all' ? 'all' : 'scoped');
  const [index, setIndex] = useState(pref('splIndex', 'your_index'));
  const [earliest, setEarliest] = useState(pref('splEarliest', '-24h'));

  const set = (k: string, v: string, fn: (s: string) => void) => { fn(v); localStorage.setItem('tracer.' + k, v); };

  const fe = [...new Set(frontendApis.filter(Boolean))];
  // Search the backend by its hosturl (what the host actually logs) when known, else the api path.
  const bePathOf = (url: string) => backendPath(backendHosturls[url] || url);
  const be = [...new Set(backendApis.map(bePathOf).filter(Boolean))];
  // Map the searchable backend path → its traced service version (keyed originally by URL).
  const beVer: Record<string, string> = {};
  backendApis.forEach((url) => { const p = bePathOf(url); if (p && backendVersions[url]) beVer[p] = backendVersions[url]; });
  const clientVersion = version && version.trim() ? version.trim() : '';
  // For secure, feMarker/beMarker are display-only — buildEventsSpl uses the fixed secure loggers.
  // Always search the raw event (empty FE/BE field names) so the export is the _raw format the analyser
  // reads — the user only chooses index / time / which APIs; the query resolves to the analysis format.
  const spl = buildEventsSpl(index, '', fe, '', be, earliest, beVer, 'serviceVersionNumber', false, feMarker, beMarker, mode, clientVersion, secure);
  const rangeLabel = TIME_PRESETS.find((p) => p.earliest === earliest)?.label ?? earliest;
  const verLabel = clientVersion && clientVersion.toUpperCase() !== 'BASE' ? clientVersion : '';

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
        <div><label>Index <span className="muted">(your Splunk index)</span></label><input value={index} onChange={(e) => set('splIndex', e.target.value, setIndex)} /></div>
      </div>

      <label>Time range <span className="muted">(query window — max 30 days)</span></label>
      <div className="timerange">
        {TIME_PRESETS.map((p) => (
          <button key={p.earliest} className={'tpill' + (earliest === p.earliest ? ' on' : '')}
                  onClick={() => set('splEarliest', p.earliest, setEarliest)}>{p.label}</button>
        ))}
      </div>

      <div className="sub" style={{ marginTop: 8 }}>
        {mode === 'all'
          ? <>Returns the last <b>{rangeLabel}</b> of <b>all</b> <code>{feMarker}</code> + <code>{beMarker}</code> events{verLabel ? <> on release <b>{verLabel}</b></> : null} (<code>_raw</code>) — same as a raw output log.</>
          : <>Searches the last <b>{rangeLabel}</b> and returns raw events (<code>_raw</code>) for <b>{fe.length}</b> front-end
            + <b>{be.length}</b> backend path(s). Front-end paths are scoped to the <code>{feMarker}</code> log lines and backends
            (by hosturl) to <code>{beMarker}</code>{verLabel ? <>, and only release <b>{verLabel}</b> lines</> : null}. Service versions are
            validated by the analyser after upload.</>}
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
