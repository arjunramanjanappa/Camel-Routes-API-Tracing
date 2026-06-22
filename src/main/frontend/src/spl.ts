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
  beVersions: Record<string, string> = {},
  svcField = 'serviceVersionNumber',
  wildcard = true,
  feMarker = '',
  beMarker = '',
  mode: 'scoped' | 'all' = 'scoped',
): string {
  const win = earliest ? `earliest=${earliest} latest=now ` : '';

  // "All log lines": every front-end + backend marker line in the window. The path/svc
  // are NOT filtered — the analyser scopes to the selected APIs on upload, so this is
  // guaranteed equivalent to a raw-log dump (it can't miss a marker line).
  if (mode === 'all') {
    const markers = [feMarker, beMarker].filter(Boolean).map((m) => `"${m}"`).join(' OR ');
    if (!markers) return '';
    return `index=${index} ${win}(${markers})\n| table _time, _raw\n| sort 0 _time`;
  }

  const fe = [...new Set(feTerms.filter(Boolean))];
  const be = [...new Set(beTerms.filter(Boolean))];
  if (fe.length === 0 && be.length === 0) return '';
  // A path term. With a field, field="*path" (wildcard tolerates a context prefix). With
  // NO field, the path is searched as a phrase in _raw — because in the raw log the path
  // is plain text (… -/services/sg/… - Request …), not an extracted field.
  const term = (field: string, t: string) =>
    (field && field.trim() ? `${field}="${wildcard ? '*' + t : t}"` : `"${t}"`);
  // Scope each path group to its log marker so the export only carries the lines the
  // analyser reads: front-end paths from <App>Message, backends from <App>HostMessage.
  const marked = (marker: string, inner: string) => (marker ? `("${marker}" ${inner})` : inner);
  const groups: string[] = [];
  if (fe.length) {
    groups.push(marked(feMarker, '(' + fe.map((t) => term(feField, t)).join(' OR ') + ')'));
  }
  if (be.length) {
    // With an extracted backend field, also filter to the traced service version(s); in
    // raw mode the svc lives in _raw and the analyser validates it after upload, so skip it.
    const clauses = be.map((t) => {
      const ver = beVersions[t];
      if (!ver || !(beField && beField.trim())) return term(beField, t);
      const vers = ver.split(' / ').map((v) => `${svcField}="${v.trim()}"`);
      const verClause = vers.length > 1 ? '(' + vers.join(' OR ') + ')' : vers[0];
      return `(${term(beField, t)} ${verClause})`;
    });
    groups.push(marked(beMarker, '(' + clauses.join(' OR ') + ')'));
  }
  return `index=${index} ${win}(${groups.join(' OR ')})\n| table _time, _raw\n| sort 0 _time`;
}

export function downloadText(name: string, text: string): void {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}
