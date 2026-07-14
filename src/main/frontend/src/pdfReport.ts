// Shared PDF report kit — the common design language (cover header, summary stat
// band, section banners, legend, coloured diff/lines, page footers) used by every
// tab's "Export PDF" so the reports look and read the same. jsPDF is loaded on demand.

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

  /** Cover header: title, subtitle, one meta line, then a rule. */
  header(title: string, subtitle: string, meta: string) {
    this.text(title, M, 'bold', 20, PAL.ink); this.y += 22;
    this.text(subtitle, M, 'normal', 11, PAL.accent); this.y += 16;
    this.text(meta, M, 'normal', 9, PAL.muted); this.y += 16;
    this.rule();
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
   * A muted label followed by a wrapping row of coloured chips, e.g.
   * "Failed by code:  [00911 (2)] [00999 (1)]". Advances this.y. No-op when empty.
   */
  chips(label: string, items: string[], r: Ramp, indent = M) {
    if (!items.length) return;
    const gap = 5;
    this.ensure(16);
    const lw = this.text(label, indent, 'bold', 9, PAL.muted);
    const chipX = indent + lw + 8;
    let x = chipX;
    for (const it of items) {
      const w = this.width(it, 'bold', 8) + 12;
      if (x + w > PAGE.w - M) { this.y += 16; this.ensure(14); x = chipX; }   // wrap, aligned under the first chip
      this.pill(it, x, r.fill, r.text, 8);
      x += w + gap;
    }
    this.y += 16;
  }

  legend(title: string, bullets: string[]) {
    this.ensure(18 + bullets.length * 12);
    this.text(title, M, 'bold', 10, PAL.ink); this.y += 14;
    bullets.forEach((b) => this.para('-  ' + b, M, CONTENT_W, 'normal', 9, PAL.muted, 12));
    this.y += 6;
  }

  /** A coloured section banner with a count, kept with at least one item below it. */
  section(title: string, count: number, r: Ramp, blurb: string) {
    this.ensure(78); this.y += 6;
    this.fl(r.bar); this.doc.roundedRect(M, this.y, CONTENT_W, 26, 5, 5, 'F');
    this.text(`${title}  (${count})`, M + 12, 'bold', 12, r.text, this.y + 17);
    this.y += 34;
    if (blurb) { this.para(blurb, M, CONTENT_W, 'normal', 9, PAL.muted, 12); this.y += 4; }
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
