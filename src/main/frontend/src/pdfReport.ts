// Shared PDF report kit — the common design language (cover header, summary stat
// band, section banners, legend, coloured diff/lines, page footers) used by every
// tab's "Export PDF" so the reports look and read the same. jsPDF is loaded on demand.
import { TG_LOGO } from './tgLogo';

export type RGB = [number, number, number];
export interface Ramp { fill: RGB; bar: RGB; text: RGB }
const ramp = (fill: RGB, bar: RGB, text: RGB): Ramp => ({ fill, bar, text });

export const PAGE = { w: 595.28, h: 841.89 };
export const M = 44;
export const CONTENT_W = PAGE.w - 2 * M;
const BOTTOM = PAGE.h - M;

export const PAL = {
  ink: [30, 41, 59] as RGB,
  body: [51, 65, 85] as RGB,
  muted: [122, 135, 148] as RGB,
  rule: [226, 232, 240] as RGB,
  accent: [37, 99, 235] as RGB,
  amber: ramp([254, 243, 199], [253, 230, 138], [146, 64, 14]),
  green: ramp([220, 252, 231], [187, 247, 208], [22, 101, 52]),
  red: ramp([254, 242, 242], [254, 202, 202], [153, 27, 27]),
  gray: ramp([241, 245, 249], [226, 232, 240], [71, 85, 105]),
  blue: ramp([219, 234, 254], [191, 219, 254], [30, 64, 175]),
  purple: ramp([237, 233, 254], [221, 214, 254], [91, 33, 182]),
  orange: ramp([255, 237, 213], [254, 215, 170], [154, 52, 18]),
  addFill: [240, 253, 244] as RGB, addText: [22, 101, 52] as RGB,
  delFill: [254, 242, 242] as RGB, delText: [153, 27, 27] as RGB,
};

/** "14-July-2026,  2:36:10 PM" — human date + local time for a report header's "Generated" line. */
export function generatedStamp(): string {
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const d = new Date();
  return `${d.getDate()}-${MONTHS[d.getMonth()]}-${d.getFullYear()},  ${d.toLocaleTimeString()}`;
}

/** A filename-safe local timestamp, e.g. 2026-06-24_153045 — appended to export filenames. */
export function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

// jsPDF's built-in fonts are Latin-1 only — map the UI's unicode glyphs to ASCII.
export function ascii(s: string): string {
  return (s || '')
    .replace(/→/g, '->').replace(/[⟵←]/g, '<-').replace(/[—–]/g, '-')
    .replace(/✎/g, '~').replace(/−/g, '-').replace(/[·•]/g, '-')
    .replace(/✓/g, 'OK').replace(/✗/g, 'X')
    .replace(/[^\x00-\xff]/g, '');
}

type Font = 'normal' | 'bold';

/** A jsPDF document with the shared report helpers and a running y cursor. */
export class ReportDoc {
  private doc: any;
  y = M;

  private constructor(doc: any) { this.doc = doc; }

  static async create(): Promise<ReportDoc> {
    const { jsPDF } = await import('jspdf');
    return new ReportDoc(new jsPDF({ unit: 'pt', format: 'a4' }));
  }

  ensure(h: number) { if (this.y + h > BOTTOM) { this.doc.addPage(); this.y = M; } }
  private st(c: RGB) { this.doc.setTextColor(c[0], c[1], c[2]); }
  private fl(c: RGB) { this.doc.setFillColor(c[0], c[1], c[2]); }
  private dr(c: RGB) { this.doc.setDrawColor(c[0], c[1], c[2]); }

  /** Draw text at (x, y ?? this.y) without advancing; returns its width. */
  text(s: string, x: number, font: Font = 'normal', size = 9, col: RGB = PAL.body, y = this.y): number {
    this.doc.setFont('helvetica', font); this.doc.setFontSize(size); this.st(col);
    this.doc.text(ascii(s), x, y);
    return this.doc.getTextWidth(ascii(s));
  }

  width(s: string, font: Font, size: number): number {
    this.doc.setFont('helvetica', font); this.doc.setFontSize(size);
    return this.doc.getTextWidth(ascii(s));
  }

  /** Wrapped paragraph; advances this.y. */
  para(s: string, x: number, width: number, font: Font, size: number, col: RGB, lh: number) {
    this.doc.setFont('helvetica', font); this.doc.setFontSize(size); this.st(col);
    for (const ln of this.doc.splitTextToSize(ascii(s), width) as string[]) {
      this.ensure(lh); this.doc.text(ln, x, this.y); this.y += lh;
    }
  }

  /** A rounded pill sized to its text at (x, y ?? this.y); returns its width. */
  pill(s: string, x: number, fill: RGB, col: RGB, size = 8, y = this.y): number {
    this.doc.setFont('helvetica', 'bold'); this.doc.setFontSize(size);
    const t = ascii(s); const w = this.doc.getTextWidth(t) + 12; const h = size + 7;
    this.fl(fill); this.doc.roundedRect(x, y - size - 1, w, h, 3, 3, 'F');
    this.st(col); this.doc.text(t, x + 6, y);
    return w;
  }

  rule() { this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y); this.y += 18; }

  /**
   * A standalone title page: the TraceGuard brand mark (top-right), a large title lowered into the page,
   * a subtitle and any number of muted meta lines — then a page break so the body starts fresh on page 2.
   */
  titlePage(title: string, subtitle: string, metaLines: string[]) {
    const logo = 30, gap = 8, tw = this.width('TraceGuard', 'bold', 15);
    const startX = PAGE.w - M - (logo + gap + tw);
    try { this.doc.addImage(TG_LOGO, 'PNG', startX, M, logo, logo); } catch { /* image optional */ }
    this.text('TraceGuard', startX + logo + gap, 'bold', 15, PAL.ink, M + 14);
    this.text('release intelligence', startX + logo + gap, 'normal', 8, PAL.muted, M + 25);
    this.y = Math.round(PAGE.h * 0.34);
    this.text(title, M, 'bold', 34, PAL.ink); this.y += 46;
    this.text(subtitle, M, 'normal', 15, PAL.accent); this.y += 26;
    metaLines.forEach((m) => { this.text(m, M, 'normal', 10.5, PAL.muted); this.y += 17; });
    this.doc.addPage(); this.y = M;
  }

  /** Register a PDF outline bookmark for the current page (no-op if the jsPDF build lacks outlines). */
  bookmark(title: string) {
    try { this.doc.outline.add(null, ascii(title), { pageNumber: this.doc.internal.getNumberOfPages() }); }
    catch { /* outlines unsupported — skip */ }
  }

  /** Cover header: the TraceGuard brand mark (top-right), title, subtitle, one meta line, then a rule. */
  header(title: string, subtitle: string, meta: string) {
    this.brandMark();   // logo + wordmark at top-right, so it's clear which app produced the report
    this.text(title, M, 'bold', 20, PAL.ink); this.y += 22;
    this.text(subtitle, M, 'normal', 11, PAL.accent); this.y += 16;
    this.text(meta, M, 'normal', 9, PAL.muted); this.y += 16;
    this.rule();
  }

  /** The app's logo + "TraceGuard" wordmark, right-aligned on the header's title row. */
  private brandMark() {
    const logo = 26, gap = 7, baseline = this.y;
    const tw = this.width('TraceGuard', 'bold', 14);
    const startX = PAGE.w - M - (logo + gap + tw);
    try { this.doc.addImage(TG_LOGO, 'PNG', startX, baseline - 20, logo, logo); } catch { /* image optional */ }
    this.text('TraceGuard', startX + logo + gap, 'bold', 14, PAL.ink, baseline - 4);
    this.text('report by the TraceGuard app', startX + logo + gap, 'normal', 7.5, PAL.muted, baseline + 6);
  }

  /** Executive stat band — coloured boxes with a big number + label. */
  statBand(stats: { n: number; label: string; ramp: Ramp }[]) {
    const gap = 12; const boxW = (CONTENT_W - (stats.length - 1) * gap) / stats.length; const boxH = 50;
    this.ensure(boxH + 6);
    stats.forEach((s, i) => {
      const x = M + i * (boxW + gap);
      this.fl(s.ramp.fill); this.doc.roundedRect(x, this.y, boxW, boxH, 6, 6, 'F');
      this.text(String(s.n), x + 12, 'bold', 22, s.ramp.text, this.y + 30);
      this.text(s.label, x + 12, 'bold', 9, s.ramp.text, this.y + 44);
    });
    this.y += boxH + 14;
  }

  paragraph(s: string) { this.para(s, M, CONTENT_W, 'normal', 10, PAL.body, 14); this.y += 4; }

  /**
   * A compact row of labelled stat cells — a small {@link statBand} for per-item / inline
   * summaries, e.g. "437 attempts   398 passed   39 failed". Advances this.y.
   */
  statStrip(stats: { n: number | string; label: string; ramp: Ramp }[], boxW = 140) {
    const gap = 8, boxH = 32;
    this.ensure(boxH + 10);
    stats.forEach((s, i) => {
      const x = M + i * (boxW + gap);
      this.fl(s.ramp.fill); this.doc.roundedRect(x, this.y, boxW, boxH, 5, 5, 'F');
      const nw = this.text(String(s.n), x + 12, 'bold', 16, s.ramp.text, this.y + 21);
      this.text(s.label, x + 12 + nw + 7, 'normal', 8, s.ramp.text, this.y + 21);
    });
    this.y += boxH + 10;
  }

  /**
   * A compact coverage table: a wide left-aligned first column (e.g. Module) and right-aligned
   * columns after it, an optional bold Total row. Reused by every tab's "by module" summary.
   */
  dataTable(headers: string[], rows: (string | number)[][], total?: (string | number)[]) {
    const n = headers.length;
    const firstW = CONTENT_W * 0.40;
    const colRight = (i: number) => M + firstW + i * ((CONTENT_W - firstW) / (n - 1));
    this.ensure(24 + (rows.length + (total ? 1 : 0)) * 18);
    this.fl(PAL.gray.fill); this.doc.rect(M, this.y - 2, CONTENT_W, 18, 'F'); this.y += 10;
    this.text(headers[0], M + 8, 'bold', 7.5, PAL.muted);
    for (let i = 1; i < n; i++) this.rtext(headers[i], colRight(i), 'bold', 7.5, PAL.muted);
    this.y += 6; this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    const row = (cells: (string | number)[], bold: boolean) => {
      this.y += 17;
      this.text(String(cells[0]), M + 8, bold ? 'bold' : 'normal', 9, PAL.ink);
      for (let i = 1; i < n; i++) this.rtext(String(cells[i] ?? ''), colRight(i), 'bold', 9, PAL.ink);
      this.y += 5; this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    };
    rows.forEach((c) => row(c, false));
    if (total) row(total, true);
    this.y += 10;
  }

  /**
   * A left-aligned multi-column table with per-cell wrapping — for tabular content whose cells are text
   * (not right-aligned numbers), e.g. the code-change "also re-test" list (Group | API | Route chain).
   * Column widths are fractions of CONTENT_W. A cell is a plain string, or a `{pill}` (a coloured label +
   * a left severity stripe on the row), or `{text, mono, color}`. Rows wrap within their column and the row
   * grows to fit; a hairline separates rows. Advances this.y.
   */
  wrapTable(
    columns: { header: string; w: number; mono?: boolean }[],
    rows: (string | { pill: { label: string; fill: RGB; text: RGB; stripe: RGB } } | { text: string; mono?: boolean; color?: RGB })[][],
  ) {
    const pad = 8, lh = 11;
    const xs: number[] = []; let acc = M;
    for (const c of columns) { xs.push(acc); acc += c.w * CONTENT_W; }
    const colW = columns.map((c) => c.w * CONTENT_W);
    // header band
    this.ensure(24);
    this.fl(PAL.gray.fill); this.doc.rect(M, this.y, CONTENT_W, 18, 'F');
    columns.forEach((c, i) => this.text(c.header.toUpperCase(), xs[i] + pad, 'bold', 7, PAL.muted, this.y + 12));
    this.y += 18;
    this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    // rows
    for (const row of rows) {
      let maxLines = 1;
      const wrapped: (string[] | null)[] = row.map((cell, i) => {
        if (cell && typeof cell === 'object' && 'pill' in cell) return null;   // pill = single line
        const txt = typeof cell === 'string' ? cell : (cell as { text: string }).text;
        const mono = columns[i].mono || (typeof cell === 'object' && (cell as { mono?: boolean }).mono);
        this.doc.setFont(mono ? 'courier' : 'helvetica', 'normal'); this.doc.setFontSize(mono ? 8 : 9);
        const lines = this.doc.splitTextToSize(ascii(txt), colW[i] - pad * 2) as string[];
        maxLines = Math.max(maxLines, lines.length);
        return lines;
      });
      const rowH = maxLines * lh + 8;
      this.ensure(rowH + 2);
      const top = this.y;
      const first = row[0];
      if (first && typeof first === 'object' && 'pill' in first) {
        this.fl(first.pill.stripe); this.doc.rect(M, top, 3, rowH, 'F');
      }
      row.forEach((cell, i) => {
        const cx = xs[i] + pad;
        if (cell && typeof cell === 'object' && 'pill' in cell) {
          this.pill(cell.pill.label, cx, cell.pill.fill, cell.pill.text, 8, top + 13);
        } else {
          const mono = columns[i].mono || (typeof cell === 'object' && (cell as { mono?: boolean }).mono);
          const color = (typeof cell === 'object' && (cell as { color?: RGB }).color) || PAL.body;
          this.doc.setFont(mono ? 'courier' : 'helvetica', 'normal'); this.doc.setFontSize(mono ? 8 : 9); this.st(color);
          (wrapped[i] as string[]).forEach((ln, k) => this.doc.text(ln, cx, top + 10 + k * lh));
        }
      });
      this.y = top + rowH;
      this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    }
    this.y += 10;
  }

  /** Right-align text so it ends at xRight (at y ?? this.y); returns its width. */
  rtext(s: string, xRight: number, font: Font, size: number, col: RGB, y = this.y): number {
    const w = this.width(s, font, size);
    this.text(s, xRight - w, font, size, col, y);
    return w;
  }

  /**
   * A compact "failed responses" table — RESPONSE CODE | FAILURES | SHARE (a proportional
   * bar + %) — under a small caption. Rows are drawn in the order given (already
   * most-frequent first). No-op when there is nothing to show.
   */
  failureTable(items: [string, number][], caption = 'Failed responses') {
    if (!items.length) return;
    const total = items.reduce((n, [, c]) => n + c, 0);
    const max = Math.max(...items.map(([, c]) => c));
    const codeX = M + 8, countRight = M + 300, barX = M + 320, barMax = PAGE.w - M - barX - 40;

    this.ensure(30 + items.length * 25);
    this.text(caption, M, 'bold', 9, PAL.red.text); this.y += 14;
    // header band
    this.fl(PAL.gray.fill); this.doc.rect(M, this.y - 12, CONTENT_W, 20, 'F');
    this.text('RESPONSE CODE', codeX, 'bold', 7, PAL.muted);
    this.rtext('FAILURES', countRight, 'bold', 7, PAL.muted);
    this.text('SHARE', barX, 'bold', 7, PAL.muted);
    this.y += 8;
    this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    // rows
    for (const [code, c] of items) {
      this.y += 19;
      this.text(code, codeX, 'normal', 9, PAL.body);
      this.rtext(String(c), countRight, 'bold', 9, PAL.ink);
      const bw = Math.max(2, barMax * (c / max));
      this.fl(PAL.red.bar); this.doc.roundedRect(barX, this.y - 8, bw, 9, 1.5, 1.5, 'F');
      this.text(Math.round((c / total) * 100) + '%', barX + bw + 6, 'normal', 7.5, PAL.muted);
      this.y += 6;
      this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y);
    }
    this.y += 10;
  }

  legend(title: string, bullets: string[]) {
    this.ensure(18 + bullets.length * 12);
    this.text(title, M, 'bold', 10, PAL.ink); this.y += 14;
    bullets.forEach((b) => this.para('-  ' + b, M, CONTENT_W, 'normal', 9, PAL.muted, 12));
    this.y += 6;
  }

  /** A top-level grouping banner (no count) — a coloured bar + title, with an optional blurb. */
  banner(title: string, r: Ramp, blurb = '') {
    this.ensure(46); this.y += 6;
    this.fl(r.bar); this.doc.roundedRect(M, this.y, CONTENT_W, 26, 5, 5, 'F');
    this.text(title, M + 12, 'bold', 13, r.text, this.y + 17);
    this.y += 34;
    if (blurb) { this.para(blurb, M, CONTENT_W, 'normal', 9, PAL.muted, 12); this.y += 4; }
  }

  /** A coloured section banner with a count, kept with at least one item below it. */
  section(title: string, count: number, r: Ramp, blurb: string) {
    this.ensure(78); this.y += 6;
    this.fl(r.bar); this.doc.roundedRect(M, this.y, CONTENT_W, 26, 5, 5, 'F');
    this.text(`${title}  (${count})`, M + 12, 'bold', 12, r.text, this.y + 17);
    this.y += 34;
    if (blurb) { this.para(blurb, M, CONTENT_W, 'normal', 9, PAL.muted, 12); this.y += 4; }
  }

  /**
   * A compact, colour-coded group header — a filled pill with its count (e.g. "Not tested  (13)"),
   * for labelling a subgroup INSIDE a section without a second heavy full-width band. Scannable,
   * and the colour carries the status so the rows below don't need to repeat it.
   */
  groupHead(label: string, count: number, r: Ramp) {
    this.ensure(30); this.y += 8;
    this.pill(`${label}   (${count})`, M, r.fill, r.text, 9.5);
    this.y += 17;
  }

  separator() { this.ensure(14); this.dr(PAL.rule); this.doc.line(M, this.y, PAGE.w - M, this.y); this.y += 12; }

  /** Monospace +added / -removed lines with coloured backgrounds. */
  diffLines(removed: string[], added: string[]) {
    const line = (txt: string, fill: RGB, col: RGB, sym: string) => {
      this.doc.setFont('courier', 'normal'); this.doc.setFontSize(8);
      for (const wl of this.doc.splitTextToSize(ascii(sym + ' ' + txt), CONTENT_W - 12) as string[]) {
        this.ensure(11);
        this.fl(fill); this.doc.rect(M + 4, this.y - 7.5, CONTENT_W - 4, 10, 'F');
        this.st(col); this.doc.setFont('courier', 'normal'); this.doc.setFontSize(8);
        this.doc.text(wl, M + 8, this.y); this.y += 10.5;
      }
    };
    removed.forEach((l) => line(l, PAL.delFill, PAL.delText, '-'));
    added.forEach((l) => line(l, PAL.addFill, PAL.addText, '+'));
  }

  emptyNote(s: string) { this.rule(); this.text(s, M, 'normal', 11, PAL.muted); }

  /**
   * A "Needs review" section listing imports/routes that could not be resolved from the
   * source or the supplied dependencies — so the reader knows the analysis above may be
   * incomplete. No-op when there is nothing to review.
   */
  reviewSection(items: string[] | undefined) {
    const list = items || [];
    if (!list.length) return;
    this.section('Needs review - unresolved references', list.length, PAL.amber,
      'These <import> / route references could not be resolved from the source or the dependencies '
      + 'provided, so the analysis above may be incomplete. Add the dependency source that defines '
      + 'them and re-run to close these out.');
    list.forEach((it) => this.para('-  ' + it, M, CONTENT_W, 'normal', 9, PAL.body, 12));
    this.y += 4;
  }

  /** Add a footer (identity + page x of y) to every page, then download. */
  save(filename: string, footerLeft: string) {
    const total = this.doc.getNumberOfPages();
    for (let i = 1; i <= total; i++) {
      this.doc.setPage(i);
      this.doc.setFont('helvetica', 'normal'); this.doc.setFontSize(8); this.st(PAL.muted);
      this.doc.text(ascii(footerLeft), M, PAGE.h - 24);
      const r = `Page ${i} of ${total}`;
      this.doc.text(r, PAGE.w - M - this.doc.getTextWidth(r), PAGE.h - 24);
    }
    this.doc.save(filename);
  }
}
