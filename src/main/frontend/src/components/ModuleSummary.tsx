import type { ModuleResult } from '../modules';

export interface ModuleStat { label: string; value: number | string; tone?: 'good' | 'warn' | 'bad' | 'muted' | 'info'; }

interface Props<T> {
  results: ModuleResult<T>[];
  activeId: string | null;
  onSelect: (id: string) => void;
  /** Per-module headline stats (e.g. Scope: APIs; Test: passed/failed/not-tested; Impact: changed/new). */
  statsOf: (r: ModuleResult<T>) => ModuleStat[];
  /** True when the module is unversioned (analysed at N/A) — shows the amber chip. */
  unversionedOf?: (r: ModuleResult<T>) => boolean;
  /** Export the ONE report covering all modules. Rendered as a single button in the strip header. */
  onExport?: () => void;
  exportLabel?: string;
  exportDisabled?: boolean;
  exportTitle?: string;
  /** Release-wide rollup tiles (aggregate across modules) shown as a band above the cards. */
  rollup?: ModuleStat[];
}

/**
 * The coordinator's at-a-glance strip: one card per module with its headline stats, doubling as
 * the selector — clicking a card shows that module's detail below (in the tab's existing view).
 * The single "export the whole report" button lives in the header here (a common place), so it is
 * clearly one report for all modules — not per selected module.
 */
export default function ModuleSummary<T>({ results, activeId, onSelect, statsOf, unversionedOf, onExport, exportLabel, exportDisabled, exportTitle, rollup }: Props<T>) {
  if (results.length <= 1) return null;
  return (
    <div className="mod-summary">
      {rollup && rollup.length > 0 && (
        <div className="mod-rollup">
          {rollup.map((s, i) => (
            <div key={i} className="mr-tile">
              <div className={'mr-n ' + (s.tone || 'muted')}>{s.value}</div>
              <div className="mr-l">{s.label}</div>
            </div>
          ))}
        </div>
      )}
      <div className="mod-summary-h row between">
        <span>By module <span className="muted">— click a module to see its detail</span></span>
        {onExport && (
          <button className="minibtn" onClick={onExport} disabled={exportDisabled}
                  title={exportTitle || 'Download one PDF report covering every module'}>
            {exportLabel || '⤓ Export PDF'}
          </button>
        )}
      </div>
      <div className="mod-cards">
        {results.map((r) => {
          const na = unversionedOf ? unversionedOf(r) : false;
          return (
            <button key={r.module.id} type="button"
                    className={'mod-card' + (r.module.id === activeId ? ' on' : '')}
                    onClick={() => onSelect(r.module.id)}>
              <div className="mc-name">{r.name}
                {na && <span className="tag na" title="Unversioned — analysed at N/A">N/A</span>}
                {r.error && <span className="tag none" title={r.error}>failed</span>}</div>
              <div className="mc-stats">
                {r.error
                  ? <span className="mc-stat bad">not analysed</span>
                  : statsOf(r).map((s, i) => (
                      <span key={i} className={'mc-stat ' + (s.tone || 'muted')}><b>{s.value}</b> {s.label}</span>
                    ))}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
