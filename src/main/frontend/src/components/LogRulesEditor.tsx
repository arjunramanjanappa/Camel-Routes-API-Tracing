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

  // NOTE: keep the raw typed text — do NOT trim or filter per keystroke. Trimming re-inserts the ", " separator
  // that join() shows, so you can't backspace past it; filtering drops the comma the moment you type it. The
  // value is round-tripped as-is (split(',') <-> join(',')); empties/whitespace are trimmed on SAVE.
  const setCodeFields = (csv: string) =>
    setCur({ ...cur, codeFields: csv.split(',') });
  const setRule = (i: number, patch: Partial<LogRule>) =>
    setCur({ ...cur, rules: cur.rules.map((r, j) => (j === i ? { ...r, ...patch } : r)) });
  const addRule = () =>
    setCur({ ...cur, rules: [...cur.rules, { match: '', codeField: '', successCodes: [], skip: false, svcVersion: '' }] });
  const removeRule = (i: number) => setCur({ ...cur, rules: cur.rules.filter((_, j) => j !== i) });

  const save = async () => {
    setBusy(true); setError(null); setSaved(false);
    try {
      // Keep a rule if it says anything — a host match, a field name, success codes, or skip. A fully-blank
      // row (half-typed) is dropped. A rule with NO match but a field/codes/skip is a GLOBAL rule (all hosts).
      const clean: LogRulesMap = {};
      for (const [k, v] of Object.entries(map)) {
        // Trim the typing-time empties now (a trailing comma leaves an ""), then keep meaningful rules.
        const rules = v.rules
          .map((r) => ({ ...r, codeField: r.codeField.trim(), successCodes: r.successCodes.map((s) => s.trim()).filter(Boolean), svcVersion: (r.svcVersion || '').trim() }))
          .filter((r) => r.match.trim() || r.codeField || r.successCodes.length > 0 || r.skip || r.svcVersion);
        const codeFields = v.codeFields.map((s) => s.trim()).filter(Boolean);
        if (codeFields.length || rules.length) clean[k] = { codeFields, rules };
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
      <div className="sub" style={{ marginTop: 0 }}>
        <b>Match</b> — backend hosturl glob (empty = all hosts). <b>Field name</b> — JSON key holding the code
        (e.g. <code>resultCode</code>, <code>errorcode</code>). <b>Success codes</b> — comma-separated, any one
        passes. <b>Svc version</b> — expected service version (exact match); set it only when the version is set
        in Java so the route scan can't derive it. <b>Skip</b> — exclude from the verdict. Save, then
        <b>↻ Re-run with current rules</b> on Release Test.
      </div>

      <div className="seg" style={{ marginTop: 6 }}>
        {APPS.map((a) => (
          <button key={a} className={app === a ? 'on' : ''} onClick={() => setApp(a)}>{a}</button>
        ))}
      </div>

      <label style={{ marginTop: 8 }}>Fallback field name(s) <span className="muted">(comma-separated; responseCode is always tried)</span></label>
      <input type="text" spellCheck={false} placeholder="resultCode, statusCode"
             value={cur.codeFields.join(',')} onChange={(e) => setCodeFields(e.target.value)} />

      <label style={{ marginTop: 8 }}>Rules <span className="muted">(Match = the backend hosturl glob — <code>*</code> or empty = every host; or e.g. <code>*/limit/*</code>)</span></label>
      <table className="logrules-tbl">
        <thead>
          <tr><th>Match (hosturl glob · empty = global)</th><th>Field name</th><th>Success codes</th><th title="Expected service version (exact match). Set only when the version is defined in Java and the scan can't read it.">Svc version</th><th>Skip</th><th /></tr>
        </thead>
        <tbody>
          {cur.rules.length === 0 && <tr><td colSpan={6} className="muted">No rules — add one below.</td></tr>}
          {cur.rules.map((r, i) => (
            <tr key={i}>
              <td><input value={r.match} spellCheck={false} placeholder="*/host/xyz  (empty = all hosts)" onChange={(e) => setRule(i, { match: e.target.value })} /></td>
              <td><input value={r.codeField} spellCheck={false} placeholder="resultCode" onChange={(e) => setRule(i, { codeField: e.target.value })} /></td>
              <td><input value={r.successCodes.join(',')} spellCheck={false} placeholder="000000,200"
                         disabled={r.skip}
                         onChange={(e) => setRule(i, { successCodes: e.target.value.split(',') })} /></td>
              <td><input value={r.svcVersion || ''} spellCheck={false} placeholder="2.3" style={{ width: 64 }}
                         disabled={r.skip}
                         title="Expected service version — exact match. Leave blank unless the version is set in Java."
                         onChange={(e) => setRule(i, { svcVersion: e.target.value })} /></td>
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
