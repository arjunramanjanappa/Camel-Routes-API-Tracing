import type { CatalogResponse } from '../types';

/**
 * The leadership Summary for the Release Scope tab: a plain "what's in this release" view — how many APIs
 * are in scope and their names grouped by version — instead of the technical route flow graph (which stays
 * in Detailed). Reuses the shared `.sumv-*` styles.
 */
export default function ScopeSummary({ catalog }: { catalog: CatalogResponse }) {
  const groups = catalog.groups || [];
  const allApis = new Set<string>();
  groups.forEach((g) => g.traces.forEach((t) => { if (t.api) allApis.add(t.api); }));

  return (
    <div className="sumv">
      <p className="sumv-eyebrow">What’s in this release{catalog.country ? ` · ${catalog.country}` : ''}</p>

      <div className="sumv-tiles">
        <div className="sumv-tile accent"><div className="n">{allApis.size}</div><div className="l">APIs in scope</div></div>
        <div className="sumv-tile violet"><div className="n">{groups.length}</div><div className="l">Version group{groups.length === 1 ? '' : 's'}</div></div>
      </div>

      {allApis.size === 0 ? (
        <div className="sumv-empty">No APIs in scope — analyse a release above.</div>
      ) : (
        <div className="sumv-groups">
          {groups.map((g) => {
            const apis = [...new Set(g.traces.map((t) => t.api).filter(Boolean))] as string[];
            if (apis.length === 0) return null;
            return (
              <div className="sumv-group" key={g.version}>
                <div className="sumv-group-head">
                  <span>{g.version === 'N/A' ? 'Base routes' : 'Release ' + g.version}</span>
                  <span className="sumv-group-cnt">{apis.length}</span>
                </div>
                <ul className="sumv-group-list">
                  {apis.map((a) => <li key={a}><span className="path">{a}</span></li>)}
                </ul>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
