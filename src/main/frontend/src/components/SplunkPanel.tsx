import { useState } from 'react';
import { backendPath, buildSpl, downloadText } from '../spl';
import CopyBtn from './CopyBtn';

interface Props {
  title?: string;
  frontendApis: string[];
  backendApis: string[];
  hint?: string;
}

function pref(k: string, d: string) { return localStorage.getItem('tracer.' + k) ?? d; }

/** Configurable Splunk SPL generator for a set of front-end + back-end APIs. */
export default function SplunkPanel({ title = 'Splunk export', frontendApis, backendApis, hint }: Props) {
  const [index, setIndex] = useState(pref('splIndex', 'your_index'));
  const [feField, setFeField] = useState(pref('splFeField', 'uri'));
  const [beField, setBeField] = useState(pref('splBeField', 'uri'));

  const set = (k: string, v: string, fn: (s: string) => void) => { fn(v); localStorage.setItem('tracer.' + k, v); };

  const fe = [...new Set(frontendApis.filter(Boolean))];
  const be = [...new Set(backendApis.map(backendPath).filter(Boolean))];
  const feSpl = buildSpl(index, feField, fe);
  const beSpl = buildSpl(index, beField, be);

  return (
    <div className="panel">
      <h2>{title}</h2>
      <div className="spl-config">
        <div><label>Index</label><input value={index} onChange={(e) => set('splIndex', e.target.value, setIndex)} /></div>
        <div><label>Front-end API field</label><input value={feField} onChange={(e) => set('splFeField', e.target.value, setFeField)} /></div>
        <div><label>Backend API field</label><input value={beField} onChange={(e) => set('splBeField', e.target.value, setBeField)} /></div>
      </div>

      <div className="spl-block">
        <div className="row between"><b>Front-end APIs ({fe.length})</b>
          <span className="row" style={{ gap: 6 }}><CopyBtn text={feSpl} /><button className="minibtn" onClick={() => downloadText('frontend.spl', feSpl)}>.spl</button></span>
        </div>
        <pre>{feSpl || '—'}</pre>
      </div>
      <div className="spl-block">
        <div className="row between"><b>Backend APIs ({be.length})</b>
          <span className="row" style={{ gap: 6 }}><CopyBtn text={beSpl} /><button className="minibtn" onClick={() => downloadText('backend.spl', beSpl)}>.spl</button></span>
        </div>
        <pre>{beSpl || '—'}</pre>
      </div>
      {hint && <div className="sub">{hint}</div>}
    </div>
  );
}
