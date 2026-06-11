import dagre from 'dagre';
import type { Edge, Node } from '@xyflow/react';

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
  dagre.layout(g);

  return nodes.map((n) => {
    const p = g.node(n.id);
    const { width, height } = dims(n);
    return { ...n, position: { x: p.x - width / 2, y: p.y - height / 2 } };
  });
}
