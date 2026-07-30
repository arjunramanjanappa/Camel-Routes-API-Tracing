import { useEffect, useMemo, useState } from 'react';
import { fetchLogRules, saveLogRules, type AppLogRules, type LogRule, type LogRulesMap } from '../api';

const APPS = ['Mighty', 'SPL', 'SPL-Secure'];
const EMPTY: AppLogRules = { codeFields: [], rules: [] };

/**
 * Editor for the per-app host response-code rules (log-rules.json). For a matching backend hosturl it lets
 * log analysis read the code from a different JSON key (e.g. resultCode), treat a custom value as success, or
 * skip the backend from the verdict (→ Skipped). Front-end lines are unaffected.
 */
export default function LogRulesEditor() {
  const [map, setMap] = useState<LogRulesMap>({});
  const [app, setApp] = useState(APPS[0]);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchLogRules().then(setMap).catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const cur = useMemo<AppLogRules>(() => map[app] ?? EMPTY, [map, app]);
  const setCur = (next: AppLogRules) => { setMap((m) => ({ ...m, [app]: next })); setSaved(false); };

  const setCodeFields = (csv: string) =>
    setCur({ ...cur, codeFields: csv.split(',').map((s) => s.trim()).filter(Boolean) });
  const setRule = (i: number, patch: Partial<LogRule>) =>
    setCur({ ...cur, rules: cur.rules.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  const addRule = () =>
    setCur({ ...cur, rules: [...cur.rules, { match: '', codeField: '', successCodes: [], skip: false }] });
  const removeRule = (i: number) => setCur({ ...cur, rules: cur.rules.filter((_, j) => j !== i) });

  const save = async () => {
    setBusy(true); setError(null); setSaved(false);
    try {
      // Drop empty-match rules so a half-typed row isn't persisted.
      const clean: LogRulesMap = {};
      for (const [k, v] of Object.entries(map)) {
        const rules = v.rules.filter((r) => r.match.trim());
        if (v.codeFields.length || rules.length) clean[k] = { codeFields: v.codeFields, rules };
      }
      setMap(await saveLogRules(clean));
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="cfg-field">
      <label>Log analysis — host response-code rules <span className="muted">(backend/host lines only)</span></label>
      <div className="sub" style={{ marginTop: 0 }}>
        For a matching backend <b>hosturl</b>, read the code from a different key (e.g. <code>resultCode</code>),
        treat a custom value as success, or <b>skip</b> it from the verdict (shown as <b>Skipped</b>). Front-end
        (controller) lines are unaffected.
      </div>

      <div className="seg" style={{ marginTop: 6 }}>
        {APPS.map((a) => (
          <button key={a} className={app === a ? 'on' : ''} onClick={() => setApp(a)}>{a}</button>
        ))}
      </div>

      <label style={{ marginTop: 8 }}>Fallback code keys <span className="muted">(comma-separated; responseCode is always tried)</span></label>
      <input type="text" spellCheck={false} placeholder="resultCode, statusCode"
             value={cur.codeFields.join(', ')} onChange={(e) => setCodeFields(e.target.value)} />

      <label style={{ marginTop: 8 }}>Rules <span className="muted">(match a hosturl glob, e.g. */host/limit/*)</span></label>
      <table className="logrules-tbl">
        <thead>
          <tr><th>Match (hosturl glob)</th><th>Code field</th><th>Success codes</th><th>Skip</th><th /></tr>
        </thead>
        <tbody>
          {cur.rules.length === 0 && <tr><td colSpan={5} className="muted">No rules — add one below.</td></tr>}
          {cur.rules.map((r, i) => (
            <tr key={i}>
              <td><input value={r.match} spellCheck={false} placeholder="*/host/xyz" onChange={(e) => setRule(i, { match: e.target.value })} /></td>
              <td><input value={r.codeField} spellCheck={false} placeholder="resultCode" onChange={(e) => setRule(i, { codeField: e.target.value })} /></td>
              <td><input value={r.successCodes.join(', ')} spellCheck={false} placeholder="000000, 200"
                         disabled={r.skip}
                         onChange={(e) => setRule(i, { successCodes: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} /></td>
              <td style={{ textAlign: 'center' }}><input type="checkbox" checked={r.skip} onChange={(e) => setRule(i, { skip: e.target.checked })} /></td>
              <td><button className="linkbtn" onClick={() => removeRule(i)} title="Remove this rule">✕</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <button className="linkbtn" onClick={addRule}>＋ Add rule</button>

      <div className="cfg-actions" style={{ marginTop: 8 }}>
        <button className="primary" disabled={busy} onClick={save}>{busy ? 'Saving…' : 'Save rules'}</button>
        {saved && !error && <span className="cfg-ok" style={{ marginLeft: 8 }}>Saved ✓</span>}
        {error && <span className="cfg-error" style={{ marginLeft: 8 }}>{error}</span>}
      </div>
    </div>
  );
}
