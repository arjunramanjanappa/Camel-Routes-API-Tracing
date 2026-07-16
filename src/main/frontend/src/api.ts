import type { AnalyzeResponse, ImpactIndex, LogAnalysisReport, Meta, ModuleLogReport, TraceParams, VersionDiffReport } from './types';

/** Build a query string; string values are set once, string[] values are appended once per entry (repeated params). */
function qs(params: Record<string, string | string[] | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (Array.isArray(v)) {
      v.forEach((item) => { if (item && item.trim()) p.append(k, item.trim()); });
    } else if (v && v.trim()) {
      p.set(k, v.trim());
    }
  }
  return p.toString();
}

export async function analyze(params: TraceParams): Promise<AnalyzeResponse> {
  const res = await fetch('/internal/route-graph?' + qs(params as Record<string, string | string[] | undefined>));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as AnalyzeResponse;
}

export async function fetchMeta(sourceDir?: string, country?: string, repo?: string, branch?: string, dep?: string[]): Promise<Meta> {
  const res = await fetch('/internal/meta?' + qs({ sourceDir, country, repo, branch, dep }));
  if (!res.ok) return { countries: [], versions: [], transferTypes: [] };
  return (await res.json()) as Meta;
}

export async function fetchImpactIndex(sourceDir?: string, country?: string, version?: string, repo?: string, branch?: string, dep?: string[], app?: string): Promise<ImpactIndex> {
  const res = await fetch('/internal/impact-index?' + qs({ sourceDir, country, version, repo, branch, dep, app }));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as ImpactIndex;
}

/** Release diff: what a target version changed per API vs its immediate-lower version. */
export async function fetchVersionDiff(sourceDir?: string, country?: string, version?: string, repo?: string, branch?: string, dep?: string[], app?: string): Promise<VersionDiffReport> {
  const res = await fetch('/internal/version-diff?' + qs({ sourceDir, country, version, repo, branch, dep, app }));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as VersionDiffReport;
}

/** Upload one or more output logs / Splunk exports (chunks) and correlate them against the traced APIs. */
export async function analyzeLog(
  file: File | File[],
  params: { version?: string; country?: string; sourceDir?: string; apis?: string[]; backends?: string[]; all?: boolean; app?: string; repo?: string; branch?: string; dep?: string[] },
): Promise<LogAnalysisReport> {
  const form = new FormData();
  (Array.isArray(file) ? file : [file]).forEach((f) => form.append('file', f));
  if (params.version && params.version.trim()) form.append('version', params.version.trim());
  if (params.country && params.country.trim()) form.append('country', params.country.trim());
  if (params.sourceDir && params.sourceDir.trim()) form.append('sourceDir', params.sourceDir.trim());
  if (params.repo && params.repo.trim()) form.append('repo', params.repo.trim());
  if (params.branch && params.branch.trim()) form.append('branch', params.branch.trim());
  (params.dep ?? []).forEach((d) => { if (d && d.trim()) form.append('dep', d.trim()); });
  (params.apis ?? []).forEach((a) => form.append('apis', a));
  (params.backends ?? []).forEach((b) => form.append('backends', b));
  if (params.all) form.append('all', 'true');
  if (params.app) form.append('app', params.app);
  const res = await fetch('/internal/log-analysis', { method: 'POST', body: form });
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as LogAnalysisReport;
}

/** One module's source + marker flavour for a multi-module log verification. */
export interface LogModuleSpec { name: string; sourceDir?: string; repo?: string; branch?: string; app?: string; }

/**
 * Multi-module release test: upload the log chunk(s) ONCE and correlate them against every module in
 * a single request. The backend re-reads the (spooled) upload per module — so a 200 MB+ log is not
 * re-uploaded per module — each with its own marker flavour. Returns one report per module.
 */
export async function analyzeLogMulti(
  files: File[],
  modules: LogModuleSpec[],
  params: { version?: string; country?: string; dep?: string[] },
): Promise<ModuleLogReport[]> {
  const form = new FormData();
  files.forEach((f) => form.append('file', f));
  if (params.version && params.version.trim()) form.append('version', params.version.trim());
  if (params.country && params.country.trim()) form.append('country', params.country.trim());
  (params.dep ?? []).forEach((d) => { if (d && d.trim()) form.append('dep', d.trim()); });
  // Parallel lists, one entry per module (index-aligned on the backend).
  modules.forEach((m) => {
    form.append('moduleName', m.name || '');
    form.append('moduleSourceDir', m.sourceDir || '');
    form.append('moduleRepo', m.repo || '');
    form.append('moduleBranch', m.branch || '');
    form.append('moduleApp', m.app || '');
  });
  const res = await fetch('/internal/log-analysis-multi', { method: 'POST', body: form });
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as ModuleLogReport[];
}
