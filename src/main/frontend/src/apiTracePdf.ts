import { ReportDoc, PAL, PAGE, M, CONTENT_W, stamp, generatedStamp } from './pdfReport';
import { backendPath } from './spl';
import type { CatalogResponse, TraceResponse } from './types';

const NO_ROUTE = '(no route found)';

/**
 * Render the API Trace catalog to a downloadable PDF: every API impacted by the
 * release, grouped by the version each resolves to, with its flow and backends.
 * Always the whole catalog (never a single API) so the report can't under-report.
 */
export async function exportApiTracePdf(cat: CatalogResponse, app?: string) {
  const r = await ReportDoc.create();
  const ver = cat.requestedVersion && cat.requestedVersion.trim() ? cat.requestedVersion.trim() : '';
  const verLabel = ver || 'all versions';

  // Backend service version per backend, read from the trace graph's BACKEND nodes.
  const svc: Record<string, string> = {};
  (cat.graph?.nodes || []).forEach((n) => {
    if (n.type === 'BACKEND' && n.data && n.data.serviceVersion) svc[n.label] = n.data.serviceVersion;
  });

  const impactedGroups = cat.groups.filter((g) => g.version !== NO_ROUTE);
  const noRouteGroup = cat.groups.find((g) => g.version === NO_ROUTE);
  const impacted = impactedGroups.reduce((n, g) => n + g.traces.length, 0);
  const allTraces = impactedGroups.flatMap((g) => g.traces);
  const routes = new Set<string>();
  allTraces.forEach((t) => (t.flow || []).forEach((f) => routes.add(f)));
  const backends = new Set<string>();
  allTraces.forEach((t) => (t.backendApis || []).forEach((b) => backends.add(b)));
  const noRoute = noRouteGroup?.traces.length ?? 0;

  r.header('Release Scope Report',
    `${app ? app + '  -  ' : ''}Release ${verLabel}${cat.country ? '  -  ' + cat.country : ''}`,
    `Generated ${generatedStamp()}`);

  // ===== Release Scope Summary =====
  r.banner('Release Scope Summary', PAL.blue);
  r.statBand([
    { n: impacted, label: 'Impacted APIs', ramp: PAL.blue },
    { n: routes.size, label: 'Routes involved', ramp: PAL.purple },
    { n: backends.size, label: 'Backends touched', ramp: PAL.orange },
  ]);

  r.paragraph(`Release ${verLabel}${cat.country ? ' in ' + cat.country : ''} implements ${impacted} API(s), `
    + `flowing through ${routes.size} route(s) to ${backends.size} backend(s).`
    + (noRoute ? ` ${noRoute} discovered API(s) have no route in this release and are listed separately.` : ''));

  // ===== How to read this report =====
  r.legend('How to read this report', [
    'Resolves to = the entry route the API uses at this version (R<ver>_... or BASE).',
    'Flow = the ordered Camel routes a request passes through.',
    'Backends = the downstream APIs / hosts the flow calls (svc = the service version sent, where known).',
    'No route found = a discovered API with no route in this release - listed, not counted as impacted.',
  ]);

  const footer = `TraceGuard - Release scope ${verLabel}${app ? ' - ' + app : ''}`;
  if (impacted === 0 && noRoute === 0) {
    r.emptyNote('No APIs resolve to this release in the current scope.');
    r.reviewSection(cat.needsReview);
    r.save(file(ver), footer);
    return;
  }

  // ===== APIs in scope =====
  r.banner('APIs in scope', PAL.blue, 'Every API this release implements, grouped by the version it resolves to.');
  impactedGroups.forEach((g) => {
    const label = g.version === 'BASE' ? 'Base'
      : /^(n\/a|na|latest)$/i.test(g.version) ? 'Latest per API' : 'Release ' + g.version;
    r.section(label, g.traces.length, g.version === 'BASE' ? PAL.gray : PAL.blue, '');
    g.traces.forEach((t, i) => { if (i > 0) r.separator(); apiRow(r, t, svc); });
  });

  if (noRouteGroup && noRouteGroup.traces.length) {
    r.section('No route found', noRouteGroup.traces.length, PAL.amber,
      'Discovered endpoints with no route at this release - not counted as impacted.');
    const names = noRouteGroup.traces.map((t) => t.operationName || t.api || '(unknown)');
    r.para(names.join(', '), M, CONTENT_W, 'normal', 9, PAL.muted, 12);
  }

  r.reviewSection(cat.needsReview);
  r.save(file(ver), footer);
}

function apiRow(r: ReportDoc, t: TraceResponse, svc: Record<string, string>) {
  r.ensure(40);
  const path = t.api || t.operationName || '(unknown)';
  const w1 = r.text(path, M, 'bold', 11, PAL.ink);
  r.text(t.operationName || '', M + w1 + 8, 'normal', 9, PAL.muted);
  // A base-resolved API (no versioned route) is auto-N/A — flagged amber; a versioned one shows Release <ver>.
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

function file(ver: string): string { return `release-scope-${ver || 'all'}-${stamp()}.pdf`; }
