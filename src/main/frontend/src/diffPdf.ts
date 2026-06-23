import type { ApiDiff, VersionDiffReport } from './types';

// A4 in points (jsPDF unit: 'pt').
const PAGE = { w: 595.28, h: 841.89 };
const M = 40;                       // page margin
const CONTENT_W = PAGE.w - 2 * M;

type RGB = [number, number, number];
const C = {
  dark: [40, 48, 60] as RGB,
  muted: [122, 135, 148] as RGB,
  rule: [225, 231, 239] as RGB,
  changedFill: [254, 243, 199] as RGB, changedText: [146, 64, 14] as RGB,
  newFill: [220, 252, 231] as RGB, newText: [22, 101, 52] as RGB,
  unchFill: [241, 245, 249] as RGB, unchText: [100, 116, 139] as RGB,
  addFill: [240, 253, 244] as RGB, addText: [22, 101, 52] as RGB,
  delFill: [254, 242, 242] as RGB, delText: [153, 27, 27] as RGB,
  verFill: [241, 245, 249] as RGB, verText: [51, 65, 85] as RGB,
};

// jsPDF's built-in fonts are Latin-1 only — map the UI's unicode glyphs to ASCII
// so arrows/marks render instead of dropping out.
function ascii(s: string): string {
  return (s || '')
    .replace(/→/g, '->').replace(/[⟵←]/g, '<-').replace(/[—–]/g, '-')
    .replace(/✎/g, '~').replace(/−/g, '-')
    .replace(/[^\x00-\xff]/g, '');
}

function statusStyle(s: ApiDiff['status']) {
  if (s === 'CHANGED') return { fill: C.changedFill, text: C.changedText, label: 'Changed' };
  if (s === 'NEW') return { fill: C.newFill, text: C.newText, label: 'New' };
  return { fill: C.unchFill, text: C.unchText, label: 'No change' };
}

/** Render the (filtered) report to a downloadable PDF. jsPDF is loaded on demand. */
export async function exportDiffPdf(report: VersionDiffReport, apis: ApiDiff[], filtered: boolean) {
  const { jsPDF } = await import('jspdf');
  const doc = new jsPDF({ unit: 'pt', format: 'a4' });
  let y = M;

  const ensure = (h: number) => { if (y + h > PAGE.h - M) { doc.addPage(); y = M; } };
  const setText = (c: RGB) => doc.setTextColor(c[0], c[1], c[2]);
  const setFill = (c: RGB) => doc.setFillColor(c[0], c[1], c[2]);

  const chip = (text: string, fill: RGB, txt: RGB, x: number, yy: number, size = 8): number => {
    doc.setFont('helvetica', 'bold'); doc.setFontSize(size);
    const t = ascii(text);
    const w = doc.getTextWidth(t) + 10;
    const h = size + 6;
    setFill(fill); doc.roundedRect(x, yy - size, w, h, 3, 3, 'F');
    setText(txt); doc.text(t, x + 5, yy);
    return w;
  };

  // --- header ---
  doc.setFont('helvetica', 'bold'); doc.setFontSize(16); setText(C.dark);
  doc.text(ascii(`Release diff - ${report.version || 'BASE'}${report.country ? ` (${report.country})` : ''}`), M, y);
  y += 18;
  doc.setFont('helvetica', 'normal'); doc.setFontSize(10); setText(C.muted);
  const stamp = new Date().toLocaleString();
  doc.text(ascii(`${report.changedCount} changed - ${report.newCount} new - ${report.unchangedCount} unchanged`
    + `${filtered ? ` - ${apis.length} shown` : ''} - generated ${stamp}`), M, y);
  y += 12;
  doc.setDrawColor(C.rule[0], C.rule[1], C.rule[2]); doc.line(M, y, PAGE.w - M, y); y += 18;

  if (apis.length === 0) {
    doc.setFont('helvetica', 'normal'); doc.setFontSize(11); setText(C.muted);
    doc.text('No APIs in the current view.', M, y);
    doc.save(`release-diff-${report.version || 'base'}.pdf`);
    return;
  }

  // --- one block per API ---
  for (const a of apis) {
    ensure(54);
    const st = statusStyle(a.status);
    const bw = chip(st.label, st.fill, st.text, M, y, 8);

    doc.setFont('helvetica', 'bold'); doc.setFontSize(11); setText(C.dark);
    const apiX = M + bw + 8;
    doc.text(ascii(a.api), apiX, y);
    const apiW = doc.getTextWidth(ascii(a.api));   // measured while still in bold-11
    doc.setFont('helvetica', 'normal'); doc.setFontSize(9); setText(C.muted);
    doc.text(ascii(a.operation), apiX + apiW + 6, y);

    // version pill, right-aligned
    if (a.lowerVersion && (a.status === 'CHANGED' || (a.status === 'UNCHANGED' && !a.note))) {
      const vt = `${a.lowerVersion} -> ${a.targetVersion}`;
      doc.setFont('helvetica', 'bold'); doc.setFontSize(8);
      const vw = doc.getTextWidth(vt) + 10;
      chip(vt, C.verFill, C.verText, PAGE.w - M - vw, y, 8);
    }
    y += 15;

    // verdict line
    doc.setFont('helvetica', 'normal'); doc.setFontSize(9);
    if (a.status === 'NEW') {
      setText(C.muted);
      doc.text(ascii(`Added in ${a.targetVersion} - no earlier version. ${a.targetRoute}`), M, y);
    } else if (a.note) {
      setText(C.muted); doc.text(ascii(a.note), M, y);
    } else {
      setText(C.dark); doc.text(ascii(`${a.targetRoute}  <-  ${a.lowerRoute}`), M, y);
    }
    y += 14;

    // change summary — wrapped colored text (robust against jsPDF width quirks)
    const summarize = (label: string, names: string[], col: RGB) => {
      if (!names.length) return;
      doc.setFont('helvetica', 'normal'); doc.setFontSize(9); setText(col);
      const wrapped: string[] = doc.splitTextToSize(ascii(`${label}: ${names.join(', ')}`), CONTENT_W);
      for (const wl of wrapped) { ensure(12); doc.text(wl, M, y); y += 12; }
    };
    summarize('Edited routes', (a.routeDiffs || []).map((rd) => rd.routeBase), C.changedText);
    summarize('Added routes', a.addedRoutes || [], C.addText);
    summarize('Removed routes', a.removedRoutes || [], C.delText);
    if ((a.routeDiffs?.length || a.addedRoutes?.length || a.removedRoutes?.length)) y += 3;

    // backend service-version changes
    (a.backendVersionChanges || []).forEach((s) => {
      ensure(13);
      doc.setFont('helvetica', 'normal'); doc.setFontSize(9); setText(C.muted);
      doc.text(ascii(`backend svc: ${s.backend}  ${s.fromVersion} -> ${s.toVersion}`), M, y);
      y += 13;
    });

    // element-level route diffs
    (a.routeDiffs || []).forEach((rd) => {
      ensure(18);
      doc.setFont('helvetica', 'bold'); doc.setFontSize(9); setText(C.dark);
      doc.text(ascii(`${rd.routeBase}  (+${rd.added.length} -${rd.removed.length})`), M, y);
      y += 12;
      doc.setFont('courier', 'normal'); doc.setFontSize(8);
      const renderLine = (txt: string, fill: RGB, col: RGB, sym: string) => {
        const wrapped: string[] = doc.splitTextToSize(ascii(sym + ' ' + txt), CONTENT_W - 8);
        for (const wl of wrapped) {
          ensure(11);
          setFill(fill); doc.rect(M, y - 7.5, CONTENT_W, 10, 'F');
          setText(col); doc.text(wl, M + 4, y);
          y += 10.5;
        }
      };
      rd.removed.forEach((l) => renderLine(l, C.delFill, C.delText, '-'));
      rd.added.forEach((l) => renderLine(l, C.addFill, C.addText, '+'));
      y += 4;
    });

    ensure(12);
    doc.setDrawColor(C.rule[0], C.rule[1], C.rule[2]); doc.line(M, y, PAGE.w - M, y); y += 16;
  }

  doc.save(`release-diff-${report.version || 'base'}.pdf`);
}
