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
  /** The module's pom.xml artifactId (else source folder) — for grouping multi-module analyses. */
  moduleName?: string;
  /** True when the repo has no versioned routes, so it was analysed at N/A (latest per API). */
  unversioned?: boolean;
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
  /** True when a pre-existing (BAU) @Component Java class wired into this API's flow was modified by the release. */
  codeChanged?: boolean;
  /** Changed bean classes (with the app-version(s) that changed each + commit authors), e.g.
   *  `statusProcessor (StatusProcessor.java) · 19.18.0 — Alice, Bob`. */
  changedClasses?: string[];
  /** Routes to re-test for that class change, each tagged Current / BAU / Future. */
  impactedRoutes?: ImpactedRoute[];
  /** Test-priority derived from the combined change signals. */
  risk?: 'High' | 'Medium' | 'Low';
  /** Distinct app/commit version(s) that changed this API's classes, e.g. `['19.18.0','19.10.1']`. */
  changedVersions?: string[];
  /** In-place edits the release made to this API's BAU (pre-existing/lower) routes, git-diffed against their
   *  own pre-release version. A removed step is backward-incompatible (High + BC); an added step is Medium. */
  bauRouteEdits?: BauRouteEdit[];
}

/** An in-place change the release made to a BAU (pre-existing/lower) route the old app still runs — its route
 *  body and/or its request payload. Any such change is High risk (it alters existing PROD behaviour). */
export interface BauRouteEdit {
  /** The BAU route id whose definition changed (e.g. `R9.8_getStatusRoute`). */
  route: string;
  /** The owning API's entry → … → route chain (for display). */
  path: string[];
  /** Route-body step lines added by the release (present after, not before). */
  addedSteps: string[];
  /** Route-body step lines removed by the release (present before, not after) — backward-incompatible. */
  removedSteps: string[];
  /** Request-payload keys the release added to a template this route sends. */
  addedKeys: string[];
  /** Request-payload keys the release removed from a template this route sends — backward-incompatible. */
  removedKeys: string[];
  /** Scalar payload values the release changed in place (key present on both sides): key, before → after. */
  changedValues: PayloadValueChange[];
  /** Git-blame authors of the route's current lines. */
  changedBy: string[];
  /** True when the release DELETED the whole BAU route (not just edited it) — a hard, backward-incompatible
   *  removal: the old app that still calls it breaks. The removed* lists then carry its pre-release body. */
  routeRemoved?: boolean;
  /** The request-body template file(s) (repo-relative, e.g. `…/sg/v1/enquiry.ftl`) whose payload the release
   *  changed — named next to the diff so the reviewer knows which .ftl/.vm the result came from. */
  payloadFiles?: string[];
  /** The ACTUAL changed lines (git `diff -w` output, each keeping its `+`/`-` prefix) the release commit(s) made
   *  to the template(s) — the real file difference, not a parsed key summary. */
  payloadDiff?: string[];
  /** The same diff WITH surrounding context lines (` ` prefix) and `@@` hunk locators — shown when the reviewer
   *  toggles "context" on, to see where in the template a change sits. */
  payloadDiffContext?: string[];
}

/** A scalar payload value the release changed in place for a key present on both sides — `key: before → after`. */
export interface PayloadValueChange {
  key: string;
  before: string;
  after: string;
}

/** A route to re-test for a shared-class change, tagged by its relation to the release. */
export interface ImpactedRoute {
  /** The REST API path that owns the entry route (e.g. `/getStatus`), or null when it couldn't be resolved. */
  api?: string | null;
  /** The entry-route → … → changed-route chain (last element is the changed route). */
  routePath: string[];
  category: 'Current' | 'BAU' | 'Future';
}

export interface PayloadChange {
  addedKeys: string[];
  removedKeys: string[];
}

export interface VersionDiffReport {
  mode: 'version-diff';
  version?: string | null;
  country?: string | null;
  /** The module's pom.xml artifactId (else source folder) — for grouping multi-module analyses. */
  moduleName?: string;
  /** True when the repo has no versioned routes, so it was analysed at N/A (snapshot). */
  unversioned?: boolean;
  changedCount: number;
  newCount: number;
  unchangedCount: number;
  /** True for the N/A snapshot: `apis` are the latest/base routes in scope, not a diff. */
  snapshot?: boolean;
  snapshotCount?: number;
  /** The app/commit version whose Java code changes were analysed (e.g. `19.18.0`); null when not requested. */
  appVersion?: string | null;
  /** How many commits carried the app-version token. */
  matchedCommits?: number;
  /** APIs whose Java/route code the app-version release changed. */
  codeChangedCount?: number;
  /** True when appVersion was given but the source isn't a git work tree, so no code-change analysis ran. */
  codeChangeUnavailable?: boolean;
  /** APIs flagged High test-priority (code change, removed payload field, or backend version bump). */
  highRiskCount?: number;
  /** APIs whose payload removed/renamed a field — the backend must stay backward compatible. */
  backwardCompatCount?: number;
  apis: ApiDiff[];
  warnings: string[];
  needsReview?: string[];
  /** Validation findings on impacted .ftl request-body templates (FTL syntax / rendered-JSON structure). */
  templateIssues?: TemplateIssue[];
}

/** A validation finding on an impacted .ftl template. kind: 'SYNTAX' (won't parse) or 'STRUCTURE' (bad JSON). */
export interface TemplateIssue {
  api: string;
  file: string;
  kind: 'SYNTAX' | 'STRUCTURE';
  message: string;
  line: number;
}

// --- log / Splunk correlation ---

export type LogStatus =
  | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'PARTIAL' | 'INDETERMINATE' | 'SKIPPED' | 'NOT_TESTED';

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
  /** True when this row is a BAU reuse of the backend at a lower/unchanged service version — a different
   *  behaviour than the release change. Shown labelled BAU and never counted toward the API's pass/fail. */
  bau?: boolean;
  /** The release route that owns this flow (e.g. R9.14_routeX) — labels the row so two routes on the same
   *  backend+version are distinct. null for a BAU / single-URL row. */
  flowRoute?: string | null;
  /** Total calls observed to this flow across all transactions (0 → not tested). */
  attempts?: number;
  /** Of those, how many succeeded / did not. */
  passed?: number;
  failed?: number;
  /** This flow's failure responseCode → count, most-frequent first (its failure bar breakdown). */
  failuresByCode?: Record<string, number> | null;
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
  /** True when this row is a BAU reuse of the backend at a lower/unchanged service version — labelled BAU,
   *  never counted toward the release readiness tally. */
  bau?: boolean;
}

/** One module's log-verification result in a multi-module release test (backend /log-analysis-multi). */
export interface ModuleLogReport {
  name: string;
  report: LogAnalysisReport | null;
  error?: string | null;
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
  /** Earliest / latest raw timestamp seen in the analysed log (null when none were parseable). */
  logStart?: string | null;
  logEnd?: string | null;
  /** Seconds between logStart and logEnd (the analysed window); -1 when unknown. */
  logSpanSeconds?: number;
}
