import type { CatalogResponse } from '../types';
import { groupByFeature, versionLabel } from '../feature';

/**
 * The leadership Summary for the Release Scope tab: a plain "what's in this release" view — the APIs in
 * scope grouped by version (BAU for base routes) and then by business feature — instead of the technical
 * route flow graph (which stays in Detailed). The Front-end ⇄ Backend toggle lives in the tab toolbar and
 * is passed in via `side`. Reuses the shared `.sumv-*` styles.
 */
export default function ScopeSummary({ catalog, side = 'fe' }: { catalog: CatalogResponse; side?: 'fe' | 'be' }) {
  const groups = catalog.groups || [];

  // Items per version group for the selected side (front-end paths, or the backend APIs the traces call).
  const perGroup = groups.map((g) => {
    const items = side === 'fe'
      ? g.traces.map((t) => t.api)
      : g.traces.flatMap((t) => t.backendApis || []);
    return { version: g.version, features: groupByFeature(items), count: new Set(items.map((x) => (x || '').replace(/^\{\{[^}]+\}\}/, '').split('?')[0]).filter(Boolean)).size };
  }).filter((g) => g.count > 0);

  const total = perGroup.reduce((n, g) => n + g.count, 0);
  const featureNames = new Set<string>();
  perGroup.forEach((g) => g.features.forEach((f) => featureNames.add(f.feature)));

  return (
    <div className="sumv">
      <p className="sumv-eyebrow" style={{ margin: '2px 0 10px' }}>What’s in this release{catalog.country ? ` · ${catalog.country}` : ''} · {side === 'fe' ? 'front-end' : 'backend'} APIs</p>

      <div className="sumv-tiles">
        <div className="sumv-tile accent"><div className="n">{total}</div><div className="l">{side === 'fe' ? 'Front-end' : 'Backend'} APIs in scope</div></div>
        <div className="sumv-tile good"><div className="n">{featureNames.size}</div><div className="l">Feature{featureNames.size === 1 ? '' : 's'}</div></div>
        <div className="sumv-tile violet"><div className="n">{perGroup.length}</div><div className="l">Version group{perGroup.length === 1 ? '' : 's'}</div></div>
      </div>

      {total === 0 ? (
        <div className="sumv-empty">No {side === 'fe' ? 'front-end' : 'backend'} APIs in scope — analyse a release above.</div>
      ) : (
        <div className="sumv-groups">
          {perGroup.map((g) => (
            <div className="sumv-group" key={g.version}>
              <div className="sumv-group-head">
                <span>{versionLabel(g.version)}</span>
                <span className="sumv-group-cnt">{g.count}</span>
              </div>
              {g.features.map((f) => (
                <div className="sumv-feat" key={f.feature}>
                  <div className="sumv-feat-head">
                    <span className="sumv-feat-name">{f.feature}</span>
                    <span className="sumv-feat-cnt">{f.items.length}</span>
                  </div>
                  <ul className="sumv-group-list">
                    {f.items.map((a) => <li key={a}><span className="path">{displayPath(a)}</span></li>)}
                  </ul>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** Strip a backend {{placeholder}} prefix for display (front-end paths are unaffected). */
function displayPath(p: string): string { return p.replace(/^\{\{[^}]+\}\}/, ''); }
