import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { fetchIgnoredClasses, saveIgnoredClasses } from '../api';

/**
 * ⚙ Config → Ignored classes: shared Java classes Release Impact must NOT treat as a code change (a common
 * logger / audit / utility class used by many routes would otherwise mark every API as impacted). One pattern per
 * line; saved machine-wide to ~/.traceguard/ignored-classes.json. Skipped changes are still listed on the report.
 */
export default function IgnoredClassesConfig() {
  const [text, setText] = useState('');
  const [initial, setInitial] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchIgnoredClasses()
      .then((p) => { if (alive) { const t = p.join('\n'); setText(t); setInitial(t); } })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, []);

  const onSave = async () => {
    setBusy(true); setError(null); setSaved(false);
    try {
      const next = await saveIgnoredClasses(text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#')));
      const t = next.join('\n'); setText(t); setInitial(t); setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const dirty = text !== initial;
  return (
    <div className="cfg-field">
      <label>Ignored classes <span className="muted">(Release Impact — shared classes whose change must not mark APIs as impacted)</span></label>
      <textarea className="cfg-textarea" rows={5} spellCheck={false} value={text} onChange={(e) => setText(e.target.value)}
                placeholder={'commonAuditProcessor\n*Logger*\n**/common/*.java'} />
      <div className="sub">
        One pattern per line, case-insensitive: a <b>bean name</b> (<code>commonAuditProcessor</code>), a <b>class name</b> with wildcards
        (<code>*Logger*</code>) or a <b>path</b> (<code>**/common/*.java</code>). A change to a matching class never flags an API; the report
        still lists what was skipped. Applies on the next Compare.
      </div>
      <div className="cfg-actions">
        <button className="primary" disabled={busy || !dirty} onClick={onSave}>{busy ? 'Saving…' : 'Save ignored classes'}</button>
        {saved && !error && <span className="cfg-ok">Saved <Check size={13} aria-hidden="true" /></span>}
        {error && <span className="cfg-error">{error}</span>}
      </div>
    </div>
  );
}
