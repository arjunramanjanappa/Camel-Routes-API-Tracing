import type { Meta, TraceParams } from '../types';

interface Props {
  params: TraceParams;
  meta: Meta;
  loading: boolean;
  onChange: (patch: Partial<TraceParams>) => void;
  onTrace: () => void;
  onCatalogAll: () => void;
}

export default function ControlPanel({ params, meta, loading, onChange, onTrace, onCatalogAll }: Props) {
  const single = !!(params.api && params.api.trim());
  return (
    <div className="controls">
      <div className="row between">
        <span className={'chip ' + (single ? 'single' : 'catalog')}>
          {single ? 'Single trace' : 'Catalog: all APIs'}
        </span>
        {single && (
          <button className="linkbtn" onClick={onCatalogAll}>Catalog all APIs →</button>
        )}
      </div>

      <label>API path</label>
      <input value={params.api || ''} placeholder="blank = catalog all APIs"
             onChange={(e) => onChange({ api: e.target.value })} />

      <label>Client release version</label>
      <input list="versionList" value={params.version || ''} placeholder="9.4 (blank = BASE / all)"
             onChange={(e) => onChange({ version: e.target.value })} />
      <datalist id="versionList">{meta.versions.map((v) => <option key={v} value={v} />)}</datalist>

      <label>Transfer type (optional)</label>
      <input list="branchList" value={params.transferType || ''} placeholder="OWN / INTRA / INTER (blank = all)"
             onChange={(e) => onChange({ transferType: e.target.value })} />
      <datalist id="branchList">{meta.transferTypes.map((v) => <option key={v} value={v} />)}</datalist>

      <label>Country</label>
      <select value={params.country || ''} onChange={(e) => onChange({ country: e.target.value })}>
        <option value="">(all countries)</option>
        {meta.countries.map((c) => <option key={c} value={c}>{c}</option>)}
      </select>
      <div className="sub">Scope to one bootstrap (e.g. SG, MY) via its imports / routeContextRef.</div>

      <label>Source directory</label>
      <input value={params.sourceDir || ''} placeholder="defaults to server config"
             onChange={(e) => onChange({ sourceDir: e.target.value })} />

      <button className="trace" disabled={loading} onClick={onTrace}>{loading ? 'Scanning…' : 'Trace'}</button>
    </div>
  );
}
