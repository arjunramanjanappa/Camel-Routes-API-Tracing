// Splunk query builder. Query behaviour: merged multi-flavour uses a whitespace-tolerant Request/Response
// filter; SPL-Secure uses precise per-marker direction tokens (see buildEventsSpl / buildMergedAllLinesSpl).
/**
 * Strip a leading Camel property placeholder `{{...}}` from a backend value to get the path/uri.
 * Handles a NESTED default like `{{am5.mock.url:{{am5.p.mfa.url}}}}${...}` by matching balanced `{{`/`}}`
 * pairs — a plain `/^\{\{[^}]+\}\}/` regex stops at the first `}}` and leaves a dangling `}}...`. Only a
 * fully-balanced leading placeholder is removed; anything else is returned unchanged.
 */
export function backendPath(v: string): string {
  if (!v || !v.startsWith('{{')) return v;
  let depth = 0, i = 0;
  while (i < v.length) {
    if (v.startsWith('{{', i)) { depth++; i += 2; }
    else if (v.startsWith('}}', i)) { depth--; i += 2; if (depth === 0) break; }
    else i++;
  }
  return depth === 0 ? v.slice(i) : v;   // balanced → strip it; unbalanced/malformed → leave as-is
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

/** The request/response wrapper object(s) that hold the code and service version — kept whole, so ANY key
 *  inside them (responseCode / resultCode / serviceVersionNumber, now or a Rule added later) is present without
 *  re-exporting. Request lines carry serviceRequestHeader, responses carry serviceResponseHeader. Matched
 *  case-insensitively. Configurable in the Splunk panel. */
export const DEFAULT_HEADER_KEYS = ['serviceRequestHeader', 'serviceResponseHeader', 'responses', 'ResponseHeader'];

// Direction filter for the CLEAN markers (Mighty*, SPLMessage, SPLHostMessage): those loggers emit only
// request/response lines, so the bare tokens "Request"/"Response" (standalone words, any surrounding "-"/":"/"[]")
// keep every direction line and exclude nothing they shouldn't.
const DIRECTION_FILTER = '("Request" OR "Response")';

// SPLAppLog is a general APPLICATION logger — it emits MANY lines that merely contain the word "Request" (not just
// the FE direction lines), so the bare filter would pull everything. Filter it to its PRECISE FE direction tokens:
// the request is "…- Request - {" and the response is "…- Response: …" (leading "- ", so body mentions like
// serviceRequest / X-Request-Id don't match). This is the one chatty marker; the rest stay on DIRECTION_FILTER.
const SPLAPPLOG_DIR = '("- Request -" OR "- Response:" OR "- Response -")';

/** Escape a wrapper key for use inside a rex alternation (keys are plain identifiers, but be safe). */
function reEsc(s: string): string { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

/** A balanced { … } object matcher, {@code depth} levels deep, built from POSSESSIVE quantifiers ({@code ++} /
 *  {@code *+}). Possessive = no backtracking, which is what avoids Splunk's PCRE match/recursion-depth error
 *  (the earlier greedy version blew the limit by backtracking). Nesting deeper than {@code depth} simply fails
 *  to match → the caller falls back to the full JSON. */
function balancedObj(depth: number): string {
  let obj = '\\{[^{}]*+\\}';                      // depth 0: an object with no nested objects
  for (let d = 1; d <= depth; d++) {
    obj = `\\{(?:[^{}]++|${obj})*+\\}`;           // wrap one more level of nesting
  }
  return obj;
}

/**
 * Slims each event's trailing JSON so the export stays small and audit-safe, WITHOUT tying it to the
 * current Rules — so adding a Rule later needs no Splunk re-export (just re-upload the same file):
 *
 * <ul>
 *   <li>Keeps EVERY request/response wrapper object present, named (case-insensitively) one of
 *       {@code wrapperKeys} — {@code serviceRequestHeader} on a request line, {@code serviceResponseHeader} on a
 *       response — as whole objects (plus {@code serviceVersionNumber}). Keeping all present means a response
 *       line that ALSO echoes the request can’t hide the response’s {@code responseCode}. The analyser’s
 *       recursive lookup then finds the code / version under whatever key a Rule names, at any depth.</li>
 *   <li>If no wrapper is found (or one nests deeper than the matcher), keep the <b>full JSON</b> — never lost.</li>
 *   <li>Lines with no JSON are left untouched.</li>
 * </ul>
 *
 * The line prefix (path, correlation/trace id, client version, direction, latency) is always kept intact.
 */
function slimToResponseObject(wrapperKeys: string[] = DEFAULT_HEADER_KEYS): string {
  const keys = [...new Set(wrapperKeys.map((k) => k.trim()).filter(Boolean))];
  const use = keys.length ? keys : DEFAULT_HEADER_KEYS;
  const OBJ = balancedObj(8);   // up to 8 nesting levels — deep enough for any request/response header
  const rexes = use.map((k, i) => `| rex field=_raw max_match=1 "(?i)\\"${reEsc(k)}\\"\\s*:\\s*(?<h${i}>${OBJ})"`);
  const emits = use.map((k, i) => `if(isnull(h${i}),"","\\"${k}\\":".h${i})`).join(',');
  const hasAny = use.map((_, i) => `isnotnull(h${i})`).join(' OR ');
  return [
    '| rex field=_raw "^(?<pfx>[^{]*)"',
    '| eval body=if(pfx==_raw,null(),substr(_raw,len(pfx)+1))',
    ...rexes,   // one per wrapper key — capture each header object present (request and/or response)
    '| rex field=_raw max_match=1 "(?i)\\"serviceVersionNumber\\"\\s*:\\s*\\"?(?<svc>[^\\",}\\s]+)"',
    `| eval kept=mvappend(if(isnull(svc),"","\\"serviceVersionNumber\\":\\"".svc."\\""),${emits})`,
    '| eval kept=mvfilter(kept!="")',
    `| eval _raw=if(isnull(body),_raw,if(${hasAny},pfx."{".mvjoin(kept,",")."}",pfx.body))`,
  ].join('\n');
}

/**
 * Build a Splunk search that returns the raw events (one combined query over the
 * selected front-end paths and their backends) within the time window, projected
 * as a single {@code _raw} column (sorted by time first). The trailing JSON is slimmed to the response
 * wrapper object (or the full JSON if none) — see {@link slimToResponseObject} — so the export stays lean
 * and audit-safe yet carries any code key a Rule may later name, and the same file re-drives the correlation.
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
  clientVersion = '',
  secure = false,
  sources: string[] = [],
  responseKeys: string[] = DEFAULT_HEADER_KEYS,
): string {
  const slim = slimToResponseObject(responseKeys) + '\n';
  const win = earliest ? `earliest=${earliest} latest=now ` : '';
  // Optional environment filter: (source="*env1*" OR source="*env2*") ANDed into the search so only the
  // chosen environment(s)' lines are fetched. Empty = every source (all environments).
  const srcList = [...new Set((sources || []).map((s) => s.trim()).filter(Boolean))];
  const src = srcList.length ? `(${srcList.map((s) => `source="${s}"`).join(' OR ')}) ` : '';
  // The release version is deliberately NOT ANDed into the query. Narrowing to one release ([9.4]) would
  // drop every OTHER version's lines — but BC / BAU testing in Release Impact needs the previous version's
  // lines from the SAME export, and re-pulling the log per version is the friction we're avoiding. So one
  // download carries all versions in the window; the analyser validates/scopes the version on upload.
  // (clientVersion is kept in the signature for callers but no longer filters the search.)
  void clientVersion;

  // SPL-Secure: SPLAppLog (chatty app logger) → its PRECISE FE tokens so it pulls only the request/response lines,
  // not every line containing the word "Request"; SPLHostMessage (dedicated host logger, clean) → the bare
  // DIRECTION_FILTER. Extra plain-SPL lines via the shared SPLHostMessage marker are harmless (different corrId —
  // the analyser drops them on upload). In scoped mode the selected paths are ANDed, but a secure FE response often
  // has NO path (tied by corrId), so use "All log lines" for secure to keep it.
  if (secure) {
    let scope = '';
    if (mode !== 'all') {
      const sel = [...new Set([...feTerms, ...beTerms].filter(Boolean))];
      if (sel.length === 0) return '';
      scope = ` (${sel.map((p) => `"${p}"`).join(' OR ')})`;
    }
    const group = `(("SPLAppLog" ${SPLAPPLOG_DIR}) OR ("SPLHostMessage" ${DIRECTION_FILTER}))`;
    return `index=${index} ${win}${src}${group}${scope}\n${slim}| sort 0 -_time\n| table _raw`;
  }

  // "All log lines": every front-end + backend marker line in the window. The path/svc
  // are NOT filtered — the analyser scopes to the selected APIs on upload, so this is
  // guaranteed equivalent to a raw-log dump (it can't miss a marker line).
  if (mode === 'all') {
    const markers = [feMarker, beMarker].filter(Boolean).map((m) => `"${m}"`).join(' OR ');
    if (!markers) return '';
    return `index=${index} ${win}${src}(${markers})\n${slim}| sort 0 -_time\n| table _raw`;
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
      const bev = beVersions[t];
      const svc = svcField && svcField.trim();
      // Only add the service-version filter with an extracted backend field AND a non-empty
      // service-version field name — otherwise `${svcField}="…"` would emit a bare `="…"`
      // ("Comparator '=' has an invalid term on left hand side"). In raw mode the svc lives
      // in _raw and the analyser validates it after upload, so it's skipped there anyway.
      if (!bev || !(beField && beField.trim()) || !svc) return term(beField, t);
      const vers = bev.split(' / ').map((x) => `${svc}="${x.trim()}"`);
      const verClause = vers.length > 1 ? '(' + vers.join(' OR ') + ')' : vers[0];
      return `(${term(beField, t)} ${verClause})`;
    });
    groups.push(marked(beMarker, '(' + clauses.join(' OR ') + ')'));
  }
  return `index=${index} ${win}${src}(${groups.join(' OR ')})\n${slim}| sort 0 -_time\n| table _raw`;
}

/** The log-line markers for one flavour: {@code <app>Message}/{@code <app>HostMessage}, or the SPL-Secure loggers. */
export function markersFor(app: string, secure: boolean): string[] {
  const a = app && app.trim() ? app.trim() : 'Mighty';
  return secure ? ['SPLAppLog', 'SPLHostMessage'] : [a + 'Message', a + 'HostMessage'];
}

/**
 * A SINGLE consolidated Splunk query covering every selected app flavour (Mighty + SPL + SPL-Secure) in one
 * export. It groups the DISTINCT set of their markers (MightyMessage, MightyHostMessage, SPLMessage,
 * SPLHostMessage, SPLAppLog) — the shared SPLHostMessage appears once, so no per-app duplicate clauses — and pulls
 * only request/response lines. The CLEAN markers use the bare DIRECTION_FILTER; SPLAppLog (a chatty app logger)
 * uses its PRECISE FE tokens so it isn't pulled for every line containing "Request". Extra cross-app lines are
 * harmless (the analyser correlates by corrId and ignores what doesn't belong). Splunk returns each event once.
 */
export function buildMergedAllLinesSpl(index: string, earliest: string, sources: string[],
                                       flavours: { app: string; secure: boolean }[], responseKeys = DEFAULT_HEADER_KEYS,
                                       mode: 'scoped' | 'all' = 'all', paths: string[] = []): string {
  const markerSet = new Set<string>();
  for (const f of flavours || []) markersFor(f.app, f.secure).forEach((m) => markerSet.add(m));
  const chatty = markerSet.delete('SPLAppLog');   // handled with its own precise tokens, not the bare filter
  const cleanMarkers = [...markerSet].map((m) => `"${m}"`).join(' OR ');
  const groups: string[] = [];
  if (cleanMarkers) groups.push(`((${cleanMarkers}) ${DIRECTION_FILTER})`);
  if (chatty) groups.push(`("SPLAppLog" ${SPLAPPLOG_DIR})`);
  if (!groups.length) return '';
  const markers = groups.join(' OR ');
  const win = earliest ? `earliest=${earliest} latest=now ` : '';
  const srcList = [...new Set((sources || []).map((s) => s.trim()).filter(Boolean))];
  const src = srcList.length ? `(${srcList.map((s) => `source="${s}"`).join(' OR ')}) ` : '';
  const slim = slimToResponseObject(responseKeys) + '\n';
  // Scoped: AND in the selected API URLs (front-end paths + backend hosturls) as raw phrases. Nothing selected →
  // no query. (Path-less lines — e.g. a corrId-only secure response — are why "All log lines" exists: it drops
  // this clause and keeps every marker line.)
  let scope = '';
  if (mode === 'scoped') {
    const uniq = [...new Set((paths || []).map((p) => p.trim()).filter(Boolean))];
    if (!uniq.length) return '';
    scope = ` (${uniq.map((p) => `"${p}"`).join(' OR ')})`;
  }
  return `index=${index} ${win}${src}(${markers})${scope}\n${slim}| sort 0 -_time\n| table _raw`;
}

export function downloadText(name: string, text: string): void {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}
