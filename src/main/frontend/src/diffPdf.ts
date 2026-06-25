import type { ApiDiff, DiffStatus, VersionDiffReport } from './types';
import { ReportDoc, PAL, PAGE, M, CONTENT_W, stamp, type Ramp } from './pdfReport';

const STATUS_ORDER: DiffStatus[] = ['CHANGED', 'NEW', 'UNCHANGED'];
function sectionMeta(s: DiffStatus): { title: string; ramp: Ramp; blurb: string } {
  if (s === 'CHANGED') return { title: 'Changed APIs', ramp: PAL.amber,
    blurb: 'Existing APIs whose Camel flow differs from the previous version - review and regression-test these.' };
  if (s === 'NEW') return { title: 'New APIs', ramp: PAL.green,
    blurb: 'Introduced in this release with no earlier version - net-new functionality to test end to end.' };
  return { title: 'Unchanged APIs', ramp: PAL.gray,
    blurb: 'A version bump with no behavioural change, or APIs this release did not touch.' };
}

/** Render the (filtered) release-diff to a downloadable PDF report. */
export async function exportDiffPdf(report: VersionDiffReport, apis: ApiDiff[], filtered: boolean, app?: string) {
  const r = await ReportDoc.create();

  r.header('Release Diff Report',
    `${app ? app + '  -  ' : ''}Release ${report.version || 'BASE'}${report.country ? '  -  ' + report.country : ''}`,
    `Each API is compared against its immediately-preceding version. Generated ${new Date().toLocaleString()}.`);

  r.statBand([
    { n: report.changedCount, label: 'Changed', ramp: PAL.amber },
    { n: report.newCount, label: 'New', ramp: PAL.green },
    { n: report.unchangedCount, label: 'Unchanged', ramp: PAL.gray },
  ]);

  r.paragraph(`Release ${report.version || 'BASE'} changes ${report.changedCount} existing API(s) and introduces `
    + `${report.newCount} new API(s) relative to the previous version; ${report.unchangedCount} are unaffected.`
    + (filtered ? `  Filtered view: ${apis.length} of ${report.apis.length} API(s) shown.` : ''));

  r.legend('How to read this report', [
    'Changed = the resolved Camel flow differs (routes, backends or service versions).',
    'New = first appears in this release; Unchanged = version bump with no behavioural change.',
    'Under "What changed", lines marked - were removed and + were added vs the previous version.',
    'svc = the backend service version sent to the host (read from the request template).',
  ]);

  const footer = `TraceGuard - Release diff ${report.version || 'BASE'}${app ? ' - ' + app : ''}`;
  if (apis.length === 0) { r.emptyNote('No APIs in the current view.'); r.save(fileName(report), footer); return; }

  const grouped: Record<DiffStatus, ApiDiff[]> = { CHANGED: [], NEW: [], UNCHANGED: [] };
  apis.forEach((a) => grouped[a.status].push(a));

  for (const status of STATUS_ORDER) {
    const list = grouped[status];
    if (!list.length) continue;
    const meta = sectionMeta(status);
    r.section(meta.title, list.length, meta.ramp, meta.blurb);
    list.forEach((a, idx) => { if (idx > 0) r.separator(); apiBlock(r, a, status); });
  }

  r.save(fileName(report), footer);
}

function apiBlock(r: ReportDoc, a: ApiDiff, status: DiffStatus) {
  r.ensure(40);
  const pathW = r.text(a.api, M, 'bold', 11, PAL.ink);
  r.text(a.operation, M + pathW + 8, 'normal', 9, PAL.muted);
  if (a.lowerVersion && (status === 'CHANGED' || (status === 'UNCHANGED' && !a.note))) {
    const vt = `${a.lowerVersion} -> ${a.targetVersion}`;
    const vw = r.width(vt, 'bold', 8) + 12;
    r.pill(vt, PAGE.w - M - vw, PAL.gray.fill, PAL.gray.text, 8);
  }
  r.y += 16;

  if (status === 'NEW') {
    r.para(`Introduced in ${a.targetVersion}. Entry route ${a.targetRoute}. No earlier version to compare against.`,
      M, CONTENT_W, 'normal', 9, PAL.body, 12);
    if (a.authors && a.authors.length) {
      r.para('Added by: ' + a.authors.join(', '), M, CONTENT_W, 'normal', 9, PAL.accent, 12);
    }
  } else if (a.note) {
    r.para(a.note, M, CONTENT_W, 'normal', 9, PAL.body, 12);
  } else {
    r.para(`Resolves to ${a.targetRoute}, compared against ${a.lowerRoute}.`, M, CONTENT_W, 'normal', 9, PAL.body, 12);
  }

  const summarize = (label: string, names: string[], col: typeof PAL.amber.text) => {
    if (names.length) r.para(`${label}: ${names.join(', ')}`, M + 4, CONTENT_W - 4, 'normal', 9, col, 12);
  };
  summarize('Edited routes', (a.routeDiffs || []).map((rd) => rd.routeBase), PAL.amber.text);
  summarize('Added routes', a.addedRoutes || [], PAL.addText);
  summarize('Removed routes', a.removedRoutes || [], PAL.delText);
  (a.backendVersionChanges || []).forEach((s) =>
    summarize('Backend service version', [`${s.backend}  ${s.fromVersion} -> ${s.toVersion}`], PAL.amber.text));

  (a.routeDiffs || []).forEach((rd) => {
    r.ensure(18);
    r.text(`${rd.routeBase}   (+${rd.added.length}  -${rd.removed.length})`, M + 4, 'bold', 9, PAL.ink); r.y += 12;
    if (rd.changedBy && rd.changedBy.length) {
      r.para('Changed by: ' + rd.changedBy.join(', '), M + 4, CONTENT_W - 4, 'normal', 8, PAL.accent, 11);
    }
    r.diffLines(rd.removed, rd.added);
    r.y += 3;
  });
  if (a.payloadChange && (a.payloadChange.addedKeys.length || a.payloadChange.removedKeys.length)) {
    const keys = [...a.payloadChange.addedKeys.map((k) => '+' + k), ...a.payloadChange.removedKeys.map((k) => '-' + k)];
    summarize('Payload change (keys)', keys, PAL.blue.text);
  }
  r.y += 6;
}

function fileName(report: VersionDiffReport): string {
  return `release-diff-${report.version || 'base'}-${stamp()}.pdf`;
}
