/**
 * Business-area labels for the Release Impact reports — the names a team uses in its own documents
 * ("Funds Transfer & Payments", "Quick Transfer") in place of raw path segments. Configured per machine in
 * ~/.traceguard/feature-labels.json (⚙ Config → Business-area labels) and seeded with the team's defaults.
 *
 * Key = the API path after /services/<country>/, lower-case, version segments (v1, v2) removed. A one-segment
 * key names an AREA (payment); a longer key names a SUB-FEATURE (payment/fundtransfer/quick). A path resolves
 * to its longest matching prefix; with no label it falls back to a humanised segment, so nothing is hidden.
 */

import { backendPath } from './spl';

export type FeatureLabels = Record<string, string>;

const VERSION_SEG = /^v\d+$/i;

/** The path's key segments: after /services/<country>/ when present, lower-case, version segments dropped. */
export function keySegments(path: string): string[] {
  const parts = backendPath(path || '').split('?')[0].split('/').filter(Boolean).map((p) => p.toLowerCase());
  const body = parts[0] === 'services' && parts.length >= 3 ? parts.slice(2) : parts;
  return body.filter((p) => !VERSION_SEG.test(p));
}

/** "fundtransfer" → "Fundtransfer", "quickTransfer" → "Quick Transfer", "cross-border" → "Cross Border". */
export function humanize(seg: string): string {
  if (!seg) return 'Other';
  return seg
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .split(' ')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/** The business AREA of a path: the first key segment, labelled when configured. */
export function areaOf(path: string, labels: FeatureLabels = {}): { key: string; label: string } {
  const segs = keySegments(path);
  const key = segs[0] || 'other';
  return { key, label: labels[key] || humanize(key) };
}

/**
 * The SUB-FEATURE of a path inside its area: the longest configured prefix of two or more segments; else, for a
 * path with at least three segments, its second segment (so /payment/fundtransfer/quick/... groups under
 * "Fundtransfer" even before a label exists). Null for an API directly under its area.
 */
export function subOf(path: string, labels: FeatureLabels = {}): { key: string; label: string } | null {
  const segs = keySegments(path);
  for (let n = Math.min(segs.length - 1, 6); n >= 2; n--) {   // leave at least the API's own last segment out
    const key = segs.slice(0, n).join('/');
    if (labels[key]) return { key, label: labels[key] };
  }
  if (segs.length >= 3) {
    const key = segs.slice(0, 2).join('/');
    return { key, label: labels[key] || humanize(segs[1]) };
  }
  return null;
}

/** Parse the Config editor's text form — one `path-key = Label` per line; blank lines and # comments ignored. */
export function parseLabelLines(text: string): FeatureLabels {
  const out: FeatureLabels = {};
  for (const raw of (text || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim().toLowerCase().replace(/^\/+|\/+$/g, '');
    const label = line.slice(eq + 1).trim();
    if (key && label) out[key] = label;
  }
  return out;
}

/** The text form of a label map for the editor. */
export function labelLines(labels: FeatureLabels): string {
  return Object.entries(labels).map(([k, v]) => `${k} = ${v}`).join('\n');
}
