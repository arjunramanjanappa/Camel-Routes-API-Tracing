import type { DepSource } from '../types';
import { blankDep } from '../deps';

/**
 * Editor for one or more optional dependency sources — extra repos/paths that provide the
 * route XMLs the primary source {@code <import>}s but doesn't itself contain (a shared-routes
 * library). Each row is a Local-path / Bitbucket-branch toggle, mirroring the primary source
 * selector. "Add dependency" appends another row, so several libraries can be supplied.
 */
export default function DependencyEditor({ deps, onChange }: { deps: DepSource[]; onChange: (d: DepSource[]) => void }) {
  const update = (i: number, patch: Partial<DepSource>) =>
    onChange(deps.map((d, idx) => (idx === i ? { ...d, ...patch } : d)));
  const remove = (i: number) => onChange(deps.filter((_, idx) => idx !== i));
  const add = () => onChange([...deps, blankDep(deps[deps.length - 1]?.sourceType ?? 'local')]);

  return (
    <div className="dep-editor">
      <div className="dep-editor-head">
        <span className="dep-editor-title">Dependency sources <span className="muted">(optional)</span></span>
        <span className="muted">core/shared repos or paths that supply the host XMLs this source imports — always in scope, independent of country or version</span>
      </div>
      {deps.map((d, i) => (
        <div className="dep-row" key={i}>
          <div className="seg dep-seg">
            <button type="button" className={d.sourceType === 'local' ? 'on' : ''} onClick={() => update(i, { sourceType: 'local' })}>Local path</button>
            <button type="button" className={d.sourceType === 'bitbucket' ? 'on' : ''} onClick={() => update(i, { sourceType: 'bitbucket' })}>Bitbucket branch</button>
          </div>
          {d.sourceType === 'bitbucket' ? (
            <>
              <input className="dep-input" value={d.repo} placeholder="https://bitbucket.internal/scm/PROJ/shared-routes.git"
                     onChange={(e) => update(i, { repo: e.target.value })} />
              <input className="dep-input dep-branch" value={d.branch} placeholder="release/9.18"
                     onChange={(e) => update(i, { branch: e.target.value })} />
            </>
          ) : (
            <input className="dep-input" value={d.sourceDir} placeholder="path to the shared-routes source"
                   onChange={(e) => update(i, { sourceDir: e.target.value })} />
          )}
          <button type="button" className="dep-remove" title="Remove this dependency" onClick={() => remove(i)}>×</button>
        </div>
      ))}
      <button type="button" className="dep-add" onClick={add}>＋ Add dependency</button>
    </div>
  );
}
