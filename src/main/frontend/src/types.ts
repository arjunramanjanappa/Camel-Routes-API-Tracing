export type NodeType = 'API' | 'ROUTE' | 'BACKEND';

export interface GraphNode {
  id: string;
  label: string;
  type: NodeType;
  data?: { source?: string; host?: boolean } | null;
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
  availableCountries: string[];
  operationCount: number;
  versionsFound: string[];
  groups: VersionGroup[];
  warnings: string[];
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
}

export interface ImpactIndex {
  version?: string;
  country?: string;
  apis: ApiImpact[];
  allRoutes: string[];
  allBackends: string[];
  allHosts: string[];
  warnings: string[];
}

export interface TraceParams {
  api?: string;
  version?: string;
  transferType?: string;
  country?: string;
  sourceDir?: string;
}
