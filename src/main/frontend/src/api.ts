import type { AnalyzeResponse, ImpactIndex, LogAnalysisReport, Meta, ModuleLogReport, SourceType, TraceParams, VersionDiffReport } from './types';

/** One configured module in the app config (the ModuleSource shape without a client-side id). */
export interface ConfigModule { sourceType: SourceType; sourceDir?: string; repo?: string; branch?: string; }

/** The per-app module lists the UI prepopulates from (Mighty / SPL → their modules). */
export async function fetchAppConfig(): Promise<Record<string, ConfigModule[]>> {
  const res = await fetch('/internal/app-config');
  if (!res.ok) return {};
  return (await res.json()) as Record<string, ConfigModule[]>;
}

/** "Save as default": persist one app's module list back to the server config file. */
export async function saveAppConfig(app: string, modules: ConfigModule[]): Promise<void> {
  const body = modules.map((m) => ({ sourceType: m.sourceType, sourceDir: m.sourceDir ?? '', repo: m.repo ?? '', branch: m.branch ?? '' }));
  const res = await fetch('/internal/app-config/' + encodeURIComponent(app), {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  if (!res.ok) {
    const d = await res.json().catch(() => null);
    throw new Error((d && d.error) || `HTTP ${res.status}`);
  }
}

/** Machine-wide config state (tokens are never returned raw — only presence + a masked preview). */
export interface AppSettings {
  home: string;
  bitbucketTokenSet: boolean;
  bitbucketTokenMasked: string;
  npmTokenSet: boolean;
  npmTokenMasked: string;
  /** Splunk Web base URL up to (and including) `/services/search/jobs/` — used to build result-download links. Not a secret. */
  splunkUrl: string;
  /** Front-end pass-rate threshold for the log-analysis verdict, as a fraction string (e.g. "0.95") or "" for the default. */
  passThreshold: string;
}

/** Read the config-menu state: where the machine-wide config lives and which tokens are set. */
export async function fetchSettings(): Promise<AppSettings> {
  const res = await fetch('/internal/settings');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as AppSettings;
}

/**
 * Save the Bitbucket / npm tokens. Only the fields present are changed — pass a string to set it
 * (empty string clears), or omit a field to leave it as-is. Returns the refreshed masked state.
 */
export async function saveSettings(patch: { bitbucketToken?: string; npmToken?: string; splunkUrl?: string; passThreshold?: string }): Promise<AppSettings> {
  const res = await fetch('/internal/settings', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch),
  });
  if (!res.ok) {
    const d = await res.json().catch(() => null);
    throw new Error((d && d.error) || `HTTP ${res.status}`);
  }
  return (await res.json()) as AppSettings;
}

/** One host response-code rule (matched on a backend hosturl glob). */
export interface LogRule { match: string; codeField: string; successCodes: string[]; skip: boolean; svcVersion?: string; }
/** Per-app host response-code rules: ordered fallback code keys + per-hosturl rules. */
export interface AppLogRules { codeFields: string[]; rules: LogRule[]; }
/** The whole log-rules.json — keyed by app/marker (Mighty / SPL / SPL-Secure). */
export type LogRulesMap = Record<string, AppLogRules>;

export async function fetchLogRules(): Promise<LogRulesMap> {
  const res = await fetch('/internal/log-rules');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as LogRulesMap;
}

/** Replace the whole host response-code rule map (all apps at once). */
export async function saveLogRules(map: LogRulesMap): Promise<LogRulesMap> {
  const res = await fetch('/internal/log-rules', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(map),
  });
  if (!res.ok) {
    const d = await res.json().catch(() => null);
    throw new Error((d && d.error) || `HTTP ${res.status}`);
  }
  return (await res.json()) as LogRulesMap;
}

// --- VAL Capability Matrix: impacted APIs → business capabilities (how to test) ---

/** One Capability Matrix row (columns A–N). */
export interface CapabilityRow {
  id: string; l1: string; l2: string; l3: string; l4: string; l5: string;
  l6Features: string; dependentCapability: string; linkedInterface: string; linkedReport: string;
  linkedTestCase: string; entity: string; splDetailedInterfaceTable: string; description: string;
}
export interface CapabilityMatch { api: string; fe: boolean; matchedInterface: string; capabilities: CapabilityRow[]; }
export interface CapabilityUnmatched { api: string; fe: boolean; reason: string; }
export interface CapabilityResult { matched: CapabilityMatch[]; unmatched: CapabilityUnmatched[]; }
export interface CapabilityConfigStatus { interfaceSpec: boolean; capabilityMatrix: boolean; }
/** statusByApi (Release Test only): API path → its log-analysis verdict, for the export's Test Status column. */
export interface CapabilityScope { feApis: string[]; beApis: string[]; country?: string; statusByApi?: Record<string, string> }

/** Whether the two VAL reports are configured. */
export async function fetchCapabilityConfig(): Promise<CapabilityConfigStatus> {
  const res = await fetch('/internal/capability-config');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as CapabilityConfigStatus;
}

/** Upload one or both VAL reports (a one-time config; either may be sent alone). */
export async function saveCapabilityConfig(interfaceSpec?: File | null, capabilityMatrix?: File | null): Promise<CapabilityConfigStatus> {
  const fd = new FormData();
  if (interfaceSpec) fd.append('interfaceSpec', interfaceSpec);
  if (capabilityMatrix) fd.append('capabilityMatrix', capabilityMatrix);
  const res = await fetch('/internal/capability-config', { method: 'POST', body: fd });
  if (!res.ok) {
    const d = await res.json().catch(() => null);
    throw new Error((d && d.error) || `HTTP ${res.status}`);
  }
  return (await res.json()) as CapabilityConfigStatus;
}

/** Resolve the impacted APIs to their capabilities (drives the summary-PDF capability columns). */
export async function resolveCapabilities(scope: CapabilityScope): Promise<CapabilityResult> {
  const res = await fetch('/internal/capabilities', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(scope),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as CapabilityResult;
}

/** Download the joined Capability export .xlsx for the current scope's impacted APIs. */
export async function exportCapabilitiesXlsx(scope: CapabilityScope): Promise<void> {
  const res = await fetch('/internal/capabilities/export', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(scope),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'capability-matrix.xlsx';
  a.click();
  URL.revokeObjectURL(url);
}

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
export async function fetchVersionDiff(sourceDir?: string, country?: string, version?: string, repo?: string, branch?: string, dep?: string[], app?: string, appVersion?: string): Promise<VersionDiffReport> {
  const res = await fetch('/internal/version-diff?' + qs({ sourceDir, country, version, repo, branch, dep, app, appVersion }));
  const data = await res.json();
  if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
  return data as VersionDiffReport;
}

/** Upload progress: bytes sent so far / total; `done` once the body is fully sent (server now processing). */
export interface UploadProgress { loaded: number; total: number; done: boolean; }

/**
 * POST a multipart form via XHR so upload progress can be reported (fetch can't). onProgress fires
 * during the upload; when the body is fully sent it fires once with done=true (the server is then
 * parsing/correlating, which has no client-visible progress).
 */
function postForm<T>(url: string, form: FormData, onProgress?: (p: UploadProgress) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress({ loaded: e.loaded, total: e.total, done: e.loaded >= e.total }); };
      xhr.upload.onload = () => onProgress({ loaded: 1, total: 1, done: true });
    }
    xhr.onload = () => {
      let data: unknown = null;
      try { data = JSON.parse(xhr.responseText); } catch { /* non-JSON error body */ }
      if (xhr.status >= 200 && xhr.status < 300) { resolve(data as T); return; }
      const msg = data && typeof data === 'object' && typeof (data as { error?: unknown }).error === 'string'
        ? (data as { error: string }).error
        : `HTTP ${xhr.status}`;
      reject(new Error(msg));
    };
    xhr.onerror = () => reject(new Error('Network error during upload'));
    xhr.ontimeout = () => reject(new Error('The upload timed out'));
    xhr.send(form);
  });
}

/** Upload one or more output logs / Splunk exports (chunks) and correlate them against the traced APIs. */
export async function analyzeLog(
  file: File | File[],
  params: { version?: string; country?: string; sourceDir?: string; apis?: string[]; backends?: string[]; all?: boolean; app?: string; repo?: string; branch?: string; dep?: string[] },
  onProgress?: (p: UploadProgress) => void,
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
  return postForm<LogAnalysisReport>('/internal/log-analysis', form, onProgress);
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
  onProgress?: (p: UploadProgress) => void,
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
  return postForm<ModuleLogReport[]>('/internal/log-analysis-multi', form, onProgress);
}
