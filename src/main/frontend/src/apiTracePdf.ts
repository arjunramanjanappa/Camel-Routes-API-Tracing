import { ReportDoc, PAL, PAGE, M, CONTENT_W, stamp, generatedStamp } from './pdfReport';
import { backendPath } from './spl';
import type { CatalogResponse, TraceResponse } from './types';
import { isNaVersion, NO_ROUTE, releaseScopeGroups } from './catalog';

/** One module's catalog for the report (or an error if its analysis failed). */
export interface ModuleCat { name: string; cat: CatalogResponse | null; error?: string; }

/**
 * Render the multi-module Release Scope catalog to one PDF: a per-module coverage summary, then a
 * section per module listing every API it implements this release, grouped by module.
 */
export async function exportApiTracePdf(mods: ModuleCat[], app?: string, version?: string, country?: string) {
  const r = await ReportDoc.create();
  const ver = version && version.trim() ? version.trim() : 'N/A';

  const rows = mods.map((m) => {
    // Count only the APIs in scope for this release — base-fallback (N/A) APIs are excluded for a
    // concrete release, matching the UI, so a module's "APIs" reads as "touched this release".
    const groups = m.cat ? releaseScopeGroups(m.cat) : [];
    const traces = groups.flatMap((g) => g.traces);
    const routes = new Set<string>(); traces.forEach((t) => (t.flow || []).forEach((f) => routes.add(f)));
    const backends = new Set<string>(); traces.forEach((t) => (t.backendApis || []).forEach((b) => backends.add(b)));
    const noRoute = m.cat?.groups.find((g) => g.version === NO_ROUTE)?.traces.length ?? 0;
    return { name: m.name, apis: traces.length, routes: routes.size, backends: backends.size,
             noRoute, unversioned: !!m.cat?.unversioned, error: m.error };
  });
  const tot = { apis: 0, routes: 0, backends: 0 };
  rows.forEach((x) => { tot.apis += x.apis; tot.routes += x.routes; tot.backends += x.backends; });

  r.header('Release Scope Report',
    `${app ? app + '  -  ' : ''}${mods.length} module(s)  -  Release ${ver}${country ? '  -  ' + country : ''}`,
    `Generated ${generatedStamp()}`);

  // ===== Release Scope Summary =====
  r.banner('Release Scope Summary', PAL.blue);
  r.statBand([
    { n: tot.apis, label: 'Total APIs', ramp: PAL.blue },
    { n: mods.length, label: 'Modules', ramp: PAL.gray },
    { n: tot.routes, label: 'Routes', ramp: PAL.purple },
    { n: tot.backends, label: 'Backends', ramp: PAL.orange },
  ]);
  r.paragraph(`Release ${ver}${country ? ' in ' + country : ''} spans ${mods.length} module(s) and ${tot.apis} API(s) in scope. Coverage by module:`);
  r.dataTable(
    ['Module (pom artifactId)', 'Version', 'APIs', 'Routes', 'Backends'],
    rows.map((x) => [x.name + (x.error ? '  (failed)' : ''), x.error ? '—' : (x.unversioned ? 'N/A' : ver), x.apis, x.routes, x.backends]),
    ['Total', '', tot.apis, tot.routes, tot.backends],
  );

  // ===== How to read =====
  r.legend('How to read this report', [
    'Each module (its own repo) is analysed for the same release + country and grouped below.',
    'Resolves to = the entry route the API uses — Release <ver> for a versioned API, N/A for an unversioned one.',
    'A module or API with no versioned route is analysed at N/A (amber) — its latest / base route.',
    'Backends = the downstream APIs / hosts each flow calls (svc = the service version, where known).',
  ]);

  const footer = `TraceGuard - Release scope ${ver}${app ? ' - ' + app : ''}`;
  const anyBaseOrRoute = mods.some((m) => m.cat && m.cat.groups.some((g) => g.traces.length > 0));
  if (tot.apis === 0 && !anyBaseOrRoute && rows.every((x) => !x.error)) {
    r.emptyNote('No APIs resolve to this release across the selected modules.');
    r.save(file(ver), footer); return;
  }

  // ===== APIs by module =====
  r.banner('APIs by module', PAL.blue, 'Every API this release implements in each module.');
  for (const m of mods) {
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const cat = m.cat;
    if (!cat) continue;
    const groups = releaseScopeGroups(cat);   // in-scope for the release (excludes N/A base for a concrete release)
    const count = groups.reduce((n, g) => n + g.traces.length, 0);
    const concrete = !isNaVersion(cat.requestedVersion) && !cat.unversioned;
    const baseGroup = concrete ? cat.groups.find((g) => g.version === 'N/A') : undefined;
    r.section('Module — ' + m.name, count, cat.unversioned ? PAL.amber : PAL.blue,
      cat.unversioned ? 'Unversioned module — analysed at N/A (latest / base).'
        : baseGroup && baseGroup.traces.length ? `${baseGroup.traces.length} more API(s) have no ${ver} route (base / N/A) — summarised below, not counted.` : '');
    const svc = svcMap(cat);
    if (count === 0) {
      r.para('No APIs are versioned for this release in this module.', M + 4, CONTENT_W - 4, 'normal', 9, PAL.muted, 12);
    }
    groups.forEach((g) => g.traces.forEach((t, i) => { if (i > 0) r.separator(); apiRow(r, t, svc); }));
    // Base-fallback APIs: listed compactly (names only) so the report stays focused on what changed.
    if (baseGroup && baseGroup.traces.length) {
      r.para(`Base / N/A (no ${ver} route): ` + baseGroup.traces.map((t) => t.api || t.operationName || '(unknown)').join(', '),
        M + 4, CONTENT_W - 4, 'normal', 9, PAL.amber.text, 12);
    }
    const noRouteGroup = cat.groups.find((g) => g.version === NO_ROUTE);
    if (noRouteGroup && noRouteGroup.traces.length) {
      r.para('No route found: ' + noRouteGroup.traces.map((t) => t.operationName || t.api || '(unknown)').join(', '),
        M + 4, CONTENT_W - 4, 'normal', 9, PAL.amber.text, 12);
    }
    r.reviewSection(cat.needsReview);
  }
  r.save(file(ver), footer);
}

function svcMap(cat: CatalogResponse): Record<string, string> {
  const svc: Record<string, string> = {};
  (cat.graph?.nodes || []).forEach((n) => {
    if (n.type === 'BACKEND' && n.data && n.data.serviceVersion) svc[n.label] = n.data.serviceVersion;
  });
  return svc;
}

function apiRow(r: ReportDoc, t: TraceResponse, svc: Record<string, string>) {
  r.ensure(40);
  const path = t.api || t.operationName || '(unknown)';
  const w1 = r.text(path, M, 'bold', 11, PAL.ink);
  r.text(t.operationName || '', M + w1 + 8, 'normal', 9, PAL.muted);
  // A base-resolved API (no versioned route) is auto-N/A — amber; a versioned one shows Release <ver>.
  const pillLabel = t.baseFallback ? 'N/A' : (t.resolvedVersion ? 'Release ' + t.resolvedVersion : '');
  if (pillLabel) {
    const pw = r.width(pillLabel, 'bold', 8) + 12;
    r.pill(pillLabel, PAGE.w - M - pw, t.baseFallback ? PAL.amber.fill : PAL.blue.fill,
      t.baseFallback ? PAL.amber.text : PAL.blue.text, 8);
  }
  r.y += 16;
  r.para(`Resolves to ${t.resolvedRoute || '-'}.`, M, CONTENT_W, 'normal', 9, PAL.body, 12);
  if (t.flow && t.flow.length) {
    r.para('Flow: ' + t.flow.join(' -> '), M + 4, CONTENT_W - 4, 'normal', 9, PAL.body, 12);
  }
  if (t.backendApis && t.backendApis.length) {
    const bs = t.backendApis.map((b) => backendPath(b) + (svc[b] ? ` (svc ${svc[b]})` : ''));
    r.para('Backends: ' + bs.join(', '), M + 4, CONTENT_W - 4, 'normal', 9, PAL.orange.text, 12);
  }
  r.y += 6;
}

function file(ver: string): string { return `release-scope-${(ver || 'all').replace(/[^a-zA-Z0-9._-]+/g, '-')}-${stamp()}.pdf`; }
