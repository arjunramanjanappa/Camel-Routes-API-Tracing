const pptxgen = require("pptxgenjs");
const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE";           // 13.33 x 7.5
pres.author = "TraceGuard";
pres.title = "TraceGuard";

const W = 13.33, H = 7.5, M = 0.7;
const HEAD = "Cambria", BODY = "Calibri";

// palette
const NAVY_BG = "0E2338", NAVY = "163B5B";
const BLUE = "2E6CA4", TEAL = "12A99A", AMBER = "C9862B";
const GREEN = "3E9B63", RED = "C2504C", SLATE = "5E7488";
const INK = "17293C", ICE = "CFE0F3", MUTE_D = "9DB3C9";
const WHITE = "FFFFFF";
const BLUE_L = "E8EFF6", TEAL_L = "E0F1EF", AMBER_L = "F5EDDD", PANEL = "F2F6F9";

const RR = pres.shapes.ROUNDED_RECTANGLE, OVAL = pres.shapes.OVAL,
      LINE = pres.shapes.LINE, RECT = pres.shapes.RECTANGLE;

const makeShadow = () => ({ type: "outer", color: "9AA9B8", blur: 7, offset: 3, angle: 90, opacity: 0.28 });
const softShadow = () => ({ type: "outer", color: "000000", blur: 8, offset: 3, angle: 90, opacity: 0.18 });

// small filled circle with a centered glyph/number
function badge(slide, cx, cy, r, color, glyph, gcolor) {
  slide.addShape(OVAL, { x: cx - r, y: cy - r, w: 2 * r, h: 2 * r, fill: { color }, line: { color: WHITE, width: 1.25 }, shadow: makeShadow() });
  slide.addText(glyph, { x: cx - r, y: cy - r, w: 2 * r, h: 2 * r, align: "center", valign: "middle", fontSize: r * 34, bold: true, color: gcolor || WHITE, fontFace: HEAD, margin: 0 });
}

// tab pill
function tabPill(slide, x, y, color, label) {
  const w = 0.28 + label.length * 0.105;
  slide.addShape(RR, { x, y, w, h: 0.42, fill: { color }, rectRadius: 0.09 });
  slide.addText(label.toUpperCase(), { x, y, w, h: 0.42, align: "center", valign: "middle", fontSize: 11, bold: true, color: WHITE, fontFace: BODY, charSpacing: 1, margin: 0 });
  return w;
}

// API -> ROUTE -> BACKEND mini flow
function nodeFlow(slide, x, y, w, labelColor) {
  const r = 0.34, cy = y;
  const cxs = [x + r, x + w / 2, x + w - r];
  const cols = [TEAL, BLUE, NAVY], caps = ["API", "ROUTE", "BACKEND"];
  for (let i = 0; i < 2; i++) {
    slide.addShape(LINE, { x: cxs[i] + r, y: cy, w: cxs[i + 1] - r - (cxs[i] + r), h: 0, line: { color: SLATE, width: 1.75, endArrowType: "triangle" } });
  }
  for (let i = 0; i < 3; i++) {
    slide.addShape(OVAL, { x: cxs[i] - r, y: cy - r, w: 2 * r, h: 2 * r, fill: { color: cols[i] }, line: { color: WHITE, width: 1.5 }, shadow: makeShadow() });
    slide.addText(caps[i], { x: cxs[i] - 0.9, y: cy + r + 0.05, w: 1.8, h: 0.26, align: "center", fontSize: 9.5, bold: true, color: labelColor, fontFace: BODY, margin: 0 });
  }
}

function reportCallout(slide, y, color, tint, desc) {
  slide.addShape(RR, { x: M, y, w: W - 2 * M, h: 0.9, fill: { color: tint }, rectRadius: 0.07, shadow: makeShadow() });
  slide.addShape(RR, { x: 1.0, y: y + 0.19, w: 0.42, h: 0.52, fill: { color: WHITE }, line: { color, width: 1.25 }, rectRadius: 0.03 });
  [0.34, 0.46, 0.58].forEach((dy, i) => slide.addShape(LINE, { x: 1.12, y: y + dy, w: i === 2 ? 0.13 : 0.2, h: 0, line: { color, width: 1 } }));
  slide.addText([{ text: "PDF report   ", options: { bold: true, color } }, { text: desc, options: { color: INK } }],
    { x: 1.72, y: y + 0.1, w: W - 2 * M - 1.3, h: 0.7, fontSize: 12.5, fontFace: BODY, valign: "middle", margin: 0 });
}

function bullets(slide, x, y, w, items, color) {
  slide.addText(items.map((t, i) => ({
    text: t, options: { bullet: { code: "2022", indent: 16 }, color: INK, breakLine: true, paraSpaceAfter: 10 }
  })), { x, y, w, h: 3.2, fontSize: 14.5, fontFace: BODY, color: INK, valign: "top" });
}

function title2(slide, colorWord, wordColor, rest) {
  slide.addText([{ text: colorWord, options: { color: wordColor } }, { text: rest, options: { color: INK } }],
    { x: M, y: 0.95, w: W - 2 * M, h: 0.75, fontSize: 30, bold: true, fontFace: HEAD, margin: 0 });
}

/* ---------------- Slide 1 — title ---------------- */
let s = pres.addSlide();
s.background = { color: NAVY_BG };
s.addText([{ text: "Trace", options: { color: WHITE } }, { text: "Guard", options: { color: TEAL } }],
  { x: 0.9, y: 2.2, w: 8.6, h: 1.2, fontSize: 60, bold: true, fontFace: HEAD, margin: 0 });
s.addText("Trace the flow.  Guard the release.", { x: 0.94, y: 3.45, w: 9, h: 0.5, fontSize: 21, italic: true, color: ICE, fontFace: BODY, margin: 0 });
s.addText("Static release intelligence for Spring Boot + Apache Camel (XML DSL). One shared view of every release — for business, dev, test and release teams.",
  { x: 0.94, y: 4.15, w: 7.9, h: 1.0, fontSize: 14.5, color: MUTE_D, fontFace: BODY, lineSpacingMultiple: 1.15, margin: 0 });
nodeFlow(s, 9.15, 2.75, 3.3, ICE);
["Release Scope", "Release Test", "Release Impact"].forEach((t, i) => {
  const x = 0.94 + i * 2.9;
  s.addShape(RR, { x, y: 5.95, w: 2.7, h: 0.55, fill: { color: "17324B" }, line: { color: TEAL, width: 1 }, rectRadius: 0.1 });
  s.addText(t, { x, y: 5.95, w: 2.7, h: 0.55, align: "center", valign: "middle", fontSize: 13, bold: true, color: ICE, fontFace: BODY, margin: 0 });
  if (i < 2) s.addText("→", { x: x + 2.7, y: 5.95, w: 0.2, h: 0.55, align: "center", valign: "middle", fontSize: 16, color: TEAL, margin: 0 });
});
s.addNotes("TraceGuard is a static-analysis tool for our Spring Boot + Apache Camel framework. It never needs the system running. Three tabs mirror a release: Scope (what it touches), Test (proof it works), Impact (what changed).");

/* ---------------- Slide 2 — problem ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
s.addText("A release is a coordination problem", { x: M, y: 0.5, w: W - 2 * M, h: 0.7, fontSize: 32, bold: true, color: INK, fontFace: HEAD, margin: 0 });
s.addText("Four questions every release has to answer — usually from four different tools.", { x: M, y: 1.28, w: W - 2 * M, h: 0.5, fontSize: 15, color: SLATE, fontFace: BODY, margin: 0 });
const cards = [
  [BLUE, "What did this release touch?", "The APIs, routes and backend services it actually impacts."],
  [TEAL, "Is it tested — end to end?", "Front-end and backend, at the right service version."],
  [AMBER, "What changed vs the last version?", "Routes, request payloads and backend service versions."],
  [NAVY, "Who signs off?", "Business, dev, test and release — from one source of truth."],
];
const cw = (W - 2 * M - 0.35) / 2, ch = 2.15;
cards.forEach((c, i) => {
  const x = M + (i % 2) * (cw + 0.35), y = 2.05 + Math.floor(i / 2) * (ch + 0.3);
  s.addShape(RR, { x, y, w: cw, h: ch, fill: { color: PANEL }, rectRadius: 0.09, shadow: softShadow() });
  badge(s, x + 0.75, y + 0.78, 0.4, c[0], String(i + 1));
  s.addText(c[1], { x: x + 1.4, y: y + 0.35, w: cw - 1.75, h: 0.6, fontSize: 17, bold: true, color: INK, fontFace: HEAD, valign: "middle", margin: 0 });
  s.addText(c[2], { x: x + 1.4, y: y + 1.0, w: cw - 1.75, h: 0.9, fontSize: 13.5, color: SLATE, fontFace: BODY, valign: "top", margin: 0 });
});
s.addNotes("Every release forces the same four questions, and today the answers live in different heads and tools. TraceGuard puts all four in one place.");

/* ---------------- Slide 3 — at a glance ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
s.addText("One tool, from source to sign-off", { x: M, y: 0.5, w: W - 2 * M, h: 0.7, fontSize: 32, bold: true, color: INK, fontFace: HEAD, margin: 0 });
s.addText("Point TraceGuard at your framework source — a local checkout or a Bitbucket branch / tag, no running system. It reads the Camel routes and controllers, resolves versions, and correlates your logs.",
  { x: M, y: 1.28, w: W - 2 * M, h: 0.6, fontSize: 15, color: SLATE, fontFace: BODY, margin: 0 });
const steps = [
  [BLUE, "1", "Release Scope", "See what a release touches — every impacted API and its full traced flow."],
  [TEAL, "2", "Release Test", "Prove it was tested end-to-end from your logs / Splunk export."],
  [AMBER, "3", "Release Impact", "See what changed vs the previous version — and who changed it."],
];
const sw = (W - 2 * M - 2 * 0.55) / 3, sy = 2.35, sh = 3.1;
steps.forEach((st, i) => {
  const x = M + i * (sw + 0.55);
  s.addShape(RR, { x, y: sy, w: sw, h: sh, fill: { color: PANEL }, rectRadius: 0.1, shadow: softShadow() });
  badge(s, x + sw / 2, sy + 0.85, 0.5, st[0], st[1]);
  s.addText(st[2], { x: x + 0.2, y: sy + 1.5, w: sw - 0.4, h: 0.5, align: "center", fontSize: 18, bold: true, color: st[0], fontFace: HEAD, margin: 0 });
  s.addText(st[3], { x: x + 0.35, y: sy + 2.05, w: sw - 0.7, h: 0.95, align: "center", fontSize: 13, color: SLATE, fontFace: BODY, valign: "top", margin: 0 });
  if (i < 2) s.addText("→", { x: x + sw, y: sy, w: 0.55, h: sh, align: "center", valign: "middle", fontSize: 24, color: st[0], margin: 0 });
});
s.addText("Two applications (Mighty & SPL) from one entry point  ·  analyse a local path or a Bitbucket branch — nothing for the release team to install.",
  { x: M, y: 6.1, w: W - 2 * M, h: 0.5, align: "center", fontSize: 13, italic: true, color: SLATE, fontFace: BODY, margin: 0 });
s.addNotes("The three tabs are a workflow, not a menu: Scope then Test then Impact. Source can be a local checkout or a Bitbucket branch/tag (the server clones it — no git/helm on the user's machine). It serves two apps that differ only in their log markers.");

/* ---------------- Slide 4 — Release Scope ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
tabPill(s, M, 0.5, BLUE, "Release Scope");
title2(s, "See what a release touches", BLUE, "");
bullets(s, M, 1.85, 6.3, [
  "Every API a client release impacts, grouped by the version it resolves to.",
  "The full traced flow — API → Camel routes → backend services — as an interactive graph.",
  "Each backend shows the exact service version it is called at.",
  "Filter out the noise: only the APIs this release actually changed.",
], BLUE);
// right panel with a mini route graph
const px = 7.35, py = 1.85, pw = 5.28, ph = 3.55;
s.addShape(RR, { x: px, y: py, w: pw, h: ph, fill: { color: PANEL }, rectRadius: 0.09, shadow: softShadow() });
(function graph() {
  const api = [px + 0.85, py + 1.78], r1 = [px + 2.65, py + 1.05], r2 = [px + 2.65, py + 2.55],
        b1 = [px + 4.35, py + 1.05], b2 = [px + 4.35, py + 2.55];
  const edges = [[api, r1], [api, r2], [r1, b1], [r2, b2]];
  edges.forEach(([a, b]) => s.addShape(LINE, { x: a[0], y: a[1], w: b[0] - a[0], h: b[1] - a[1], line: { color: SLATE, width: 1.5, endArrowType: "triangle" } }));
  const node = (c, col, cap) => { const rr = 0.3; s.addShape(OVAL, { x: c[0] - rr, y: c[1] - rr, w: 2 * rr, h: 2 * rr, fill: { color: col }, line: { color: WHITE, width: 1.25 }, shadow: makeShadow() }); s.addText(cap, { x: c[0] - 0.75, y: c[1] + rr + 0.02, w: 1.5, h: 0.22, align: "center", fontSize: 8.5, bold: true, color: SLATE, fontFace: BODY, margin: 0 }); };
  node(api, TEAL, "API"); node(r1, BLUE, "route"); node(r2, BLUE, "route"); node(b1, NAVY, "backend"); node(b2, NAVY, "backend");
  [[b1, "svc 2.3"], [b2, "svc 2.1"]].forEach(([c, t]) => s.addText(t, { x: c[0] + 0.35, y: c[1] - 0.14, w: 0.85, h: 0.28, fontSize: 8.5, bold: true, color: NAVY, fontFace: BODY, margin: 0 }));
  s.addText("traced flow · one impacted API", { x: px + 0.25, y: py + ph - 0.4, w: pw - 0.5, h: 0.3, align: "center", fontSize: 10, italic: true, color: SLATE, fontFace: BODY, margin: 0 });
})();
reportCallout(s, 5.75, BLUE, BLUE_L, "the impacted-API catalog for the release — every API, its route, flow and backends.");
s.addNotes("Scope answers 'what does this release touch'. The graph shows an API fanning out through routes to its backends, each tagged with the service version. The PDF is the full catalog for release sign-off.");

/* ---------------- Slide 5 — Release Test ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
tabPill(s, M, 0.5, TEAL, "Release Test");
title2(s, "Prove it was tested", TEAL, "");
bullets(s, M, 1.85, 6.3, [
  "Generates a Splunk query scoped to the client version — fewer logs, no other-version noise.",
  "Correlates every front-end call with its backend by correlation id.",
  "An end-to-end verdict per API, plus a backend service-version check.",
  "Upload the raw log or the Splunk export — same result either way.",
], TEAL);
// right panel: verdict pills
(function verdicts() {
  s.addShape(RR, { x: px, y: py, w: pw, h: 3.55, fill: { color: PANEL }, rectRadius: 0.09, shadow: softShadow() });
  s.addText("End-to-end verdict", { x: px + 0.35, y: py + 0.25, w: pw - 0.7, h: 0.35, fontSize: 13, bold: true, color: INK, fontFace: HEAD, margin: 0 });
  const rows = [[GREEN, "Success", "14"], [SLATE, "Partial", "2"], [RED, "Failed", "1"], [AMBER, "Timeout", "0"]];
  rows.forEach((rw, i) => {
    const y = py + 0.75 + i * 0.55;
    s.addShape(RR, { x: px + 0.35, y, w: pw - 0.7, h: 0.44, fill: { color: WHITE }, line: { color: "DBE4EB", width: 1 }, rectRadius: 0.06 });
    s.addShape(OVAL, { x: px + 0.5, y: y + 0.12, w: 0.2, h: 0.2, fill: { color: rw[0] } });
    s.addText(rw[1], { x: px + 0.85, y, w: 2.6, h: 0.44, valign: "middle", fontSize: 13, color: INK, fontFace: BODY, margin: 0 });
    s.addText(rw[2], { x: px + pw - 1.15, y, w: 0.75, h: 0.44, align: "right", valign: "middle", fontSize: 14, bold: true, color: rw[0], fontFace: BODY, margin: 0 });
  });
  s.addText("Service version  ✓ matched against the traced version", { x: px + 0.35, y: py + 3.05, w: pw - 0.7, h: 0.3, align: "center", fontSize: 10, italic: true, color: SLATE, fontFace: BODY, margin: 0 });
})();
reportCallout(s, 5.75, TEAL, TEAL_L, "a verification / sign-off report — worst-first, per-API pass/fail with the evidence.");
s.addNotes("Test answers 'is it actually tested end-to-end'. We generate the Splunk query, you paste it, export, upload. It ties front-end to backend by correlation id and checks the service version. The PDF is the sign-off evidence.");

/* ---------------- Slide 6 — Release Impact ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
tabPill(s, M, 0.5, AMBER, "Release Impact");
title2(s, "See what changed", AMBER, "");
// status chips row
[["Changed", "6", AMBER], ["New", "2", GREEN], ["Unchanged", "3", SLATE]].forEach((c, i) => {
  const x = M + i * 2.15;
  s.addShape(RR, { x, y: 1.75, w: 1.95, h: 1.0, fill: { color: PANEL }, rectRadius: 0.08, shadow: softShadow() });
  s.addText(c[1], { x, y: 1.82, w: 1.95, h: 0.6, align: "center", fontSize: 30, bold: true, color: c[2], fontFace: HEAD, margin: 0 });
  s.addText(c[0], { x, y: 2.42, w: 1.95, h: 0.3, align: "center", fontSize: 12, color: SLATE, fontFace: BODY, margin: 0 });
});
bullets(s, M, 3.1, 6.3, [
  "Route-level diffs — added / removed / modified, element by element.",
  "Backend service-version bumps (2.2 → 2.3), even when the route XML is unchanged.",
  "Payload changes — JSON keys added / removed across the request templates.",
  "“Changed by / Added by” — git-blame authorship of who made each change.",
], AMBER);
// right: a diff snippet card
(function diff() {
  s.addShape(RR, { x: px, y: 1.75, w: pw, h: 3.65, fill: { color: "1B2C3D" }, rectRadius: 0.09, shadow: softShadow() });
  s.addText("fundTransferSubmitV2Api", { x: px + 0.3, y: 1.95, w: pw - 0.6, h: 0.35, fontSize: 12.5, bold: true, color: ICE, fontFace: BODY, margin: 0 });
  const lines = [
    ["-", "to uri=\"...ft/v93/precapture.ftl\"", RED],
    ["+", "to uri=\"...ft/v94/precapture.ftl\"", GREEN],
    ["~", "backend service version  2.2 → 2.3", AMBER],
    ["+", "payload key  currency", GREEN],
  ];
  lines.forEach((l, i) => {
    const y = 2.45 + i * 0.6;
    s.addShape(RR, { x: px + 0.3, y, w: pw - 0.6, h: 0.48, fill: { color: "24384C" }, rectRadius: 0.04 });
    s.addText(l[0], { x: px + 0.42, y, w: 0.3, h: 0.48, valign: "middle", fontSize: 14, bold: true, color: l[2], fontFace: "Consolas", margin: 0 });
    s.addText(l[1], { x: px + 0.78, y, w: pw - 1.05, h: 0.48, valign: "middle", fontSize: 11, color: ICE, fontFace: "Consolas", margin: 0 });
  });
  s.addText("changed by  a.ramanjanappa", { x: px + 0.3, y: 4.98, w: pw - 0.6, h: 0.3, fontSize: 9.5, italic: true, color: MUTE_D, fontFace: BODY, margin: 0 });
})();
reportCallout(s, 5.75, AMBER, AMBER_L, "a Release Impact report (Changed + New) — for review and regression scoping.");
s.addNotes("Impact answers 'what changed vs last version'. It catches route diffs, service-version bumps, payload key changes, and attributes each change via git blame. The PDF drives review and decides what to regression-test.");

/* ---------------- Slide 7 — reporting ---------------- */
s = pres.addSlide();
s.background = { color: WHITE };
s.addText("Sign off from one document", { x: M, y: 0.5, w: W - 2 * M, h: 0.7, fontSize: 32, bold: true, color: INK, fontFace: HEAD, margin: 0 });
s.addText("Every tab exports a shareable, consistently-designed PDF — the same report drives business review, dev handover, test evidence and the release go / no-go.",
  { x: M, y: 1.28, w: W - 2 * M, h: 0.6, fontSize: 15, color: SLATE, fontFace: BODY, margin: 0 });
const rep = [
  [BLUE, "Release Scope", "Scope report", "Release & business — the blast radius of the release."],
  [TEAL, "Release Test", "Verification report", "Test & release — proof each API passed end-to-end."],
  [AMBER, "Release Impact", "Impact report", "Dev & test — what changed, and who changed it."],
];
const rw2 = (W - 2 * M - 2 * 0.5) / 3, ry = 2.4, rh = 3.15;
rep.forEach((r, i) => {
  const x = M + i * (rw2 + 0.5);
  s.addShape(RR, { x, y: ry, w: rw2, h: rh, fill: { color: PANEL }, rectRadius: 0.1, shadow: softShadow() });
  s.addShape(RR, { x: x + 0.35, y: ry + 0.4, w: 0.6, h: 0.74, fill: { color: WHITE }, line: { color: r[0], width: 1.5 }, rectRadius: 0.04 });
  [0.62, 0.79, 0.96].forEach((dy, k) => s.addShape(LINE, { x: x + 0.5, y: ry + dy, w: k === 2 ? 0.18 : 0.3, h: 0, line: { color: r[0], width: 1 } }));
  tabPill(s, x + 1.15, ry + 0.5, r[0], r[1]);
  s.addText(r[2], { x: x + 0.35, y: ry + 1.5, w: rw2 - 0.7, h: 0.5, fontSize: 18, bold: true, color: INK, fontFace: HEAD, margin: 0 });
  s.addText(r[3], { x: x + 0.35, y: ry + 2.05, w: rw2 - 0.7, h: 0.95, fontSize: 13, color: SLATE, fontFace: BODY, valign: "top", margin: 0 });
});
s.addText("Cover header · executive stat band · colour-coded sections · page footers — one design across every report.",
  { x: M, y: 6.15, w: W - 2 * M, h: 0.5, align: "center", fontSize: 13, italic: true, color: SLATE, fontFace: BODY, margin: 0 });
s.addNotes("The reports share one design language so a single document works for every audience. Scope for readiness, Verification for proof, Impact for change control.");

/* ---------------- Slide 8 — value + close ---------------- */
s = pres.addSlide();
s.background = { color: NAVY_BG };
s.addText("How TraceGuard helps you ship with confidence", { x: M, y: 0.55, w: W - 2 * M, h: 0.8, fontSize: 30, bold: true, color: WHITE, fontFace: HEAD, margin: 0 });
const val = [
  [BLUE, "Business", "Visibility into scope and readiness — a clear go / no-go."],
  [AMBER, "Dev", "Exactly what changed this release, and who changed it."],
  [TEAL, "Test", "What to test, and the evidence it passed end-to-end."],
  ["7C93AC", "Release", "One consolidated, signed-off record for every release."],
];
const vw = (W - 2 * M - 0.4) / 2, vh = 1.55;
val.forEach((v, i) => {
  const x = M + (i % 2) * (vw + 0.4), y = 1.75 + Math.floor(i / 2) * (vh + 0.35);
  s.addShape(RR, { x, y, w: vw, h: vh, fill: { color: "17324B" }, rectRadius: 0.09 });
  s.addShape(OVAL, { x: x + 0.35, y: y + 0.5, w: 0.5, h: 0.5, fill: { color: v[0] } });
  s.addText(v[1], { x: x + 1.1, y: y + 0.28, w: vw - 1.35, h: 0.45, fontSize: 18, bold: true, color: WHITE, fontFace: HEAD, valign: "middle", margin: 0 });
  s.addText(v[2], { x: x + 1.1, y: y + 0.78, w: vw - 1.35, h: 0.65, fontSize: 13, color: ICE, fontFace: BODY, valign: "top", margin: 0 });
});
s.addText([{ text: "Trace the flow.  ", options: { color: WHITE } }, { text: "Guard the release.", options: { color: TEAL } }],
  { x: M, y: 6.35, w: W - 2 * M, h: 0.6, align: "center", fontSize: 22, bold: true, italic: true, fontFace: HEAD, margin: 0 });
s.addNotes("Close on the value per team: business gets go/no-go, dev gets change + authorship, test gets what-to-test + proof, release gets a signed-off record. One tool, from scope to sign-off.");

pres.writeFile({ fileName: "TraceGuard.pptx" }).then(f => console.log("WROTE " + f));
