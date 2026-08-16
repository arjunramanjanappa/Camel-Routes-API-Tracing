import { useEffect, useRef, useState } from 'react';
import { Check } from 'lucide-react';
import { fetchCapabilityConfig, saveCapabilityConfig, type CapabilityConfigStatus } from '../api';

/**
 * VAL report config (⚙ Config): attach the two Excel reports once — the Interface Spec (API → capability IDs)
 * and the Capability Matrix (ID → how to test). Release Test / Release Scope then map each impacted API to its
 * business capabilities (an export + capability columns on the leadership Summary PDFs). Stored on this machine
 * like the other config; re-upload when the VAL updates.
 */
export default function CapabilityConfig() {
  const [status, setStatus] = useState<CapabilityConfigStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'interfaceSpec' | 'capabilityMatrix' | null>(null);
  const specRef = useRef<HTMLInputElement>(null);
  const matrixRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let alive = true;
    fetchCapabilityConfig().then((s) => { if (alive) setStatus(s); }).catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, []);

  const upload = async (which: 'interfaceSpec' | 'capabilityMatrix', file: File | null) => {
    if (!file) return;
    setBusy(which); setError(null);
    try {
      setStatus(await saveCapabilityConfig(which === 'interfaceSpec' ? file : null, which === 'capabilityMatrix' ? file : null));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const picker = (which: 'interfaceSpec' | 'capabilityMatrix', label: string, hint: string, ref: React.RefObject<HTMLInputElement>, set: boolean) => (
    <div className="cfg-field">
      <label>{label} <span className="muted">{hint}</span></label>
      <div className="cfg-status">
        {set ? <span className="cfg-ok" style={{ padding: 0 }}>Attached <Check size={13} aria-hidden="true" /></span> : <span className="muted">Not attached</span>}
        {' '}
        <button className="linkbtn" disabled={busy !== null} onClick={() => ref.current?.click()}>{set ? 'Replace…' : 'Attach…'}</button>
        {busy === which && <span className="muted"> uploading…</span>}
      </div>
      <input ref={ref} type="file" accept=".xlsx" style={{ display: 'none' }}
             onChange={(e) => { upload(which, e.target.files?.[0] ?? null); e.target.value = ''; }} />
    </div>
  );

  return (
    <div className="cfg-field">
      <label>VAL capability reports <span className="muted">(map impacted APIs → how to test — used by Release Test &amp; Scope)</span></label>
      {error && <div className="cfg-error">{error}</div>}
      {picker('interfaceSpec', 'Interface Spec', '(.xlsx — Col G Interface, Col M Countries, Col Q Linked Capabilities)', specRef, !!status?.interfaceSpec)}
      {picker('capabilityMatrix', 'Capability Matrix', '(.xlsx — Col A ID, Cols A–N L1–L5 / how to test)', matrixRef, !!status?.capabilityMatrix)}
      <div className="sub">Attach both. TraceGuard joins an impacted API to the Interface Spec (Col G ends-with, Col M country), unions its Col Q IDs, and looks them up in the Capability Matrix.</div>
    </div>
  );
}
