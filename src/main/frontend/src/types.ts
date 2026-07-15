export type NodeType = 'API' | 'ROUTE' | 'BACKEND';

export interface GraphNode {
  id: string;
  label: string;
  type: NodeType;
  data?: { source?: string; host?: boolean; serviceVersion?: string } | null;
}

export interface GraphEdge {
  from: string;
  to: string;
  label?: string | null;
}

export interface RouteGraph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface TraceResponse {
  mode: 'single';
  api?: string;
  requestedVersion?: string;
  transferType?: string;
  country?: string;
  availableCountries: string[];
  operationName?: string;
  command?: string;
  resolvedVersion?: string;
  resolvedRoute?: string;
  baseFallback: boolean;
  flow: string[];
  backendApis: string[];
  warnings: string[];
  /** Imports/routes that couldn't be resolved even after dependencies were added — needs a human. */
  needsReview?: string[];
  graph: RouteGraph;
}

export interface VersionGroup {
  version: string;
  traces: TraceResponse[];
}

export interface CatalogResponse {
  mode: 'catalog';
  requestedVersion?: string;
  transferType?: string;
  country?: string;
  /** The module's pom.xml artifactId (else source folder) — for grouping multi-module analyses. */
  moduleName?: string;
  /** True when the repo has no versioned routes, so it was analysed at N/A (latest). */
  unversioned?: boolean;
  availableCountries: string[];
  operationCount: number;
  versionsFound: string[];
  groups: VersionGroup[];
  warnings: string[];
  needsReview?: string[];
  graph: RouteGraph;
}

export type AnalyzeResponse = TraceResponse | CatalogResponse;

export interface Meta {
  countries: string[];
  versions: string[];
  transferTypes: string[];
}

export interface ApiImpact {
  api: string;
  operation: string;
  command?: string;
  resolvedRoute?: string;
  resolvedVersion?: string;
  baseFallback: boolean;
  routes: string[];
  backends: string[];
  hosts: string[];
  backendVersions?: Record<string, string>;
  backendHosturls?: Record<string, string>;
}

export interface ImpactIndex {
  version?: string;
  country?: string;
  /** Auto-detected SPL-Secure (intercepted-UFW) flavour — drives the secure Splunk query shape. */
  commandDispatch?: boolean;
  apis: ApiImpact[];
  allRoutes: string[];
  allBackends: string[];
  allHosts: string[];
  routeBackends?: Record<string, string[]>;
  warnings: string[];
  needsReview?: string[];
}

export type SourceType = 'local' | 'bitbucket';

/** An optional extra source root that supplies XMLs the primary source imports but doesn't contain. */
export interface DepSource {
  sourceType: SourceType;
  sourceDir: string;
  repo: string;
  branch: string;
}

export interface TraceParams {
  api?: string;
  version?: string;
  transferType?: string;
  country?: string;
  sourceDir?: string;
  sourceType?: SourceType;
  repo?: string;
  branch?: string;
  /** Encoded dependency sources: `local:<path>` or `bit:<repo>|<branch>`. */
  dep?: string[];
  /** Selected application flavour (Mighty / SPL / SPL-Secure) — drives framework-specific resolution. */
  app?: string;
}

// --- release diff (version comparison) ---

export type DiffStatus = 'NEW' | 'CHANGED' | 'UNCHANGED';

export interface RouteStepDiff {
  routeBase: string;
  targetRoute?: string | null;
  lowerRoute?: string | null;
  added: string[];
  removed: string[];
  changedBy?: string[];
}

export interface BackendVersionChange {
  backend: string;
  fromVersion?: string | null;
  toVersion?: string | null;
}

export interface ApiDiff {
  api: string;
  operation: string;
  targetRoute?: string | null;
  targetVersion?: string | null;
  lowerRoute?: string | null;
  lowerVersion?: string | null;
  /** Diff status, or 'SNAPSHOT' for an N/A snapshot row (not a comparison). */
  status: DiffStatus | 'SNAPSHOT';
  routeDiffs: RouteStepDiff[];
  addedRoutes: string[];
  removedRoutes: string[];
  backendVersionChanges: BackendVersionChange[];
  payloadChange?: PayloadChange | null;
  note?: string | null;
  authors?: string[];
}

export interface PayloadChange {
  addedKeys: string[];
  removedKeys: string[];
}

export interface VersionDiffReport {
  mode: 'version-diff';
  version?: string | null;
  country?: string | null;
  changedCount: number;
  newCount: number;
  unchangedCount: number;
  /** True for the N/A snapshot: `apis` are the latest/base routes in scope, not a diff. */
  snapshot?: boolean;
  snapshotCount?: number;
  apis: ApiDiff[];
  warnings: string[];
  needsReview?: string[];
}

// --- log / Splunk correlation ---

export type LogStatus =
  | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'PARTIAL' | 'INDETERMINATE' | 'NOT_TESTED';

export interface BackendCallResult {
  backend: string;
  observedPath?: string | null;
  status: LogStatus;
  latencyMs?: number | null;
  responseCode?: string | null;
  responseDescription?: string | null;
  expectedServiceVersion?: string | null;
  loggedServiceVersion?: string | null;
  serviceVersionOk?: boolean | null;
}

export interface ApiLogResult {
  api: string;
  operation: string;
  resolvedRoute?: string | null;
  clientVersion?: string | null;
  status: LogStatus;
  tested: boolean;
  feLatencyMs?: number | null;
  responseCode?: string | null;
  responseDescription?: string | null;
  attempts: number;
  successCount: number;
  failureCount: number;
  latestAt?: string | null;
  correlationId?: string | null;
  note?: string | null;
  backends: BackendCallResult[];
  /** Failed attempts grouped by response code / failure reason → count, most-frequent first. */
  failuresByCode?: Record<string, number> | null;
}

export interface BackendLogResult {
  backend: string;
  status: LogStatus;
  tested: boolean;
  latencyMs?: number | null;
  responseCode?: string | null;
  responseDescription?: string | null;
  attempts: number;
  successCount: number;
  failureCount: number;
  latestAt?: string | null;
  correlationId?: string | null;
  note?: string | null;
  expectedServiceVersion?: string | null;
  loggedServiceVersion?: string | null;
  serviceVersionOk?: boolean | null;
  /** Failed calls grouped by response code / failure reason → count, most-frequent first. */
  failuresByCode?: Record<string, number> | null;
}

export interface LogAnalysisReport {
  uploadType: string;
  clientVersion?: string | null;
  country?: string | null;
  linesScanned: number;
  matchedLines: number;
  transactions: number;
  unparsedLines: number;
  apis: ApiLogResult[];
  backends: BackendLogResult[];
  warnings: string[];
}
