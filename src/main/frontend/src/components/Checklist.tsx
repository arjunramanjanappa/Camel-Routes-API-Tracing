import { useMemo, useState } from 'react';

interface Props {
  title: string;
  items: string[];
  selected: Set<string>;
  onToggle: (item: string) => void;
  onSetMany: (items: string[], on: boolean) => void;
}

/** A searchable checklist with "select all shown" / "clear shown" helpers. */
export default function Checklist({ title, items, selected, onToggle, onSetMany }: Props) {
  const [q, setQ] = useState('');
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase();
    return t ? items.filter((i) => i.toLowerCase().includes(t)) : items;
  }, [items, q]);
  const selCount = items.filter((i) => selected.has(i)).length;

  return (
    <div className="panel checklist">
      <h2>{title} <span className="muted">{selCount}/{items.length} selected</span></h2>
      <input className="search-mini" placeholder={`filter ${title.toLowerCase()}…`} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="row" style={{ gap: 6, margin: '6px 0' }}>
        <button className="linkbtn" onClick={() => onSetMany(shown, true)}>select all shown</button>
        <button className="linkbtn" onClick={() => onSetMany(shown, false)}>clear shown</button>
      </div>
      <div className="checklist-items">
        {shown.length === 0 && <div className="muted">no items</div>}
        {shown.map((i) => (
          <label key={i} className="check">
            <input type="checkbox" checked={selected.has(i)} onChange={() => onToggle(i)} />
            <code>{i}</code>
          </label>
        ))}
      </div>
    </div>
  );
}
