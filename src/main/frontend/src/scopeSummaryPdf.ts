import type { CatalogResponse } from './types';
import { ReportDoc, PAL, generatedStamp } from './pdfReport';

/** One module's catalog for the report (or an error) — same shape the detailed trace PDF takes. */
export interface ScopeModule { name: string; cat: CatalogResponse | null; error?: string; }

/**
 * The 1–2 page leadership Summary PDF for the Release Scope tab — "what's in this release": the APIs in
 * scope, grouped by version, for release managers & delivery leads. The full route-flow report is
 * {@link exportApiTracePdf}.
 */
export async function exportScopeSummaryPdf(mods: ScopeModule[], app?: string, version?: string, country?: string) {
  const r = await ReportDoc.create();
  const ver = version || 'N/A';
  const ctry = country || mods.find((m) => m.cat)?.cat?.country || '';
  const withCat = mods.filter((m) => m.cat);

  const apisOf = (cat: CatalogResponse): number => {
    const s = new Set<string>();
    (cat.groups || []).forEach((g) => g.traces.forEach((t) => { if (t.api) s.add(t.api); }));
    return s.size;
  };
  const totalApis = withCat.reduce((n, m) => n + apisOf(m.cat!), 0);
  const versions = new Set<string>();
  withCat.forEach((m) => (m.cat!.groups || []).forEach((g) => versions.add(g.version)));

  r.titlePage('Release Scope — Summary',
    'For release managers, coordinators & delivery leads',
    [
      `${app ? app + ' · ' : ''}Release ${ver}${ctry ? ' · ' + ctry : ''}`,
      `${totalApis} API(s) in scope across ${withCat.length} module(s)`,
      'Generated ' + generatedStamp(),
    ]);

  r.bookmark('APIs in scope');
  r.banner('APIs in scope', PAL.blue, `Release ${ver}${ctry ? ' · ' + ctry : ''} — the APIs this release covers, grouped by version.`);
  r.statBand([
    { n: totalApis, label: 'APIs in scope', ramp: PAL.blue },
    { n: withCat.length, label: 'Modules', ramp: PAL.gray },
    { n: versions.size, label: 'Versions', ramp: PAL.purple },
  ]);

  for (const m of mods) {
    r.bookmark('Module — ' + m.name);
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const cat = m.cat; if (!cat) continue;
    r.banner('Module — ' + m.name, PAL.gray, `${apisOf(cat)} API(s) in scope.`);
    const rows = (cat.groups || [])
      .map((g) => {
        const apis = [...new Set(g.traces.map((t) => t.api).filter(Boolean))] as string[];
        return { label: g.version === 'N/A' ? 'Base routes' : 'Release ' + g.version, apis };
      })
      .filter((x) => x.apis.length > 0);
    if (rows.length) {
      r.wrapTable(
        [{ header: 'Version', w: 0.22 }, { header: 'APIs', w: 0.78, mono: true }],
        rows.map((x) => [x.label, { text: x.apis.join(', '), mono: true, color: PAL.ink }]));
    }
  }

  const footer = `TraceGuard - Release ${ver}${ctry ? ' · ' + ctry : ''}${app ? ' · ' + app : ''} - Scope Summary`;
  r.save(`TraceGuard-Release-${ver}-Scope-Summary.pdf`, footer);
}
