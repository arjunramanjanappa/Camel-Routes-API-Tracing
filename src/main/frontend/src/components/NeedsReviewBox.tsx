/**
 * The highlighted "Needs review" callout: imports/routes that could not be resolved even after
 * any dependency sources were added. It is intentionally distinct from the general warning banner
 * so the reader can see, at a glance, that some XMLs are still pending review. When it shows, the
 * Dependency-sources editor is revealed next to the Source input so the missing pieces can be added.
 */
export default function NeedsReviewBox({ items }: { items: string[] }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="reviewbox">
      <div className="reviewbox-head">
        <span className="reviewbox-title">⚑ Needs review — {items.length} unresolved reference{items.length > 1 ? 's' : ''}</span>
      </div>
      <div className="reviewbox-sub">
        These XMLs/routes are imported/called but weren&rsquo;t found in the source provided — so the analysis
        may be incomplete. Add the dependency that defines them under <b>Source → Dependency sources</b> and
        re-run; anything still listed should be reviewed manually.
      </div>
      <ul className="reviewbox-list">
        {items.map((it, i) => <li key={i}>{it}</li>)}
      </ul>
    </div>
  );
}
