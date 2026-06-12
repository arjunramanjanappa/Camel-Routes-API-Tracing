import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { DerivedNode } from '../graphModel';

export type FlowNodeData = DerivedNode & {
  dir: 'LR' | 'TB';
  w: number;
  h: number;
  dimmed?: boolean;
  sel?: boolean;
  match?: boolean;
};

function Handles({ dir }: { dir: 'LR' | 'TB' }) {
  const target = dir === 'LR' ? Position.Left : Position.Top;
  const source = dir === 'LR' ? Position.Right : Position.Bottom;
  return (
    <>
      <Handle type="target" position={target} className="rf-handle" />
      <Handle type="source" position={source} className="rf-handle" />
    </>
  );
}

function cls(d: FlowNodeData, base: string) {
  return ['rf-node', base, d.dimmed && 'dim', d.sel && 'sel', d.match && 'match'].filter(Boolean).join(' ');
}

export function ApiNode(props: NodeProps) {
  const d = props.data as unknown as FlowNodeData;
  return (
    <div className={cls(d, 'api')} style={{ minWidth: d.w }}>
      <div className="rf-kind">◍ API</div>
      <div className="rf-title">{d.label}</div>
      <Handles dir={d.dir} />
    </div>
  );
}

export function RouteNode(props: NodeProps) {
  const d = props.data as unknown as FlowNodeData;
  return (
    <div className={cls(d, 'route ' + d.role)} style={{ minWidth: d.w }}>
      <div className="rf-title">{d.label}</div>
      <div className="rf-chips">
        {d.version && <span className="rf-chip ver">R{d.version}</span>}
        {d.host && <span className="rf-chip host">⬡ host</span>}
        {d.isEntry && <span className="rf-chip entry">★ entry</span>}
        {d.clientMatch && <span className="rf-chip client">● client</span>}
      </div>
      <Handles dir={d.dir} />
    </div>
  );
}

export function BackendNode(props: NodeProps) {
  const d = props.data as unknown as FlowNodeData;
  return (
    <div className={cls(d, 'backend')} style={{ minWidth: d.w }}>
      <div className="rf-kind">☁ backend</div>
      <div className="rf-title">{d.label}</div>
      {d.serviceVersion && (
        <div className="rf-chips"><span className="rf-chip svc" title="backend service version">svc v{d.serviceVersion}</span></div>
      )}
      <Handles dir={d.dir} />
    </div>
  );
}

export const nodeTypes = { apiNode: ApiNode, routeNode: RouteNode, backendNode: BackendNode };
