import dagre from 'dagre';
import type { Edge, Node } from '@xyflow/react';

const ENTRY_ROOT = '__entry_root__';

/** Compute hierarchical positions for React Flow nodes using dagre. */
export function layoutGraph(nodes: Node[], edges: Edge[], dir: 'LR' | 'TB'): Node[] {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: dir, nodesep: 30, ranksep: 96, marginx: 24, marginy: 24 });
  g.setDefaultEdgeLabel(() => ({}));

  const dims = (n: Node) => ({
    width: Number((n.data as Record<string, unknown>).w) || 170,
    height: Number((n.data as Record<string, unknown>).h) || 52,
  });

  nodes.forEach((n) => g.setNode(n.id, dims(n)));
  edges.forEach((e) => g.setEdge(e.source, e.target));

  // Line up every entry (API) node in one column (LR) / row (TB). Dagre's default
  // ranker otherwise pulls each API to wherever its first route sits, so when several
  // APIs share downstream routes their start points scatter across ranks. Tie all API
  // nodes to a single heavily-weighted invisible root: that pins them to the same rank.
  // The root is never returned (we map positions over the original nodes), so it and its
  // edges never render — routes still fan out to the right of the aligned entry column.
  const apiIds = nodes.filter((n) => n.type === 'apiNode').map((n) => n.id);
  if (apiIds.length > 1) {
    g.setNode(ENTRY_ROOT, { width: 1, height: 1 });
    apiIds.forEach((id) => g.setEdge(ENTRY_ROOT, id, { weight: 1000, minlen: 1 }));
  }

  dagre.layout(g);

  return nodes.map((n) => {
    const p = g.node(n.id);
    const { width, height } = dims(n);
    return { ...n, position: { x: p.x - width / 2, y: p.y - height / 2 } };
  });
}
