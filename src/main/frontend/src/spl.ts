/** Strip a {{placeholder}} prefix from a backend value to get the path/uri. */
export function backendPath(v: string): string {
  return v.replace(/^\{\{[^}]+\}\}/, '');
}

/** Build a Splunk SPL query that searches for any of `terms` in `field`. */
export function buildSpl(index: string, field: string, terms: string[]): string {
  const clean = [...new Set(terms.filter(Boolean))];
  if (clean.length === 0) return '';
  const ors = clean.map((t) => `${field}="${t}"`).join(' OR ');
  return `index=${index} (${ors})\n| stats count, latest(_time) as last_seen by ${field}\n| sort - count`;
}

export function downloadText(name: string, text: string): void {
  const a = document.createElement('a');
  a.href = 'data:text/plain;charset=utf-8,' + encodeURIComponent(text);
  a.download = name;
  a.click();
}
