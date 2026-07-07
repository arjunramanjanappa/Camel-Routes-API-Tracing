/**
 * The highlighted "Needs review" callout: imports/routes that could not be resolved even after
 * any dependency sources were added. It is intentionally distinct from the general warning banner
 * so the reader can see, at a glance, that some XMLs are still pending review — and offers a
 * one-click way to add the dependency source that would resolve them.
 */
export default function NeedsReviewBox({ items, onAddDependency }: { items: string[]; onAddDependency: () => void }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="reviewbox">
      <div className="reviewbox-head">
        <span className="reviewbox-title">⚑ Needs review — {items.length} unresolved reference{items.length > 1 ? 's' : ''}</span>
        <button type="button" className="reviewbox-add" onClick={onAddDependency}>＋ Add dependency source</button>
      </div>
      <div className="reviewbox-sub">
        These XMLs/routes are imported but not present in the source provided — so the analysis may be
        incomplete. Add the dependency that defines them and re-run; anything still listed should be reviewed manually.
      </div>
      <ul className="reviewbox-list">
        {items.map((it, i) => <li key={i}>{it}</li>)}
      </ul>
    </div>
  );
}
