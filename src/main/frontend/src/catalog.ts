import type { CatalogResponse, RouteGraph, VersionGroup } from './types';

/** The catalog's "no route at all" bucket — never counted as in-scope. */
export const NO_ROUTE = '(no route found)';

/** Did the user ask for N/A / latest (rather than a concrete release)? */
export function isNaVersion(v?: string | null): boolean {
  const s = (v || '').trim().toUpperCase();
  return s === '' || s === 'N/A' || s === 'NA' || s === 'LATEST';
}

/** Version groups that carry real routes (excludes the "no route found" bucket). */
export function scopeGroups(cat: CatalogResponse): VersionGroup[] {
  return (cat.groups || []).filter((g) => g.version !== NO_ROUTE);
}

function sum(groups: VersionGroup[]): number {
  return groups.reduce((n, g) => n + g.traces.length, 0);
}

/** APIs resolved to base (N/A) — shown in the catalog for completeness, but not "in scope" for a concrete release. */
export function baseCount(cat: CatalogResponse): number {
  return sum((cat.groups || []).filter((g) => g.version === 'N/A'));
}

/**
 * The version groups that count as "in scope" for the release.
 *
 * For a concrete release, base-fallback (N/A) APIs are excluded: they have no route AT this
 * release (only base), so counting them makes every module look fully impacted. For an N/A
 * selection, or a repo with no versions at all (unversioned), every routed API is in scope.
 */
export function releaseScopeGroups(cat: CatalogResponse): VersionGroup[] {
  const groups = scopeGroups(cat);
  if (isNaVersion(cat.requestedVersion) || cat.unversioned) return groups;
  return groups.filter((g) => g.version !== 'N/A');
}

/** APIs in scope for the release — the module-card / catalog headline count. */
export function inScopeCount(cat: CatalogResponse): number {
  return sum(releaseScopeGroups(cat));
}

/** The API graph-node ids for a version group (matches the backend id `"api:" + api|operation`). */
export function apiIdsForGroup(group: VersionGroup): Set<string> {
  const ids = new Set<string>();
  group.traces.forEach((t) => {
    if (t.api) ids.add('api:' + t.api);
    if (t.operationName) ids.add('api:' + t.operationName);
  });
  return ids;
}

/**
 * Reduce a catalog graph to just the subgraph reachable from a set of API nodes — so the
 * Release Scope graph can show one release version's flow at a time, not one big chunk.
 */
export function filterGraphByApis(graph: RouteGraph, apiIds: Set<string>): RouteGraph {
  const nodeIds = new Set((graph.nodes || []).map((n) => n.id));
  const adj = new Map<string, string[]>();
  (graph.edges || []).forEach((e) => {
    const list = adj.get(e.from) || [];
    list.push(e.to);
    adj.set(e.from, list);
  });
  const keep = new Set<string>();
  const queue: string[] = [];
  apiIds.forEach((id) => { if (nodeIds.has(id)) { keep.add(id); queue.push(id); } });
  while (queue.length) {
    const cur = queue.shift() as string;
    (adj.get(cur) || []).forEach((nb) => { if (!keep.has(nb)) { keep.add(nb); queue.push(nb); } });
  }
  return {
    nodes: (graph.nodes || []).filter((n) => keep.has(n.id)),
    edges: (graph.edges || []).filter((e) => keep.has(e.from) && keep.has(e.to)),
  };
}
