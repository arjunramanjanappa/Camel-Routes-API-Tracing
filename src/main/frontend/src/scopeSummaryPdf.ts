import type { CatalogResponse } from './types';
import { ReportDoc, PAL, M, CONTENT_W, generatedStamp } from './pdfReport';
import { groupByFeature, versionLabel } from './feature';

/** One module's catalog for the report (or an error) — same shape the detailed trace PDF takes. */
export interface ScopeModule { name: string; cat: CatalogResponse | null; error?: string; }

function displayPath(p: string): string { return (p || '').replace(/^\{\{[^}]+\}\}/, ''); }

/** APIs per version group for one side (front-end paths / backend APIs), grouped by feature. */
function bySide(cat: CatalogResponse, side: 'fe' | 'be') {
  return (cat.groups || []).map((g) => {
    const items = side === 'fe' ? g.traces.map((t) => t.api) : g.traces.flatMap((t) => t.backendApis || []);
    const features = groupByFeature(items);
    const count = features.reduce((n, f) => n + f.items.length, 0);
    return { version: g.version, features, count };
  }).filter((x) => x.count > 0);
}

/**
 * The 1–2 page leadership Summary PDF for the Release Scope tab — "what's in this release": the front-end
 * and backend APIs in scope, grouped by version (BAU for base routes) and business feature. The full
 * route-flow report is {@link exportApiTracePdf}.
 */
export async function exportScopeSummaryPdf(mods: ScopeModule[], app?: string, version?: string, country?: string) {
  const r = await ReportDoc.create();
  const ver = version || 'N/A';
  const ctry = country || mods.find((m) => m.cat)?.cat?.country || '';
  const withCat = mods.filter((m) => m.cat);

  const feCount = withCat.reduce((n, m) => n + bySide(m.cat!, 'fe').reduce((s, g) => s + g.count, 0), 0);
  const features = new Set<string>();
  const versions = new Set<string>();
  withCat.forEach((m) => bySide(m.cat!, 'fe').forEach((g) => { versions.add(g.version); g.features.forEach((f) => features.add(f.feature)); }));

  r.titlePage('Release Scope — Summary',
    'For release managers, coordinators & delivery leads',
    [
      `${app ? app + ' · ' : ''}Release ${ver}${ctry ? ' · ' + ctry : ''}`,
      `${feCount} front-end API(s) across ${features.size} feature(s), ${withCat.length} module(s)`,
      'Generated ' + generatedStamp(),
    ]);

  r.bookmark('APIs in scope');
  r.banner('APIs in scope', PAL.blue, `Release ${ver}${ctry ? ' · ' + ctry : ''} — grouped by version (BAU = base routes) and business feature.`);
  r.statBand([
    { n: feCount, label: 'Front-end APIs', ramp: PAL.blue },
    { n: features.size, label: 'Features', ramp: PAL.green },
    { n: versions.size, label: 'Version groups', ramp: PAL.purple },
  ]);

  const featureTable = (perGroup: ReturnType<typeof bySide>) => {
    for (const g of perGroup) {
      r.para(`${versionLabel(g.version)}  (${g.count})`, M, CONTENT_W, 'bold', 10.5, PAL.ink, 15);
      r.wrapTable(
        [{ header: 'Feature', w: 0.22 }, { header: 'APIs', w: 0.78, mono: true }],
        g.features.map((f) => [f.feature, { text: f.items.map(displayPath).join(', '), mono: true, color: PAL.ink }]));
    }
  };

  for (const m of mods) {
    r.bookmark('Module — ' + m.name);
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const cat = m.cat; if (!cat) continue;
    const fe = bySide(cat, 'fe');
    const be = bySide(cat, 'be');
    r.banner('Module — ' + m.name, PAL.gray, `${fe.reduce((n, g) => n + g.count, 0)} front-end · ${be.reduce((n, g) => n + g.count, 0)} backend API(s).`);
    if (fe.length) { r.section('Front-end APIs', fe.reduce((n, g) => n + g.count, 0), PAL.blue, ''); featureTable(fe); }
    if (be.length) { r.section('Backend APIs', be.reduce((n, g) => n + g.count, 0), PAL.orange, ''); featureTable(be); }
  }

  const footer = `TraceGuard - Release ${ver}${ctry ? ' · ' + ctry : ''}${app ? ' · ' + app : ''} - Scope Summary`;
  r.save(`TraceGuard-Release-${ver}-Scope-Summary.pdf`, footer);
}
