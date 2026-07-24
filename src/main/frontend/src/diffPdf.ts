import type { ApiDiff, ApiLogResult, DiffStatus, VersionDiffReport } from './types';
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

/** One module's version-diff for the report (or an error), optionally enriched with a per-version test log + remarks. */
export interface ModuleDiff { name: string; report: VersionDiffReport | null; error?: string; logByVer?: Record<string, Record<string, ApiLogResult>>; remarks?: Record<string, string>; }

const ROUTE_VER_PDF = /^R(\d+(?:\.\d+)*)_/;
function normVerPdf(v?: string | null): string { return v && v !== 'N/A' && v !== 'BASE' ? v : 'BASE'; }
/** The version an impacted route belongs to (from its route id), for its own log lookup. */
function routeVerPdf(routePath: string[]): string {
  for (const rid of routePath) { const m = ROUTE_VER_PDF.exec(rid); if (m) return m[1]; }
  return 'BASE';
}
/** A row's log result, looked up by version + api in a module's per-version map. */
function logAt(logByVer: Record<string, Record<string, ApiLogResult>> | undefined, version: string | null | undefined, api?: string | null): ApiLogResult | undefined {
  return api ? logByVer?.[normVerPdf(version)]?.[api] : undefined;
}

/** A short tested-status label for the PDF (mirrors the UI badge), or null when no log covers this API. */
function testedTag(l?: ApiLogResult): { label: string; ramp: Ramp } | null {
  if (!l) return null;
  if (!l.tested) return { label: 'Not tested', ramp: PAL.gray };
  if (l.status === 'SUCCESS') return { label: 'Tested - passed', ramp: PAL.green };
  if (l.status === 'FAILED' || l.status === 'TIMEOUT') return { label: 'Tested - FAILED', ramp: PAL.red };
  if (l.status === 'PARTIAL') return { label: 'Tested - partial', ramp: PAL.amber };
  return { label: 'Ran (unclear)', ramp: PAL.gray };
}

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
      high: rep?.highRiskCount ?? 0, bc: rep?.backwardCompatCount ?? 0,
      snap: rep?.snapshotCount ?? (rep?.snapshot ? rep.apis.length : 0) };
  });
  const tot = { changed: 0, added: 0, unchanged: 0, code: 0, high: 0, bc: 0 };
  rows.forEach((x) => { tot.changed += x.changed; tot.added += x.added; tot.unchanged += x.unchanged; tot.code += x.code; tot.high += x.high; tot.bc += x.bc; });
  // The app/commit version whose Java code changes were analysed (same across modules); null if not requested.
  const appVersion = mods.find((m) => m.report?.appVersion)?.report?.appVersion || null;

  // ===== Title page =====
  r.titlePage('Release Impact Report',
    `${app ? app + '  ·  ' : ''}Release ${ver}${country ? '  ·  ' + country : ''}${appVersion ? '  ·  app ' + appVersion : ''}`,
    [`Generated ${generatedStamp()}`, `${mods.length} module(s)`,
      `${tot.changed + tot.added} API(s) to test  ·  ${tot.high} high-risk`]);

  // ===== Release Impact Summary =====
  r.bookmark('Release Impact Summary');
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
  if (tot.high || tot.bc) {
    r.para(`Test priority: ${tot.high} high-risk API(s) to focus on first`
      + `${tot.bc ? `, ${tot.bc} require backward compatibility (a payload field was removed/renamed)` : ''}.`,
      M, CONTENT_W, 'bold', 9.5, tot.high ? PAL.red.text : PAL.amber.text, 13);
    r.y += 4;
  }
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
    ...(appVersion ? ['Code changed = a pre-existing (BAU) @Component Java class an API uses was modified by app version(s) ' + appVersion + ' (matched exactly; whitespace-only changes ignored; new classes shipped with a new route are not counted), shown with the version(s) that changed each class and the commit authors. A NEW API that changes shared code is listed under Changed. "Shared code — also re-test" lists, per route family, the current BAU route (immediate-lower, else base) plus every future/higher version — each must be tested now, as this change won\'t surface under their own version later.'] : []),
    'Under "What changed", lines marked - were removed and + were added vs the previous version.',
  ]);

  const footer = `TraceGuard - Release impact ${ver}${app ? ' - ' + app : ''}`;

  // ===== Backward compatibility (executive callout) =====
  // BC is required when a payload field was removed OR a shared class changed — both force a re-test of the
  // older app version against this release.
  const bcItems = mods.flatMap((m) => (m.report?.apis ?? [])
    .filter((a) => (a.payloadChange?.removedKeys?.length ?? 0) > 0 || a.codeChanged)
    .map((a) => {
      const reasons: string[] = [];
      if (a.payloadChange?.removedKeys?.length) reasons.push('removed field(s): ' + a.payloadChange.removedKeys.join(', '));
      if (a.codeChanged) reasons.push('shared class changed - regression-test the older (BAU) version');
      return { module: m.name, api: a.api, reason: reasons.join('; ') };
    }));
  if (bcItems.length) {
    r.bookmark('Backward compatibility');
    r.section('Backward compatibility required', bcItems.length, PAL.red,
      'These APIs need the previous version re-verified against this release: a request field was removed/renamed (the backend must still accept old clients), and/or a shared class they use was changed (the older version\'s flow must be regression-tested).');
    r.wrapTable(
      [{ header: 'API', w: 0.32, mono: true }, { header: 'Module', w: 0.22 }, { header: 'Why', w: 0.46 }],
      bcItems.map((it) => [
        { text: it.api, mono: true, color: PAL.ink },
        it.module,
        { text: it.reason, color: PAL.delText },
      ]));
  }

  // ===== Impact by module =====
  r.bookmark('Impact by module');
  r.banner('Impact by module', PAL.blue, 'Each module’s changed + new APIs to test (changed first). N/A modules list their latest routes.');
  for (const m of mods) {
    r.bookmark('Module — ' + m.name);
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
      // Test-log coverage line (only when a log was merged for this module).
      if (m.logByVer) {
        const toTest = [...grouped.CHANGED, ...grouped.NEW];
        let passed = 0, covered = 0;
        for (const a of toTest) { const l = logAt(m.logByVer, a.targetVersion, a.api); if (l?.tested) { covered++; if (l.status === 'SUCCESS') passed++; } }
        r.para(`Test coverage: ${passed} of ${toTest.length} to-test API(s) executed & passed`
          + `${covered - passed > 0 ? `, ${covered - passed} executed with failures/partial` : ''}`
          + `, ${toTest.length - covered} not tested.`,
          M, CONTENT_W, 'bold', 9.5, passed === toTest.length && toTest.length > 0 ? PAL.green.text : PAL.amber.text, 13);
        r.y += 3;
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
          list.forEach((a, idx) => { if (idx > 0) r.separator(); apiBlock(r, a, a.status as DiffStatus, m.logByVer, m.remarks?.[`${a.api}|${a.operation}`]); });
        }
      }
    }
    r.reviewSection(rep.needsReview);
  }

  // ===== Who changed what (author appendix) =====
  // Parse the commit authors off each changed-class label ("bean (File.java) — Alice, Bob") and map
  // author -> the APIs their change affects, so verification can be assigned.
  const byAuthor = new Map<string, Set<string>>();
  for (const m of mods) {
    for (const a of m.report?.apis ?? []) {
      if (!a.codeChanged) continue;
      for (const label of a.changedClasses ?? []) {
        const parts = label.split(/\s[—–-]\s/);
        if (parts.length < 2) continue;
        for (const au of parts[parts.length - 1].split(',').map((s) => s.trim()).filter(Boolean)) {
          if (!byAuthor.has(au)) byAuthor.set(au, new Set());
          byAuthor.get(au)!.add(a.api);
        }
      }
    }
  }
  if (byAuthor.size) {
    r.bookmark('Who changed what');
    r.section('Who changed what', byAuthor.size, PAL.gray,
      'Commit authors of the shared Java classes this release changed, and the APIs their change affects — for assigning verification / who to ask.');
    r.wrapTable(
      [{ header: 'Author', w: 0.30 }, { header: 'Affects APIs', w: 0.70, mono: true }],
      [...byAuthor.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([au, apis]) => [
        au, { text: [...apis].sort().join(', '), mono: true, color: PAL.body },
      ]));
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
  codeChangeLines(r, a);
  r.y += 6;
}

/** Render the app-version code-change causes for one API (shared by diff + snapshot rows). */
function codeChangeLines(r: ReportDoc, a: ApiDiff, logByVer?: Record<string, Record<string, ApiLogResult>>) {
  if (!a.codeChanged) return;
  // Changed shared classes, one per line (label + commit authors), so it doesn't run together.
  (a.changedClasses || []).forEach((c, i) => {
    r.para((i === 0 ? 'Code changed - shared classes:   ' : '') + c,
      M + 4, CONTENT_W - 4, i === 0 ? 'bold' : 'normal', 9, PAL.purple.text, 12);
  });
  const impacted = a.impactedRoutes || [];
  if (!impacted.length) return;

  // "Also re-test" as a scannable table: Group | API | Route chain [| Tested], each cell wraps in its column.
  const GROUPS = [
    { key: 'Current', ramp: PAL.purple },
    { key: 'BAU', ramp: PAL.gray },
    { key: 'Future', ramp: PAL.orange },
    { key: 'Unknown', ramp: PAL.red },
  ] as const;
  const groupOf = (rt: { api?: string | null; category: string }) => (rt.api ? rt.category : 'Unknown');
  const withTested = !!logByVer;
  const rows = GROUPS.flatMap((g) =>
    impacted.filter((rt) => groupOf(rt) === g.key).map((rt) => {
      const row: (string | { pill: { label: string; fill: [number, number, number]; text: [number, number, number]; stripe: [number, number, number] } } | { text: string; mono?: boolean; color?: [number, number, number] })[] = [
        { pill: { label: g.key, fill: g.ramp.fill, text: g.ramp.text, stripe: g.ramp.text } },
        { text: rt.api || '-', mono: true, color: PAL.blue.text },
        { text: rt.routePath.join('  ->  '), mono: true, color: PAL.body },
      ];
      if (withTested) {
        const tt = testedTag(logAt(logByVer, routeVerPdf(rt.routePath), rt.api));
        row.push({ text: tt ? tt.label : '-', color: tt ? tt.ramp.text : PAL.muted });
      }
      return row;
    }));
  r.para('Shared code - also re-test (Current = this release, BAU = live, Future = upcoming, Unknown = trace manually):',
    M + 4, CONTENT_W - 4, 'bold', 9, PAL.amber.text, 12);
  r.wrapTable(
    withTested
      ? [{ header: 'Group', w: 0.15 }, { header: 'API', w: 0.24, mono: true }, { header: 'Route chain', w: 0.40, mono: true }, { header: 'Tested', w: 0.21 }]
      : [{ header: 'Group', w: 0.17 }, { header: 'API', w: 0.30, mono: true }, { header: 'Route chain', w: 0.53, mono: true }],
    rows);
}

function apiBlock(r: ReportDoc, a: ApiDiff, status: DiffStatus, logByVer?: Record<string, Record<string, ApiLogResult>>, remark?: string) {
  const log = logAt(logByVer, a.targetVersion, a.api);
  r.ensure(40);
  const pathW = r.text(a.api, M, 'bold', 11, PAL.ink);
  r.text(a.operation, M + pathW + 8, 'normal', 9, PAL.muted);
  // Right-aligned test-priority pill (leftmost signal on the row), with the version pill to its left.
  const riskRamp = a.risk === 'High' ? PAL.red : a.risk === 'Medium' ? PAL.amber : PAL.gray;
  const riskLabel = `${a.risk || 'Low'} risk`;
  const riskW = r.width(riskLabel, 'bold', 8) + 12;
  r.pill(riskLabel, PAGE.w - M - riskW, riskRamp.fill, riskRamp.text, 8);
  if (a.lowerVersion && (status === 'CHANGED' || (status === 'UNCHANGED' && !a.note))) {
    const vt = `${a.lowerVersion} -> ${a.targetVersion}`;
    const vw = r.width(vt, 'bold', 8) + 12;
    r.pill(vt, PAGE.w - M - riskW - 8 - vw, PAL.gray.fill, PAL.gray.text, 8);
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

  // Merged test-log evidence for this API (executed / passed / failed), when a log was uploaded.
  const tt = testedTag(log);
  if (tt && log) {
    r.para(`Test log: ${tt.label}${log.tested ? ` — executed ${log.attempts}x, ${log.successCount} passed, ${log.failureCount} failed` : ''}.`,
      M, CONTENT_W, 'bold', 9, tt.ramp.text, 12);
  }

  const summarize = (label: string, names: string[], col: typeof PAL.amber.text) => {
    if (names.length) r.para(`${label}: ${names.join(', ')}`, M + 4, CONTENT_W - 4, 'normal', 9, col, 12);
  };
  summarize('Edited routes', (a.routeDiffs || []).map((rd) => rd.routeBase), PAL.amber.text);
  summarize('Added routes', a.addedRoutes || [], PAL.addText);
  summarize('Removed routes', a.removedRoutes || [], PAL.delText);
  (a.backendVersionChanges || []).forEach((s) =>
    summarize('Backend service version', [`${s.backend}  ${s.fromVersion} -> ${s.toVersion}`], PAL.amber.text));
  codeChangeLines(r, a, logByVer);

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
    const removed = a.payloadChange.removedKeys.length;
    r.para(removed > 0
      ? `Backward compatibility required - ${removed} field(s) removed`
      : 'Backward compatible - fields added only',
      M + 4, CONTENT_W - 4, 'normal', 9, removed > 0 ? PAL.delText : PAL.addText, 12);
  }
  // Tester's manual remark (e.g. why an impacted API wasn't/can't be retested).
  if (remark) {
    r.para(`Remark: ${remark}`, M + 4, CONTENT_W - 4, 'bold', 9, PAL.accent, 12);
  }
  r.y += 6;
}

function file(ver: string): string {
  const v = (ver || 'base').replace(/[^a-zA-Z0-9._-]+/g, '-');   // N/A -> N-A (no path chars)
  return `release-impact-${v}-${stamp()}.pdf`;
}
