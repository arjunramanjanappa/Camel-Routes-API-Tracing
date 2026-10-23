import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { fetchFeatureLabels, saveFeatureLabels } from '../api';
import { labelLines, parseLabelLines } from '../labels';

/**
 * ⚙ Config → Business-area labels: the names the Release Impact reports use for API areas and sub-features
 * (e.g. payment → "Funds Transfer & Payments"). One `path-key = Label` per line; saved machine-wide to
 * ~/.traceguard/feature-labels.json. Unlabelled paths fall back to their own segment, so nothing is hidden.
 */
export default function FeatureLabelsConfig() {
  const [text, setText] = useState('');
  const [initial, setInitial] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchFeatureLabels()
      .then((m) => { if (alive) { const t = labelLines(m); setText(t); setInitial(t); } })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, []);

  const onSave = async () => {
    setBusy(true); setError(null); setSaved(false);
    try {
      const next = await saveFeatureLabels(parseLabelLines(text));
      const t = labelLines(next); setText(t); setInitial(t); setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const dirty = text !== initial;
  return (
    <div className="cfg-field">
      <label>Business-area labels <span className="muted">(names the Release Impact reports use for API areas and sub-features)</span></label>
      <textarea className="cfg-textarea" rows={7} spellCheck={false} value={text} onChange={(e) => setText(e.target.value)}
                placeholder={'payment = Funds Transfer & Payments\npayment/fundtransfer/quick = Quick Transfer'} />
      <div className="sub">
        One <code>path-key = Label</code> per line. Key = the API path after <code>/services/&lt;country&gt;/</code>, without version
        segments (<code>v1</code>, <code>v2</code>): a one-segment key names an <b>area</b>, a longer key a <b>sub-feature</b>. An unlabelled
        path shows its own segment. Applies to the Summary, the Detailed view and both PDFs.
      </div>
      <div className="cfg-actions">
        <button className="primary" disabled={busy || !dirty} onClick={onSave}>{busy ? 'Saving…' : 'Save labels'}</button>
        {saved && !error && <span className="cfg-ok">Saved <Check size={13} aria-hidden="true" /></span>}
        {error && <span className="cfg-error">{error}</span>}
      </div>
    </div>
  );
}
