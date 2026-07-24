/**
 * Feature grouping for service paths. Framework REST paths follow /services/<country>/<feature>/<api>
 * (e.g. /services/sg/fx/getFxRate → feature "fx"), so the third segment names the business feature. Used
 * across the summary views and PDFs so grouping + wording stay consistent.
 */

/** Strip a {{placeholder}} prefix (backend URIs carry one) and any query string. */
function cleanPath(p: string): string {
  return (p || '').replace(/^\{\{[^}]+\}\}/, '').split('?')[0];
}

/** The business feature of a path — the segment after /services/<country>/, else the first meaningful one. */
export function featureOf(path: string): string {
  const parts = cleanPath(path).split('/').filter(Boolean);
  if (parts[0] && parts[0].toLowerCase() === 'services' && parts.length >= 3) return parts[2];
  return parts[1] || parts[0] || 'other';
}

/** Group paths by feature (deduped), features sorted A→Z, paths sorted within each. */
export function groupByFeature(paths: (string | null | undefined)[]): { feature: string; items: string[] }[] {
  const m = new Map<string, Set<string>>();
  for (const raw of paths) {
    const p = (raw || '').trim();
    if (!p) continue;
    const f = featureOf(p);
    if (!m.has(f)) m.set(f, new Set());
    m.get(f)!.add(p);
  }
  return [...m.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([feature, items]) => ({ feature, items: [...items].sort() }));
}

/** Human label for a version group: the N/A / base group is BAU (business-as-usual) scope. */
export function versionLabel(version: string | null | undefined): string {
  return !version || version === 'N/A' || version === 'BASE' ? 'BAU' : 'Release ' + version;
}
