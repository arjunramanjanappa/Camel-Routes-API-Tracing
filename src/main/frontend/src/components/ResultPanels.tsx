import type { AnalyzeResponse, TraceResponse } from '../types';
import SplunkPanel from './SplunkPanel';

interface Props {
  data: AnalyzeResponse;
  onBackToCatalog: () => void;
  onOpenApi: (api: string, version: string | undefined) => void;
}

function Warnings({ items }: { items: string[] }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="panel">
      <h2>Warnings</h2>
      <ul>{items.map((w, i) => <li key={i} className="warn">{w}</li>)}</ul>
    </div>
  );
}

function Single({ d }: { d: TraceResponse }) {
  return (
    <>
      <div className="panel">
        <div className="row between"><h2 style={{ margin: 0 }}>Resolution</h2></div>
        <div className="kv"><b>Operation:</b> {d.operationName || '(unresolved)'}</div>
        <div className="kv"><b>Command:</b> {d.command || '—'}</div>
        <div className="kv">
          <b>Resolved route:</b> <code>{d.resolvedRoute || '—'}</code>{' '}
          {d.resolvedRoute && (
            <span className={'pill ' + (d.baseFallback ? 'base' : 'versioned')}>
              {d.baseFallback ? 'BASE' : 'R' + (d.resolvedVersion || '')}
            </span>
          )}
        </div>
      </div>
      {d.flow.length > 0 && (
        <div className="panel"><h2>Flow</h2><ol>{d.flow.map((r, i) => <li key={i}><code>{r}</code></li>)}</ol></div>
      )}
      {d.backendApis.length > 0 && (
        <div className="panel"><h2>Backend APIs</h2><ul>{d.backendApis.map((b, i) => <li key={i}><code>{b}</code></li>)}</ul></div>
      )}
      {d.operationName && (
        <SplunkPanel
          title="Splunk export — this API"
          frontendApis={[d.api || d.operationName || '']}
          backendApis={d.backendApis}
          hint="Run in Splunk, export the report, then upload it under Impact analysis for correlation."
        />
      )}
    </>
  );
}

export default function ResultPanels({ data, onBackToCatalog, onOpenApi }: Props) {
  if (data.mode === 'single') {
    return (
      <>
        <div className="row between" style={{ marginTop: 12 }}>
          <span className="sub">Single trace</span>
          <button className="linkbtn" onClick={onBackToCatalog}>← Catalog</button>
        </div>
        <Single d={data} />
        <Warnings items={data.warnings} />
      </>
    );
  }

  const cat = data;
  const allFe = [...new Set(cat.groups.flatMap((g) => g.traces.map((t) => t.api || t.operationName || '')).filter(Boolean))];
  const allBe = [...new Set(cat.groups.flatMap((g) => g.traces.flatMap((t) => t.backendApis || [])))];
  return (
    <>
      <div className="panel">
        <h2>Catalog{cat.country ? ' · ' + cat.country : ''}</h2>
        <div className="kv"><b>APIs discovered:</b> {cat.operationCount}</div>
        <div className="kv"><b>Version groups:</b> {(cat.versionsFound || []).join(', ')}</div>
      </div>
      {cat.groups.map((g) => {
        const cls = g.version === '(no route found)' ? 'none' : g.version === 'BASE' ? 'base' : 'versioned';
        return (
          <div className="panel" key={g.version}>
            <h2>{g.version} <span className={'pill ' + cls}>{g.traces.length}</span></h2>
            {g.traces.map((t, i) => {
              const open = () => onOpenApi(t.api || t.operationName || '', cat.requestedVersion && cat.requestedVersion.trim() ? cat.requestedVersion : t.resolvedVersion);
              return (
                <div className="entry" key={i}>
                  <div className="body" onClick={open}>
                    <div className="op">{t.operationName}</div>
                    <div className="meta">{t.api || ''}</div>
                    <div className="kv">
                      {t.resolvedRoute ? <code>{t.resolvedRoute}</code> : <span className="warn">no route</span>}
                      {t.backendApis && t.backendApis.length > 0 && ` · ${t.backendApis.length} backend${t.backendApis.length > 1 ? 's' : ''}`}
                    </div>
                  </div>
                  <button className="minibtn" onClick={open} title="Open this API as its own trace">Open ▶</button>
                </div>
              );
            })}
          </div>
        );
      })}
      {allFe.length > 0 && (
        <SplunkPanel
          title="Splunk export — all APIs"
          frontendApis={allFe}
          backendApis={allBe}
          hint="Run in Splunk, export the report, then upload it under Impact analysis for correlation."
        />
      )}
      <Warnings items={cat.warnings} />
    </>
  );
}
