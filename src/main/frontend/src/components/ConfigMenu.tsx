import { useEffect, useState } from 'react';
import { fetchSettings, saveSettings, type AppSettings } from '../api';

/**
 * The machine-wide Config menu (⚙). Lets the user store their Bitbucket and npm access tokens once
 * per machine — persisted under {@code ~/.traceguard} and read on every run, in both the standalone
 * launcher and IntelliJ. The Bitbucket token takes effect immediately (no restart); the npm token is
 * used by the build script for the private registry. Tokens are never shown back in full — only a
 * masked preview (last 4 chars) and whether each is set.
 */
export default function ConfigMenu({ onClose }: { onClose: () => void }) {
  const [state, setState] = useState<AppSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [bb, setBb] = useState('');
  const [npm, setNpm] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchSettings()
      .then((s) => { if (alive) setState(s); })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onEsc);
    return () => window.removeEventListener('keydown', onEsc);
  }, [onClose]);

  const apply = async (patch: { bitbucketToken?: string; npmToken?: string }) => {
    setBusy(true); setError(null); setSaved(false);
    try {
      const next = await saveSettings(patch);
      setState(next);
      setBb(''); setNpm('');
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const onSave = () => {
    const patch: { bitbucketToken?: string; npmToken?: string } = {};
    if (bb.trim()) patch.bitbucketToken = bb.trim();
    if (npm.trim()) patch.npmToken = npm.trim();
    if (Object.keys(patch).length === 0) { setError('Enter a new token to save, or use Remove to clear one.'); return; }
    apply(patch);
  };

  return (
    <div className="flow-modal-backdrop" onClick={onClose}>
      <div className="flow-modal config-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Config">
        <div className="flow-modal-head">
          <span className="flow-modal-title">⚙ Config <span className="muted">— saved on this machine</span></span>
          <button className="minibtn" onClick={onClose}>✕ Close</button>
        </div>
        <div className="flow-modal-body config-body">
          {error && <div className="cfg-error">{error}</div>}
          {saved && !error && <div className="cfg-ok">Saved ✓</div>}
          {!state ? (
            <div className="sub">Loading…</div>
          ) : (
            <>
              <div className="cfg-field">
                <label>Bitbucket access token <span className="muted">(HTTP token, Read scope — used to clone repos)</span></label>
                <div className="cfg-status">
                  {state.bitbucketTokenSet
                    ? <>Current: <code>{state.bitbucketTokenMasked}</code> <button className="linkbtn" disabled={busy} onClick={() => apply({ bitbucketToken: '' })}>Remove</button></>
                    : <span className="muted">Not set</span>}
                </div>
                <input type="password" autoComplete="off" placeholder="Enter new Bitbucket token" value={bb}
                       onChange={(e) => setBb(e.target.value)} />
                <div className="sub">Takes effect immediately — no restart needed.</div>
              </div>

              <div className="cfg-field">
                <label>npm access token <span className="muted">(private registry — used by the build script)</span></label>
                <div className="cfg-status">
                  {state.npmTokenSet
                    ? <>Current: <code>{state.npmTokenMasked}</code> <button className="linkbtn" disabled={busy} onClick={() => apply({ npmToken: '' })}>Remove</button></>
                    : <span className="muted">Not set</span>}
                </div>
                <input type="password" autoComplete="off" placeholder="Enter new npm token" value={npm}
                       onChange={(e) => setNpm(e.target.value)} />
              </div>

              <div className="cfg-actions">
                <button className="primary" disabled={busy} onClick={onSave}>{busy ? 'Saving…' : 'Save'}</button>
              </div>

              <div className="cfg-note">
                Stored in <code>{state.home}</code> on this machine (plaintext, user-only). Modules saved with
                <b> “Save as default”</b> live here too, so they’re remembered on every run — standalone or IntelliJ.
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
