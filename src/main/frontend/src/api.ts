import type { AnalyzeResponse, ImpactIndex, LogAnalysisReport, Meta, TraceParams } from './types';

function qs(params: Record<string, string | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v && v.trim()) p.set(k, v.trim());
  }
  return p.toString();
}

export async function analyze(params: TraceParams): Promise<AnalyzeResponse> {
  const res = await fetch('/internal/route-graph?' + qs(params as Record<string, string | undefined>));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as AnalyzeResponse;
}

export async function fetchMeta(sourceDir?: string, country?: string): Promise<Meta> {
  const res = await fetch('/internal/meta?' + qs({ sourceDir, country }));
  if (!res.ok) return { countries: [], versions: [], transferTypes: [] };
  return (await res.json()) as Meta;
}

export async function fetchImpactIndex(sourceDir?: string, country?: string, version?: string): Promise<ImpactIndex> {
  const res = await fetch('/internal/impact-index?' + qs({ sourceDir, country, version }));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as ImpactIndex;
}

/** Upload an output log / Splunk export and correlate it against the traced APIs. */
export async function analyzeLog(
  file: File,
  params: { version?: string; country?: string; sourceDir?: string; apis?: string[] },
): Promise<LogAnalysisReport> {
  const form = new FormData();
  form.append('file', file);
  if (params.version && params.version.trim()) form.append('version', params.version.trim());
  if (params.country && params.country.trim()) form.append('country', params.country.trim());
  if (params.sourceDir && params.sourceDir.trim()) form.append('sourceDir', params.sourceDir.trim());
  (params.apis ?? []).forEach((a) => form.append('apis', a));
  const res = await fetch('/internal/log-analysis', { method: 'POST', body: form });
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as LogAnalysisReport;
}
