import { useState } from 'react';
import type { AnalyzeResponse, TraceResponse } from '../types';
import { baseCount, inScopeCount, isNaVersion } from '../catalog';

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
            <span className={'pill ' + (d.baseFallback ? 'na' : 'versioned')}
                  title={d.baseFallback ? 'No versioned route — resolved to base (N/A)' : undefined}>
              {d.baseFallback ? 'Base' : 'Release ' + (d.resolvedVersion || '')}
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

  return <Catalog cat={data} onOpenApi={onOpenApi} />;
}

function groupClass(version: string): string {
  if (version === '(no route found)') return 'none';
  if (version === 'N/A') return 'na';
  if (version === 'BASE') return 'base';
  return 'versioned';
}

function Catalog({ cat, onOpenApi }: { cat: Extract<AnalyzeResponse, { mode: 'catalog' }>; onOpenApi: Props['onOpenApi'] }) {
  // In scope for the release = APIs on a real release route. For a concrete release the N/A
  // (base-fallback) APIs are shown but NOT counted here — see catalog.ts.
  const impacted = inScopeCount(cat);
  const base = baseCount(cat);
  const concrete = !isNaVersion(cat.requestedVersion) && !cat.unversioned;
  // "No route found" = ONLY the genuinely route-less APIs (that group).
  const noRoute = cat.groups.find((g) => g.version === '(no route found)')?.traces.length ?? 0;
  const reqVer = cat.requestedVersion && cat.requestedVersion.trim() ? cat.requestedVersion.trim() : '';

  // Collapsed by default, so the catalog reads as an overview; the release group opens first,
  // N/A / no-route stay collapsed (they're base/noise for a concrete release).
  const [open, setOpen] = useState<Set<string>>(() => new Set(
    cat.groups.filter((g) => g.version !== 'N/A' && g.version !== '(no route found)').map((g) => g.version),
  ));
  const toggle = (v: string) => setOpen((prev) => {
    const next = new Set(prev);
    if (next.has(v)) next.delete(v); else next.add(v);
    return next;
  });

  return (
    <>
      <div className="panel">
        <h2>Catalog{cat.country ? ' · ' + cat.country : ''}</h2>
        <div className="catalog-stats">
          <div className="cstat impacted">
            <span className="cstat-num">{impacted}</span>
            <span className="cstat-label">In scope{reqVer ? ` · Release ${reqVer}` : ''}</span>
          </div>
          {concrete && base > 0 && (
            <div className="cstat na">
              <span className="cstat-num">{base}</span>
              <span className="cstat-label">Base (not counted)</span>
            </div>
          )}
          {noRoute > 0 && (
            <div className="cstat muted">
              <span className="cstat-num">{noRoute}</span>
              <span className="cstat-label">No route found</span>
            </div>
          )}
        </div>
        {concrete && base > 0 && (
          <div className="sub">Release <b>{reqVer}</b> touches <b>{impacted}</b> API{impacted === 1 ? '' : 's'}. The other <b>{base}</b> have no {reqVer} route and resolve to their <b>base</b> route — listed below but not counted as in-scope.</div>
        )}
      </div>
      {cat.groups.map((g) => {
        const cls = groupClass(g.version);
        const isOpen = open.has(g.version);
        const label = g.version === 'N/A' ? 'Base' : g.version === '(no route found)' ? g.version : 'Release ' + g.version;
        return (
          <div className={'panel catalog-group ' + cls} key={g.version}>
            <button type="button" className="catalog-group-head" aria-expanded={isOpen} onClick={() => toggle(g.version)}>
              <span className="collapse-caret">{isOpen ? '▾' : '▸'}</span>
              <span className="catalog-group-title">{label}</span>
              <span className={'pill ' + cls}>{g.traces.length} API{g.traces.length === 1 ? '' : 's'}</span>
            </button>
            {isOpen && g.traces.map((t, i) => {
              const open2 = () => onOpenApi(t.api || t.operationName || '', cat.requestedVersion && cat.requestedVersion.trim() ? cat.requestedVersion : t.resolvedVersion);
              return (
                <div className="entry" key={i}>
                  <div className="body" onClick={open2}>
                    <div className="op">{t.operationName}
                      {t.resolvedRoute && (
                        <span className={'pill ' + (t.baseFallback ? 'na' : 'versioned')}>
                          {t.baseFallback ? 'Base' : 'R' + (t.resolvedVersion || '')}
                        </span>
                      )}
                    </div>
                    <div className="meta">{t.api || ''}</div>
                    <div className="kv">
                      {t.resolvedRoute ? <code>{t.resolvedRoute}</code> : <span className="warn">no route</span>}
                      {t.backendApis && t.backendApis.length > 0 && ` · ${t.backendApis.length} backend${t.backendApis.length > 1 ? 's' : ''}`}
                    </div>
                  </div>
                  <button className="minibtn" onClick={open2} title="Open this API as its own trace">Open ▶</button>
                </div>
              );
            })}
          </div>
        );
      })}
      <Warnings items={cat.warnings} />
    </>
  );
}
