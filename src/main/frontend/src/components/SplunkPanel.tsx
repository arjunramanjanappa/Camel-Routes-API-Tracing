import { useState } from 'react';
import { backendPath, buildEventsSpl, downloadText, TIME_PRESETS } from '../spl';
import CopyBtn from './CopyBtn';

interface Props {
  title?: string;
  frontendApis: string[];
  backendApis: string[];
  hint?: string;
}

function pref(k: string, d: string) { return localStorage.getItem('tracer.' + k) ?? d; }

/**
 * Builds one Splunk search for the selected APIs (front-end paths + their
 * backends) that returns raw events as _time, _raw — the exact shape the log
 * analyser reads back, so the exported report drives the correlation.
 */
export default function SplunkPanel({ title = 'Splunk query', frontendApis, backendApis, hint }: Props) {
  const [index, setIndex] = useState(pref('splIndex', 'your_index'));
  const [feField, setFeField] = useState(pref('splFeField', 'uri'));
  const [beField, setBeField] = useState(pref('splBeField', 'uri'));
  const [earliest, setEarliest] = useState(pref('splEarliest', '-24h'));

  const set = (k: string, v: string, fn: (s: string) => void) => { fn(v); localStorage.setItem('tracer.' + k, v); };

  const fe = [...new Set(frontendApis.filter(Boolean))];
  const be = [...new Set(backendApis.map(backendPath).filter(Boolean))];
  const spl = buildEventsSpl(index, feField, fe, beField, be, earliest);
  const rangeLabel = TIME_PRESETS.find((p) => p.earliest === earliest)?.label ?? earliest;

  return (
    <div className="panel">
      <h2>{title}</h2>
      <div className="spl-config">
        <div><label>Index</label><input value={index} onChange={(e) => set('splIndex', e.target.value, setIndex)} /></div>
        <div><label>Front-end API field</label><input value={feField} onChange={(e) => set('splFeField', e.target.value, setFeField)} /></div>
        <div><label>Backend API field</label><input value={beField} onChange={(e) => set('splBeField', e.target.value, setBeField)} /></div>
      </div>

      <label>Time range <span className="muted">(query window — max 30 days)</span></label>
      <div className="timerange">
        {TIME_PRESETS.map((p) => (
          <button key={p.earliest} className={'tpill' + (earliest === p.earliest ? ' on' : '')}
                  onClick={() => set('splEarliest', p.earliest, setEarliest)}>{p.label}</button>
        ))}
      </div>

      <div className="sub" style={{ marginTop: 8 }}>
        Searches the last <b>{rangeLabel}</b> and returns raw events (<code>_time, _raw</code>) for <b>{fe.length}</b> front-end
        + <b>{be.length}</b> backend path(s). Export the result as CSV (or JSON) and upload it under <b>Verify with logs</b>.
      </div>

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
