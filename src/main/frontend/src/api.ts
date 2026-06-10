import type { AnalyzeResponse, Meta, TraceParams } from './types';

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
