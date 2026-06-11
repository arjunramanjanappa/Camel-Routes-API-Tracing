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

/**
 * Build a Splunk search that returns the raw events (one combined query over the
 * selected front-end paths and their backends) within the time window, projected
 * as {@code _time, _raw}. Exporting this as CSV yields exactly the columns the log
 * analyser reads back, so the same report drives the end-to-end correlation.
 */
export function buildEventsSpl(
  index: string,
  feField: string,
  feTerms: string[],
  beField: string,
  beTerms: string[],
  earliest = '-24h',
): string {
  const fe = [...new Set(feTerms.filter(Boolean))];
  const be = [...new Set(beTerms.filter(Boolean))];
  if (fe.length === 0 && be.length === 0) return '';
  const groups: string[] = [];
  if (fe.length) groups.push('(' + fe.map((t) => `${feField}="${t}"`).join(' OR ') + ')');
  if (be.length) groups.push('(' + be.map((t) => `${beField}="${t}"`).join(' OR ') + ')');
  const win = earliest ? `earliest=${earliest} latest=now ` : '';
  return `index=${index} ${win}(${groups.join(' OR ')})\n| table _time, _raw\n| sort 0 _time`;
}

export function downloadText(name: string, text: string): void {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}
