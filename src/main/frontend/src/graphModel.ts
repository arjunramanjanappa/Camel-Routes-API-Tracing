import type { RouteGraph } from './types';

export const COLORS: Record<string, string> = {
  api: '#2563eb',
  versioned: '#059669',
  base: '#d97706',
  shared: '#0891b2',
  backend: '#ea580c',
};

export const ROLE_LABEL: Record<string, string> = {
  api: 'API',
  versioned: 'Versioned route',
  base: 'BASE route',
  shared: 'Shared / host route',
  backend: 'Backend API',
};

const VER_PREFIX = /^R[\d.]+_/;

export function routeVersion(name: string): string | null {
  const m = name.match(VER_PREFIX);
  return m ? m[0].slice(1, -1) : null;
}

function displayLabel(type: string, label: string): string {
  if (type === 'ROUTE') return label; // keep version-bearing id
  if (type === 'BACKEND') return label.replace(/^\{\{[^}]+\}\}/, '').replace(/^https?:\/\/[^/]+/, '') || label;
  return label.replace(/\s*\[.*\]$/, '');
}

export interface DerivedNode {
  id: string;
  type: string;
  full: string;
  label: string;
  role: string;
  version: string;
  clientMatch: boolean;
  host: boolean;
  isEntry: boolean;
  source: string;
  reached: string;
  serviceVersion: string;
}

export interface DerivedEdge {
  id: string;
  source: string;
  target: string;
  label: string;
  async: boolean;
}

export interface Derived {
  nodes: DerivedNode[];
  edges: DerivedEdge[];
  byId: Map<string, DerivedNode>;
}

export function derive(graph: RouteGraph, opNames: string[], clientVersion: string): Derived {
  const entry = new Set<string>();
  const reachedBy: Record<string, string[]> = {};
  (graph.edges || []).forEach((e) => {
    if (String(e.from).startsWith('api:')) entry.add(e.to);
    if (e.label) (reachedBy[e.to] = reachedBy[e.to] || []).push(e.label);
  });

  const roleOf = (type: string, label: string): string => {
    if (type === 'API') return 'api';
    if (type === 'BACKEND') return 'backend';
    if (VER_PREFIX.test(label)) return 'versioned';
    if (opNames.includes(label)) return 'base';
    return 'shared';
  };

  const nodes: DerivedNode[] = (graph.nodes || []).map((n) => {
    const ver = n.type === 'ROUTE' ? routeVersion(n.label) || '' : '';
    return {
      id: n.id,
      type: n.type,
      full: n.label,
      label: displayLabel(n.type, n.label),
      role: roleOf(n.type, n.label),
      version: ver,
      clientMatch: !!clientVersion && ver === clientVersion,
      host: !!(n.data && n.data.host),
      isEntry: entry.has(n.id),
      source: (n.data && n.data.source) || '',
      reached: (reachedBy[n.id] || []).join(', '),
      serviceVersion: (n.data && n.data.serviceVersion) || '',
    };
  });

  const edges: DerivedEdge[] = (graph.edges || []).map((e, i) => ({
    id: 'e' + i,
    source: e.from,
    target: e.to,
    label: e.label || '',
    async: !!e.label && e.label.indexOf('async') >= 0,
  }));

  const byId = new Map(nodes.map((n) => [n.id, n]));
  return { nodes, edges, byId };
}

export function opNamesOf(data: { mode: string; operationName?: string; groups?: { traces: { operationName?: string }[] }[] }): string[] {
  if (!data) return [];
  if (data.mode === 'catalog') {
    const s = new Set<string>();
    (data.groups || []).forEach((g) => g.traces.forEach((t) => t.operationName && s.add(t.operationName)));
    return [...s];
  }
  return data.operationName ? [data.operationName] : [];
}
