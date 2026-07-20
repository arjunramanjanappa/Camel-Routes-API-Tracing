import type { ApiDiff, DiffStatus, VersionDiffReport } from './types';
import { ReportDoc, PAL, PAGE, M, CONTENT_W, stamp, generatedStamp, type Ramp } from './pdfReport';

// Only changed + new APIs are listed in the report body — the utility goal is "what to test this
// release". Unchanged APIs are still counted in the summary table, just not enumerated.
const LISTED_STATUSES: ('CHANGED' | 'NEW')[] = ['CHANGED', 'NEW'];
function sectionMeta(s: DiffStatus): { title: string; ramp: Ramp; blurb: string } {
  if (s === 'CHANGED') return { title: 'Changed APIs', ramp: PAL.amber,
    blurb: 'Existing APIs whose Camel flow differs from the previous version - review and regression-test these.' };
  if (s === 'NEW') return { title: 'New APIs', ramp: PAL.green,
    blurb: 'Introduced in this release with no earlier version - net-new functionality to test end to end.' };
  return { title: 'Unchanged APIs', ramp: PAL.gray,
    blurb: 'A version bump with no behavioural change, or APIs this release did not touch.' };
}

/** One module's version-diff for the report (or an error). */
export interface ModuleDiff { name: string; report: VersionDiffReport | null; error?: string; }

/** Render the multi-module release-diff to one PDF: a per-module impact summary, then each module's changes. */
export async function exportDiffPdf(mods: ModuleDiff[], app?: string) {
  const r = await ReportDoc.create();
  const first = mods.find((m) => m.report)?.report;
  const ver = first?.version || 'N/A';
  const country = first?.country;

  const rows = mods.map((m) => {
    const rep = m.report;
    return { name: m.name, error: m.error, snapshot: !!rep?.snapshot,
      changed: rep?.changedCount ?? 0, added: rep?.newCount ?? 0, unchanged: rep?.unchangedCount ?? 0,
      code: rep?.codeChangedCount ?? 0,
      snap: rep?.snapshotCount ?? (rep?.snapshot ? rep.apis.length : 0) };
  });
  const tot = { changed: 0, added: 0, unchanged: 0, code: 0 };
  rows.forEach((x) => { tot.changed += x.changed; tot.added += x.added; tot.unchanged += x.unchanged; tot.code += x.code; });
  // The app/commit version whose Java code changes were analysed (same across modules); null if not requested.
  const appVersion = mods.find((m) => m.report?.appVersion)?.report?.appVersion || null;

  r.header('Release Impact Report',
    `${app ? app + '  -  ' : ''}${mods.length} module(s)  -  Release ${ver}${country ? '  -  ' + country : ''}`
      + `${appVersion ? '  -  app ' + appVersion : ''}`,
    `Generated ${generatedStamp()}`);

  // ===== Release Impact Summary =====
  r.banner('Release Impact Summary', PAL.blue);
  const band = [
    { n: tot.changed, label: 'Changed', ramp: PAL.amber },
    { n: tot.added, label: 'New', ramp: PAL.green },
    { n: mods.length, label: 'Modules', ramp: PAL.gray },
  ];
  if (appVersion) band.splice(2, 0, { n: tot.code, label: 'Code changed', ramp: PAL.purple });
  r.statBand(band);
  r.paragraph(`Release ${ver}${country ? ' in ' + country : ''} across ${mods.length} module(s): `
    + `${tot.changed} changed, ${tot.added} new, ${tot.unchanged} unchanged`
    + `${appVersion ? `. App version ${appVersion} changed shared Java classes affecting ${tot.code} API(s)` : ''}. Impact by module:`);
  r.dataTable(
    appVersion
      ? ['Module (pom artifactId)', 'Version', 'Changed', 'New', 'Code', 'Unchanged']
      : ['Module (pom artifactId)', 'Version', 'Changed', 'New', 'Unchanged'],
    rows.map((x) => appVersion
      ? [x.name + (x.error ? '  (failed)' : ''), x.error ? '—' : (x.snapshot ? 'N/A' : ver),
         x.snapshot ? '—' : x.changed, x.snapshot ? '—' : x.added, x.code, x.snapshot ? x.snap : x.unchanged]
      : [x.name + (x.error ? '  (failed)' : ''), x.error ? '—' : (x.snapshot ? 'N/A' : ver),
         x.snapshot ? '—' : x.changed, x.snapshot ? '—' : x.added, x.snapshot ? x.snap : x.unchanged]),
    appVersion
      ? ['Total', '', tot.changed, tot.added, tot.code, tot.unchanged]
      : ['Total', '', tot.changed, tot.added, tot.unchanged],
  );

  // ===== How to read =====
  r.legend('How to read this report', [
    'Each module (repo) is compared for the same release; an unversioned (N/A) module shows a latest-routes snapshot instead.',
    'Only changed, new and code-changed APIs are listed — the ones to regression-test this release. Unchanged APIs are counted in the summary above but not listed.',
    'Changed = the resolved Camel flow differs (routes, backends or service versions). New = first appears in this release.',
    ...(appVersion ? ['Code changed = a pre-existing (BAU) @Component Java class an API uses was modified by app version ' + appVersion + ' (whitespace-only changes ignored; new classes shipped with a new route are not counted), shown with the commit authors. A NEW API that changes shared code is listed under Changed. "Shared code — also re-test" lists, per route family, the current BAU route (immediate-lower, else base) plus every future/higher version — each must be tested now, as this change won\'t surface under their own version later.'] : []),
    'Under "What changed", lines marked - were removed and + were added vs the previous version.',
  ]);

  const footer = `TraceGuard - Release impact ${ver}${app ? ' - ' + app : ''}`;

  // ===== Impact by module =====
  r.banner('Impact by module', PAL.blue, 'Each module’s changed + new APIs to test (changed first). N/A modules list their latest routes.');
  for (const m of mods) {
    if (m.error) { r.section('Module — ' + m.name, 0, PAL.red, 'Not analysed: ' + m.error); continue; }
    const rep = m.report; if (!rep) continue;
    if (rep.snapshot) {
      r.section('Module — ' + m.name + '  (N/A)', rep.apis.length, PAL.amber,
        'Unversioned — the latest route each API is on (no prior release to compare).');
      if (rep.appVersion) {
        r.para(rep.codeChangeUnavailable
          ? `App version ${rep.appVersion}: source is not a git work tree — Java code-change detection skipped.`
          : `App version ${rep.appVersion}: ${rep.matchedCommits ?? 0} commit(s) tagged, ${rep.codeChangedCount ?? 0} API(s) with a shared Java class change.`,
          M, CONTENT_W, 'normal', 9, PAL.body, 12);
      }
      rep.apis.forEach((a, idx) => { if (idx > 0) r.separator(); snapshotRow(r, a); });
    } else {
      // Group by EFFECTIVE status: a NEW API that changed shared BAU code is listed under Changed (mirrors the
      // backend's New->Changed promotion), because that Java change means BAU APIs using the class need testing.
      const grouped: Record<'CHANGED' | 'NEW', ApiDiff[]> = { CHANGED: [], NEW: [] };
      rep.apis.forEach((a) => {
        if (a.status === 'SNAPSHOT') return;
        const eff = a.status === 'NEW' && a.codeChanged ? 'CHANGED' : a.status;
        if (eff === 'CHANGED') grouped.CHANGED.push(a);
        else if (eff === 'NEW') grouped.NEW.push(a);
        // UNCHANGED is not listed (BAU, no flow change — deliberately no code-change noise).
      });
      const listedCount = rep.changedCount + rep.newCount;   // backend counts already reflect the promotion
      const blurb = rep.unchangedCount > 0
        ? `${rep.unchangedCount} unchanged API(s) not listed — this report focuses on what to test.`
        : '';
      r.section('Module — ' + m.name, listedCount, PAL.blue, blurb);
      if (rep.appVersion) {
        r.para(rep.codeChangeUnavailable
          ? `App version ${rep.appVersion}: source is not a git work tree — Java code-change detection skipped.`
          : `App version ${rep.appVersion}: ${rep.matchedCommits ?? 0} commit(s) tagged, ${rep.codeChangedCount ?? 0} API(s) with a shared Java class change.`,
          M, CONTENT_W, 'normal', 9, PAL.body, 12);
      }
      if (listedCount === 0) {
        r.emptyNote(`Nothing to test in this module — no changed or new APIs`
          + (rep.unchangedCount > 0 ? ` (${rep.unchangedCount} unchanged).` : '.'));
      } else {
        for (const status of LISTED_STATUSES) {
          const list = grouped[status];
          if (!list.length) continue;
          const meta = sectionMeta(status);
          r.groupHead(meta.title, list.length, meta.ramp);
          list.forEach((a, idx) => { if (idx > 0) r.separator(); apiBlock(r, a, a.status as DiffStatus); });
        }
      }
    }
    r.reviewSection(rep.needsReview);
  }
  r.save(file(ver), footer);
}

/** One row of a latest-routes snapshot (an unversioned/N/A module). */
function snapshotRow(r: ReportDoc, a: ApiDiff) {
  r.ensure(30);
  const pathW = r.text(a.api, M, 'bold', 11, PAL.ink);
  r.text(a.operation, M + pathW + 8, 'normal', 9, PAL.muted);
  const base = a.targetVersion === 'BASE';
  const label = base ? 'N/A' : 'Release ' + a.targetVersion;
  const vw = r.width(label, 'bold', 8) + 12;
  r.pill(label, PAGE.w - M - vw, base ? PAL.amber.fill : PAL.blue.fill, base ? PAL.amber.text : PAL.blue.text, 8);
  r.y += 16;
  r.para(`Route: ${a.targetRoute}.`, M, CONTENT_W, 'normal', 9, PAL.body, 12);
  const summarize = (label: string, names: string[], col: typeof PAL.amber.text) => {
    if (names.length) r.para(`${label}: ${names.join(', ')}`, M + 4, CONTENT_W - 4, 'normal', 9, col, 12);
  };
  codeChangeLines(r, a, summarize);
  r.y += 6;
}

/** Render the app-version code-change causes for one API (shared by diff + snapshot rows). */
function codeChangeLines(r: ReportDoc, a: ApiDiff,
                         summarize: (label: string, names: string[], col: typeof PAL.amber.text) => void) {
  if (!a.codeChanged) return;
  summarize('Code changed - shared classes', a.changedClasses || [], PAL.purple.text);
  const impacted = a.impactedRoutes || [];
  (['Current', 'BAU', 'Future'] as const).forEach((cat) => {
    const routes = impacted.filter((r) => r.category === cat).map((r) => r.route);
    if (routes.length) {
      r.para(`Also re-test (${cat}): ${routes.join(', ')}`, M + 4, CONTENT_W - 4, 'normal', 9, PAL.amber.text, 12);
    }
  });
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
  codeChangeLines(r, a, summarize);

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

function file(ver: string): string {
  const v = (ver || 'base').replace(/[^a-zA-Z0-9._-]+/g, '-');   // N/A -> N-A (no path chars)
  return `release-impact-${v}-${stamp()}.pdf`;
}
