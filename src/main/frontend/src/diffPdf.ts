import type { ApiDiff, DiffStatus, VersionDiffReport } from './types';

// A4 in points (jsPDF unit: 'pt').
const PAGE = { w: 595.28, h: 841.89 };
const M = 44;                       // page margin
const CONTENT_W = PAGE.w - 2 * M;
const BOTTOM = PAGE.h - M;          // content floor (footer sits below, in the margin)

type RGB = [number, number, number];
const C = {
  ink: [30, 41, 59] as RGB,
  body: [51, 65, 85] as RGB,
  muted: [122, 135, 148] as RGB,
  rule: [226, 232, 240] as RGB,
  accent: [37, 99, 235] as RGB,
  changedFill: [254, 243, 199] as RGB, changedBar: [253, 230, 138] as RGB, changedText: [146, 64, 14] as RGB,
  newFill: [220, 252, 231] as RGB, newBar: [187, 247, 208] as RGB, newText: [22, 101, 52] as RGB,
  unchFill: [241, 245, 249] as RGB, unchBar: [226, 232, 240] as RGB, unchText: [71, 85, 105] as RGB,
  addFill: [240, 253, 244] as RGB, addText: [22, 101, 52] as RGB,
  delFill: [254, 242, 242] as RGB, delText: [153, 27, 27] as RGB,
  verFill: [241, 245, 249] as RGB, verText: [51, 65, 85] as RGB,
};

// jsPDF's built-in fonts are Latin-1 only — map the UI's unicode glyphs to ASCII.
function ascii(s: string): string {
  return (s || '')
    .replace(/→/g, '->').replace(/[⟵←]/g, '<-').replace(/[—–]/g, '-')
    .replace(/✎/g, '~').replace(/−/g, '-')
    .replace(/[^\x00-\xff]/g, '');
}

const STATUS_ORDER: DiffStatus[] = ['CHANGED', 'NEW', 'UNCHANGED'];
function sectionMeta(s: DiffStatus) {
  if (s === 'CHANGED') return { title: 'Changed APIs', bar: C.changedBar, text: C.changedText,
    blurb: 'Existing APIs whose Camel flow differs from the previous version — review and regression-test these.' };
  if (s === 'NEW') return { title: 'New APIs', bar: C.newBar, text: C.newText,
    blurb: 'Introduced in this release with no earlier version — net-new functionality to test end to end.' };
  return { title: 'Unchanged APIs', bar: C.unchBar, text: C.unchText,
    blurb: 'A version bump with no behavioural change, or APIs this release did not touch.' };
}

/** Render the (filtered) report to a downloadable PDF. jsPDF is loaded on demand. */
export async function exportDiffPdf(report: VersionDiffReport, apis: ApiDiff[], filtered: boolean, app?: string) {
  const { jsPDF } = await import('jspdf');
  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  let y = M;

  const setText = (c: RGB) => doc.setTextColor(c[0], c[1], c[2]);
  const setFill = (c: RGB) => doc.setFillColor(c[0], c[1], c[2]);
  const setDraw = (c: RGB) => doc.setDrawColor(c[0], c[1], c[2]);
  const newPage = () => { doc.addPage(); y = M; };
  const ensure = (h: number) => { if (y + h > BOTTOM) newPage(); };

  const text = (s: string, x: number, yy: number, font: 'normal' | 'bold' = 'normal', size = 9, col: RGB = C.body) => {
    doc.setFont('helvetica', font); doc.setFontSize(size); setText(col);
    doc.text(ascii(s), x, yy);
  };
  // A pill sized to its text. Returns its width.
  const pill = (s: string, x: number, yy: number, fill: RGB, col: RGB, size = 8): number => {
    doc.setFont('helvetica', 'bold'); doc.setFontSize(size);
    const t = ascii(s); const w = doc.getTextWidth(t) + 12; const h = size + 7;
    setFill(fill); doc.roundedRect(x, yy - size - 1, w, h, 3, 3, 'F');
    setText(col); doc.text(t, x + 6, yy);
    return w;
  };
  // Wrapped paragraph; advances y.
  const para = (s: string, x: number, width: number, font: 'normal' | 'bold', size: number, col: RGB, lh: number) => {
    doc.setFont('helvetica', font); doc.setFontSize(size); setText(col);
    for (const ln of doc.splitTextToSize(ascii(s), width) as string[]) { ensure(lh); doc.text(ln, x, y); y += lh; }
  };

  // ---------------- header ----------------
  text('Release Diff Report', M, y, 'bold', 20, C.ink); y += 22;
  const sub = `${app ? app + '  ·  ' : ''}Release ${report.version || 'BASE'}${report.country ? '  ·  ' + report.country : ''}`;
  text(sub, M, y, 'normal', 11, C.accent); y += 16;
  text(`Each API is compared against its immediately-preceding version. Generated ${new Date().toLocaleString()}.`,
    M, y, 'normal', 9, C.muted); y += 16;
  setDraw(C.rule); doc.line(M, y, PAGE.w - M, y); y += 18;

  // ---------------- executive summary (stat band) ----------------
  const stats: { n: number; label: string; fill: RGB; col: RGB }[] = [
    { n: report.changedCount, label: 'Changed', fill: C.changedFill, col: C.changedText },
    { n: report.newCount, label: 'New', fill: C.newFill, col: C.newText },
    { n: report.unchangedCount, label: 'Unchanged', fill: C.unchFill, col: C.unchText },
  ];
  const gap = 12; const boxW = (CONTENT_W - 2 * gap) / 3; const boxH = 50;
  ensure(boxH + 6);
  stats.forEach((st, i) => {
    const x = M + i * (boxW + gap);
    setFill(st.fill); doc.roundedRect(x, y, boxW, boxH, 6, 6, 'F');
    text(String(st.n), x + 12, y + 30, 'bold', 22, st.col);
    text(st.label, x + 12, y + 44, 'bold', 9, st.col);
  });
  y += boxH + 14;

  para(`Release ${report.version || 'BASE'} changes ${report.changedCount} existing API(s) and introduces `
    + `${report.newCount} new API(s) relative to the previous version; ${report.unchangedCount} are unaffected.`
    + (filtered ? `  Filtered view: ${apis.length} of ${report.apis.length} API(s) shown.` : ''),
    M, CONTENT_W, 'normal', 10, C.body, 14);
  y += 4;

  // ---------------- legend ----------------
  ensure(60);
  text('How to read this report', M, y, 'bold', 10, C.ink); y += 14;
  const legend = [
    'Changed = the resolved Camel flow differs (routes, backends or service versions).',
    'New = first appears in this release; Unchanged = version bump with no behavioural change.',
    'Under "What changed", lines marked - were removed and + were added vs the previous version.',
    'svc = the backend service version sent to the host (read from the request template).',
  ];
  legend.forEach((l) => { para('•  ' + l, M, CONTENT_W, 'normal', 9, C.muted, 12); });
  y += 6;

  if (apis.length === 0) {
    setDraw(C.rule); doc.line(M, y, PAGE.w - M, y); y += 16;
    text('No APIs in the current view.', M, y, 'normal', 11, C.muted);
    finish(doc, report, app);
    doc.save(fileName(report));
    return;
  }

  // ---------------- sections ----------------
  const grouped: Record<DiffStatus, ApiDiff[]> = { CHANGED: [], NEW: [], UNCHANGED: [] };
  apis.forEach((a) => grouped[a.status].push(a));

  for (const status of STATUS_ORDER) {
    const list = grouped[status];
    if (!list.length) continue;
    const meta = sectionMeta(status);

    // section banner — keep it with at least one entry below it
    ensure(78);
    y += 6;
    setFill(meta.bar); doc.roundedRect(M, y, CONTENT_W, 26, 5, 5, 'F');
    text(`${meta.title}  (${list.length})`, M + 12, y + 17, 'bold', 12, meta.text);
    y += 26 + 8;
    para(meta.blurb, M, CONTENT_W, 'normal', 9, C.muted, 12);
    y += 4;

    list.forEach((a, idx) => {
      if (idx > 0) { ensure(14); setDraw(C.rule); doc.line(M, y, PAGE.w - M, y); y += 12; }
      apiBlock(a, status);
    });
  }

  finish(doc, report, app);
  doc.save(fileName(report));

  // ---------------- per-API block ----------------
  function apiBlock(a: ApiDiff, status: DiffStatus) {
    ensure(40);
    // header: path + operation, with a right-aligned version pill (changed only)
    text(a.api, M, y, 'bold', 11, C.ink);
    const pathW = (doc.setFont('helvetica', 'bold'), doc.setFontSize(11), doc.getTextWidth(ascii(a.api)));
    text(a.operation, M + pathW + 8, y, 'normal', 9, C.muted);
    if (a.lowerVersion && (status === 'CHANGED' || (status === 'UNCHANGED' && !a.note))) {
      const vt = `${a.lowerVersion} -> ${a.targetVersion}`;
      doc.setFont('helvetica', 'bold'); doc.setFontSize(8);
      const vw = doc.getTextWidth(ascii(vt)) + 12;
      pill(vt, PAGE.w - M - vw, y, C.verFill, C.verText, 8);
    }
    y += 16;

    // one-line plain-English verdict
    if (status === 'NEW') {
      para(`Introduced in ${a.targetVersion}. Entry route ${a.targetRoute}. No earlier version to compare against.`,
        M, CONTENT_W, 'normal', 9, C.body, 12);
    } else if (a.note) {
      para(a.note, M, CONTENT_W, 'normal', 9, C.body, 12);
    } else {
      para(`Resolves to ${a.targetRoute}, compared against ${a.lowerRoute}.`, M, CONTENT_W, 'normal', 9, C.body, 12);
    }

    // change summary (wrapped, coloured by kind)
    const summarize = (label: string, names: string[], col: RGB) => {
      if (!names.length) return;
      para(`${label}: ${names.join(', ')}`, M + 4, CONTENT_W - 4, 'normal', 9, col, 12);
    };
    summarize('Edited routes', (a.routeDiffs || []).map((rd) => rd.routeBase), C.changedText);
    summarize('Added routes', a.addedRoutes || [], C.addText);
    summarize('Removed routes', a.removedRoutes || [], C.delText);
    (a.backendVersionChanges || []).forEach((s) =>
      summarize('Backend service version', [`${s.backend}  ${s.fromVersion} -> ${s.toVersion}`], C.changedText));

    // element-level diff
    (a.routeDiffs || []).forEach((rd) => {
      ensure(18);
      text(`${rd.routeBase}   (+${rd.added.length}  -${rd.removed.length})`, M + 4, y, 'bold', 9, C.ink); y += 12;
      doc.setFont('courier', 'normal'); doc.setFontSize(8);
      const line = (txt: string, fill: RGB, col: RGB, sym: string) => {
        for (const wl of doc.splitTextToSize(ascii(sym + ' ' + txt), CONTENT_W - 12) as string[]) {
          ensure(11);
          setFill(fill); doc.rect(M + 4, y - 7.5, CONTENT_W - 4, 10, 'F');
          setText(col); doc.setFont('courier', 'normal'); doc.setFontSize(8); doc.text(wl, M + 8, y);
          y += 10.5;
        }
      };
      rd.removed.forEach((l) => line(l, C.delFill, C.delText, '-'));
      rd.added.forEach((l) => line(l, C.addFill, C.addText, '+'));
      y += 3;
    });
    y += 6;
  }
}

function fileName(report: VersionDiffReport): string {
  return `release-diff-${report.version || 'base'}.pdf`;
}

// Footer on every page: report identity (left) + page x of y (right).
function finish(doc: any, report: VersionDiffReport, app?: string) {
  const total = doc.getNumberOfPages();
  for (let i = 1; i <= total; i++) {
    doc.setPage(i);
    doc.setFont('helvetica', 'normal'); doc.setFontSize(8);
    doc.setTextColor(C.muted[0], C.muted[1], C.muted[2]);
    const left = ascii(`TraceGuard · Release diff ${report.version || 'BASE'}${app ? ' · ' + app : ''}`);
    doc.text(left, M, PAGE.h - 24);
    const right = `Page ${i} of ${total}`;
    doc.text(right, PAGE.w - M - doc.getTextWidth(right), PAGE.h - 24);
  }
}
