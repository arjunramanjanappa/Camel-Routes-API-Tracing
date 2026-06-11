import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
import dagre from 'cytoscape-dagre';
import type { Derived } from '../graphModel';
import { COLORS } from '../graphModel';

cytoscape.use(dagre);

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

const MIN_ZOOM = 0.7;

const LAYOUTS: Record<string, () => cytoscape.LayoutOptions> = {
  'Hierarchical →': () => ({ name: 'dagre', rankDir: 'LR', nodeSep: 26, rankSep: 100 } as cytoscape.LayoutOptions),
  'Hierarchical ↓': () => ({ name: 'dagre', rankDir: 'TB', nodeSep: 26, rankSep: 90 } as cytoscape.LayoutOptions),
  'Tree': () => ({ name: 'breadthfirst', directed: true, spacingFactor: 1.3, padding: 30 } as cytoscape.LayoutOptions),
  'Radial': () => ({ name: 'concentric', minNodeSpacing: 40, padding: 30 } as cytoscape.LayoutOptions),
  'Force': () => ({ name: 'cose', animate: false, idealEdgeLength: 90, nodeRepulsion: 9000, padding: 30 } as cytoscape.LayoutOptions),
};

const RouteGraph = forwardRef<GraphHandle, Props>(function RouteGraph(
  { derived, selectedId, search, onSelect },
  ref
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const appliedSearch = useRef<string | null>(null);
  const appliedSelection = useRef<string | null>(null);
  const [layoutName, setLayoutName] = useState<string>('Hierarchical →');

  const applyFit = (cy: cytoscape.Core) => {
    cy.fit(undefined, 45);
    if (cy.nodes().length <= 2 && cy.zoom() > 1.2) { cy.zoom(1.2); cy.center(); return; }
    if (cy.zoom() < MIN_ZOOM) {
      cy.zoom(MIN_ZOOM);
      const bb = cy.elements().boundingBox({});
      cy.pan({ x: -bb.x1 * MIN_ZOOM + 50, y: -bb.y1 * MIN_ZOOM + 40 });
    }
  };

  const runLayout = (cy: cytoscape.Core, name: string) => {
    const layout = cy.layout((LAYOUTS[name] || LAYOUTS['Hierarchical →'])());
    layout.one('layoutstop', () => applyFit(cy));
    layout.run();
    applyFit(cy);
    setTimeout(() => applyFit(cy), 200);
  };

  const zoomBy = (factor: number) => {
    const cy = cyRef.current;
    if (!cy) return;
    const c = { x: cy.width() / 2, y: cy.height() / 2 };
    cy.animate({ zoom: { level: cy.zoom() * factor, renderedPosition: c } }, { duration: 150 });
  };

  useImperativeHandle(ref, () => ({
    fit: () => cyRef.current?.fit(undefined, 45),
    exportPng: () => {
      const cy = cyRef.current;
      if (!cy) return;
      const a = document.createElement('a');
      a.href = cy.png({ full: true, scale: 2, bg: '#ffffff' });
      a.download = 'route-trace.png';
      a.click();
    },
  }));

  // (Re)build the graph whenever the data changes.
  useEffect(() => {
    if (!containerRef.current) return;
    const elements: cytoscape.ElementDefinition[] = [];
    derived.nodes.forEach((n) =>
      elements.push({
        data: {
          id: n.id, label: n.label, full: n.full, type: n.type, role: n.role,
          isEntry: n.isEntry ? 'yes' : 'no',
          clientMatch: n.clientMatch ? 'yes' : 'no',
          host: n.host ? 'yes' : 'no',
        },
      })
    );
    derived.edges.forEach((e) =>
      elements.push({ data: { id: e.id, source: e.source, target: e.target, label: e.label, async: e.async ? 'yes' : 'no' } })
    );

    cyRef.current?.destroy();
    const cy = cytoscape({
      container: containerRef.current,
      elements,
      wheelSensitivity: 0.2,
      style: ([
        { selector: 'node', style: { 'label': 'data(label)', 'font-size': 13, 'font-weight': 600, 'color': '#fff', 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap', 'text-max-width': 150, 'width': 'label', 'height': 'label', 'padding': '13px', 'shape': 'round-rectangle', 'background-color': '#64748b', 'border-width': 1, 'border-color': 'rgba(15,23,42,0.15)' } },
        { selector: 'node[role="api"]', style: { 'background-color': COLORS.api } },
        { selector: 'node[role="versioned"]', style: { 'background-color': COLORS.versioned } },
        { selector: 'node[role="base"]', style: { 'background-color': COLORS.base } },
        { selector: 'node[role="shared"]', style: { 'background-color': COLORS.shared } },
        { selector: 'node[role="backend"]', style: { 'background-color': COLORS.backend, 'shape': 'cut-rectangle' } },
        { selector: 'node[host="yes"]', style: { 'shape': 'barrel' } },
        { selector: 'node[isEntry="yes"]', style: { 'border-width': 4, 'border-color': '#f59e0b' } },
        { selector: 'node[clientMatch="yes"]', style: { 'outline-width': 4, 'outline-color': '#db2777', 'outline-offset': 2 } },
        { selector: 'edge', style: { 'width': 1.5, 'line-color': '#94a3b8', 'target-arrow-color': '#94a3b8', 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', 'label': 'data(label)', 'font-size': 10, 'font-weight': 700, 'color': '#b45309', 'text-background-color': '#fff', 'text-background-opacity': 1, 'text-background-padding': 2 } },
        { selector: 'edge[async="yes"]', style: { 'line-style': 'dashed' } },
        { selector: '.faded', style: { 'opacity': 0.15 } },
        { selector: 'node.selected', style: { 'border-width': 4, 'border-color': '#1d4ed8' } },
        { selector: 'node.match', style: { 'border-width': 4, 'border-color': '#7c3aed' } },
        { selector: 'node.hover', style: { 'border-width': 3, 'border-color': '#1d4ed8' } },
        { selector: 'edge.hl', style: { 'line-color': '#1d4ed8', 'target-arrow-color': '#1d4ed8', 'width': 3, 'opacity': 1 } },
      ] as any[]),
    });
    cyRef.current = cy;

    const tip = tooltipRef.current!;
    cy.on('tap', 'node', (e) => onSelect(e.target.id()));
    cy.on('tap', (e) => { if (e.target === cy) onSelect(null); });
    cy.on('mouseover', 'node', (e) => {
      const p = e.target.renderedPosition();
      tip.textContent = e.target.data('full');
      tip.style.left = p.x + 'px';
      tip.style.top = p.y + 'px';
      tip.style.display = 'block';
      e.target.addClass('hover');
    });
    cy.on('mouseout', 'node', (e) => { tip.style.display = 'none'; e.target.removeClass('hover'); });
    cy.on('pan zoom drag', () => (tip.style.display = 'none'));

    appliedSearch.current = null;
    appliedSelection.current = null;
    return () => { cy.destroy(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [derived]);

  // Run / re-run layout on data or layout change.
  useEffect(() => {
    if (cyRef.current) runLayout(cyRef.current, layoutName);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [derived, layoutName]);

  // Apply selection / search highlight without rebuilding.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().removeClass('faded hl selected match');
    const q = search.trim().toLowerCase();
    if (q) {
      const matches = cy.nodes().filter((n) => (n.data('full') + ' ' + n.data('label')).toLowerCase().includes(q));
      cy.elements().addClass('faded');
      matches.removeClass('faded').addClass('match');
      matches.connectedEdges().removeClass('faded');
      if (search !== appliedSearch.current && matches.nonempty()) cy.animate({ fit: { eles: matches, padding: 60 } }, { duration: 250 });
    } else if (selectedId) {
      const node = cy.getElementById(selectedId);
      if (node && node.nonempty()) {
        cy.elements().addClass('faded');
        node.closedNeighborhood().removeClass('faded');
        node.connectedEdges().removeClass('faded').addClass('hl');
        node.addClass('selected');
        if (selectedId !== appliedSelection.current) cy.animate({ center: { eles: node } }, { duration: 250 });
      }
    }
    appliedSearch.current = search;
    appliedSelection.current = selectedId;
  }, [selectedId, search]);

  return (
    <div className="graph-wrap">
      <div ref={containerRef} className="cy" />
      <div ref={tooltipRef} className="tooltip" />
      <div className="graph-controls">
        <select value={layoutName} onChange={(e) => setLayoutName(e.target.value)} title="Layout">
          {Object.keys(LAYOUTS).map((k) => <option key={k} value={k}>{k}</option>)}
        </select>
        <button className="minibtn" onClick={() => zoomBy(1 / 1.25)} title="Zoom out">−</button>
        <button className="minibtn" onClick={() => zoomBy(1.25)} title="Zoom in">+</button>
        <button className="minibtn" onClick={() => cyRef.current && applyFit(cyRef.current)} title="Reset view">Reset</button>
      </div>
    </div>
  );
});

export default RouteGraph;
