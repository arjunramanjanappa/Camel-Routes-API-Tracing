import type { DerivedNode } from '../graphModel';
import { COLORS, ROLE_LABEL } from '../graphModel';

function sourceLabel(s: string): string {
  if (s === 'camel') return 'Camel RouteDefinition';
  if (s === 'dom') return 'DOM fallback parser';
  if (s === 'not-found') return 'not found in source';
  return s || '—';
}

export default function DetailPanel({ node, onClose }: { node: DerivedNode; onClose: () => void }) {
  return (
    <div className="panel">
      <button className="detail-close" title="close" onClick={onClose}>✕</button>
      <h2>Selected node</h2>
      <span className="role" style={{ background: COLORS[node.role] }}>{ROLE_LABEL[node.role]}</span>
      <div className="kv" style={{ marginTop: 8 }}>
        <b>{node.type === 'BACKEND' ? 'Endpoint' : 'Name'}:</b><br /><code>{node.full}</code>
      </div>
      {node.type === 'ROUTE' && (
        <>
          <div className="kv">
            <b>Version:</b> {node.version ? 'R' + node.version : 'BASE / un-versioned'}{' '}
            {node.clientMatch && <span className="pill" style={{ background: '#fce7f3', color: '#9d174d' }}>client version</span>}
          </div>
          <div className="kv"><b>Entry route:</b> {node.isEntry ? 'yes' : 'no'}</div>
          {node.host && (
            <div className="kv"><b>Host call:</b> yes <span className="pill" style={{ background: '#cffafe', color: '#155e75' }}>CamelHttpUri</span></div>
          )}
          <div className="kv"><b>Loaded via:</b> {sourceLabel(node.source)}</div>
        </>
      )}
      {node.reached && <div className="kv"><b>Reached via branch:</b> {node.reached}</div>}
    </div>
  );
}
