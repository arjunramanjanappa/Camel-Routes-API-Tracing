import { forwardRef, useEffect, useImperativeHandle, useState, type Ref } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, BackgroundVariant, MiniMap, Controls, Panel,
  useNodesState, useEdgesState, useReactFlow, getNodesBounds, getViewportForBounds,
  type Node, type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { toPng } from 'html-to-image';
import type { Derived } from '../graphModel';
import { COLORS } from '../graphModel';
import { nodeTypes, type FlowNodeData } from '../graph/nodes';
import { toFlow } from '../graph/flow';
import { layoutGraph } from '../graph/layout';

export interface GraphHandle {
  fit: () => void;
  exportPng: () => void;
}

interface Props {
  derived: Derived;
  selectedId: string | null;
  search: string;
  onSelect: (id: string | null) => void;
}

interface Active { nodes: Set<string>; edges: Set<string>; matches: Set<string>; }

function computeActive(derived: Derived, selectedId: string | null, search: string): Active | null {
  const q = search.trim().toLowerCase();
  if (q) {
    const matches = new Set(derived.nodes.filter((n) => (n.full + ' ' + n.label).toLowerCase().includes(q)).map((n) => n.id));
    const edges = new Set(derived.edges.filter((e) => matches.has(e.source) || matches.has(e.target)).map((e) => e.id));
    return { nodes: matches, edges, matches };
  }
  if (selectedId) {
    const nodes = new Set<string>([selectedId]);
    const edges = new Set<string>();
    derived.edges.forEach((e) => {
      if (e.source === selectedId || e.target === selectedId) {
        edges.add(e.id);
        nodes.add(e.source);
        nodes.add(e.target);
      }
    });
    return { nodes, edges, matches: new Set() };
  }
  return null;
}

function Flow({ derived, selectedId, search, onSelect, fref }: Props & { fref: Ref<GraphHandle> }) {
  const [dir, setDir] = useState<'LR' | 'TB'>('LR');
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const rf = useReactFlow();

  // Layout on data / direction change.
  useEffect(() => {
    const f = toFlow(derived, dir);
    setNodes(layoutGraph(f.nodes, f.edges, dir));
    setEdges(f.edges);
    // Keep nodes readable: never auto-zoom below ~0.55 (pan instead of squint).
    const t = setTimeout(() => rf.fitView({ padding: 0.2, minZoom: 0.55, maxZoom: 1.3, duration: 300 }), 50);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [derived, dir]);

  // Apply selection / search highlight (positions preserved).
  useEffect(() => {
    const active = computeActive(derived, selectedId, search);
    setNodes((ns) => ns.map((n) => ({
      ...n,
      data: { ...n.data, dimmed: !!active && !active.nodes.has(n.id), sel: n.id === selectedId, match: !!active && active.matches.has(n.id) },
    })));
    setEdges((es) => es.map((e) => ({ ...e, style: { ...e.style, opacity: active && !active.edges.has(e.id) ? 0.1 : 1 } })));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, search, derived]);

  useImperativeHandle(fref, () => ({
    fit: () => rf.fitView({ padding: 0.2, minZoom: 0.55, maxZoom: 1.3, duration: 300 }),
    exportPng: () => {
      const ns = rf.getNodes();
      if (ns.length === 0) return;
      const bounds = getNodesBounds(ns);
      const w = Math.max(800, Math.min(4000, bounds.width + 240));
      const h = Math.max(600, Math.min(4000, bounds.height + 240));
      const vp = getViewportForBounds(bounds, w, h, 0.2, 2, 0.1);
      const el = document.querySelector('.react-flow__viewport') as HTMLElement | null;
      if (!el) return;
      toPng(el, {
        backgroundColor: '#ffffff', width: w, height: h,
        style: { width: w + 'px', height: h + 'px', transform: `translate(${vp.x}px, ${vp.y}px) scale(${vp.zoom})` },
      }).then((url) => { const a = document.createElement('a'); a.href = url; a.download = 'route-trace.png'; a.click(); });
    },
  }));

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onNodeClick={(_, n) => onSelect(n.id)}
      onPaneClick={() => onSelect(null)}
      minZoom={0.1}
      proOptions={{ hideAttribution: true }}
    >
      <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#e2e8f0" />
      <Controls showInteractive={false} />
      <MiniMap position="top-right" pannable zoomable
               nodeColor={(n) => COLORS[(n.data as unknown as FlowNodeData).role] || '#64748b'} />
      <Panel position="bottom-center">
        <select className="rf-layout-select" value={dir} onChange={(e) => setDir(e.target.value as 'LR' | 'TB')}>
          <option value="LR">Hierarchical →</option>
          <option value="TB">Hierarchical ↓</option>
        </select>
      </Panel>
    </ReactFlow>
  );
}

const RouteGraph = forwardRef<GraphHandle, Props>(function RouteGraph(props, ref) {
  return (
    <div className="graph-wrap">
      <ReactFlowProvider>
        <Flow {...props} fref={ref} />
      </ReactFlowProvider>
    </div>
  );
});

export default RouteGraph;
