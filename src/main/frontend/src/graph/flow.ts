import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { Derived } from '../graphModel';

function estWidth(label: string): number {
  return Math.min(300, Math.max(140, label.length * 7.2 + 60));
}

/** Map our derived graph model to React Flow nodes + edges. */
export function toFlow(derived: Derived, dir: 'LR' | 'TB'): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = derived.nodes.map((n) => {
    const type = n.type === 'API' ? 'apiNode' : n.type === 'BACKEND' ? 'backendNode' : 'routeNode';
    const w = estWidth(n.label);
    const h = n.type === 'ROUTE' ? 64 : 48;
    return { id: n.id, type, position: { x: 0, y: 0 }, data: { ...n, dir, w, h } };
  });

  const edges: Edge[] = derived.edges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label || undefined,
    animated: e.async,
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: '#94a3b8' },
    style: { stroke: '#94a3b8', strokeWidth: 1.5 },
    labelStyle: { fontSize: 10, fontWeight: 700, fill: '#b45309' },
    labelBgStyle: { fill: '#ffffff', fillOpacity: 0.92 },
    labelBgPadding: [3, 2] as [number, number],
  }));

  return { nodes, edges };
}
