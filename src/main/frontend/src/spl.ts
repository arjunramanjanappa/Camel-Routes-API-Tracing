/** Strip a {{placeholder}} prefix from a backend value to get the path/uri. */
export function backendPath(v: string): string {
  return v.replace(/^\{\{[^}]+\}\}/, '');
}

/** Splunk relative-time presets for the query window (capped at 30 days). */
export interface TimePreset { label: string; earliest: string; }
export const TIME_PRESETS: TimePreset[] = [
  { label: '15 min', earliest: '-15m' },
  { label: '1 hour', earliest: '-1h' },
  { label: '4 hours', earliest: '-4h' },
  { label: '24 hours', earliest: '-24h' },
  { label: '7 days', earliest: '-7d' },
  { label: '30 days', earliest: '-30d' },
];

/**
 * Build a Splunk SPL query that searches for any of `terms` in `field` within the
 * given relative time window (e.g. earliest "-24h"). The window is rendered as
 * Splunk earliest/latest modifiers so the search is bounded.
 */
export function buildSpl(index: string, field: string, terms: string[], earliest = '-24h'): string {
  const clean = [...new Set(terms.filter(Boolean))];
  if (clean.length === 0) return '';
  const ors = clean.map((t) => `${field}="${t}"`).join(' OR ');
  const win = earliest ? `earliest=${earliest} latest=now ` : '';
  return `index=${index} ${win}(${ors})\n| stats count, latest(_time) as last_seen by ${field}\n| sort - count`;
}

export function downloadText(name: string, text: string): void {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}
