import { ReportDoc, PAL, M, CONTENT_W } from './pdfReport';
import { backendPath } from './spl';

export interface ImpactRow {
  api: string;
  operation: string;
  resolvedRoute?: string;
  selected: boolean;
  viaRoutes: string[];
  viaBackends: string[];
}

export interface ImpactPdfInput {
  app?: string;
  version?: string;
  country?: string;
  totalApis: number;
  changedRoutes: string[];
  changedBackends: string[];
  rows: ImpactRow[];
  backendVersions: Record<string, string>;
}

/** Render the impact analysis to a downloadable PDF report. */
export async function exportImpactPdf(input: ImpactPdfInput) {
  const r = await ReportDoc.create();
  const ver = input.version || 'BASE';
  const selected = input.rows.filter((x) => x.selected);
  const blast = input.rows.filter((x) => !x.selected);

  r.header('Impact Analysis Report',
    `${input.app ? input.app + '  -  ' : ''}Release ${ver}${input.country ? '  -  ' + input.country : ''}`,
    `The blast radius of a change across the release's APIs. Generated ${new Date().toLocaleString()}.`);

  r.statBand([
    { n: input.rows.length, label: 'Impacted APIs', ramp: PAL.blue },
    { n: selected.length, label: 'Directly selected', ramp: PAL.green },
    { n: input.totalApis, label: 'APIs in release', ramp: PAL.gray },
  ]);

  r.paragraph(`A change to ${input.changedRoutes.length} route(s) and ${input.changedBackends.length} backend(s) `
    + `impacts ${input.rows.length} of ${input.totalApis} API(s) at release ${ver} `
    + `(${selected.length} selected directly, ${blast.length} in the blast radius).`);

  if (input.changedRoutes.length) {
    r.para('Changed routes: ' + input.changedRoutes.join(', '), M, CONTENT_W, 'normal', 9, PAL.green.text, 12);
  }
  if (input.changedBackends.length) {
    r.para('Changed backends: ' + input.changedBackends.map((b) => backendPath(b)).join(', '),
      M, CONTENT_W, 'normal', 9, PAL.orange.text, 12);
  }
  r.y += 6;

  r.legend('How to read this report', [
    'Directly selected = the APIs you chose as the change under assessment.',
    'Blast radius = other APIs that share a changed route or backend and may be affected.',
    'Impacted via = the specific shared route(s) / backend(s) that tie each API to the change.',
    'svc = the backend service version the API sends (where known).',
  ]);

  const footer = `TraceGuard - Impact analysis ${ver}${input.app ? ' - ' + input.app : ''}`;
  if (input.rows.length === 0) { r.emptyNote('No APIs are impacted by the current selection.'); r.save(file(ver), footer); return; }

  if (selected.length) {
    r.section('Directly selected APIs', selected.length, PAL.blue, 'The APIs you selected as the change to assess.');
    selected.forEach((row, i) => { if (i > 0) r.separator(); apiRow(r, row, input.backendVersions); });
  }
  if (blast.length) {
    r.section('Blast radius', blast.length, PAL.gray,
      'Other APIs that share a changed route or backend - they may be affected and warrant testing.');
    blast.forEach((row, i) => { if (i > 0) r.separator(); apiRow(r, row, input.backendVersions); });
  }

  r.save(file(ver), footer);
}

function apiRow(r: ReportDoc, row: ImpactRow, svc: Record<string, string>) {
  r.ensure(34);
  const w = r.text(row.api, M, 'bold', 11, PAL.ink);
  r.text(row.operation, M + w + 8, 'normal', 9, PAL.muted);
  r.y += 14;
  r.para(`Resolves to ${row.resolvedRoute || '-'}.`, M, CONTENT_W, 'normal', 9, PAL.body, 12);
  if (row.viaRoutes.length) {
    r.para('Impacted via routes: ' + row.viaRoutes.join(', '), M + 4, CONTENT_W - 4, 'normal', 9, PAL.green.text, 12);
  }
  if (row.viaBackends.length) {
    const bs = row.viaBackends.map((b) => backendPath(b) + (svc[b] ? ` (svc ${svc[b]})` : ''));
    r.para('Impacted via backends: ' + bs.join(', '), M + 4, CONTENT_W - 4, 'normal', 9, PAL.orange.text, 12);
  }
  r.y += 6;
}

function file(ver: string): string { return `impact-analysis-${ver === 'BASE' ? 'base' : ver}.pdf`; }
