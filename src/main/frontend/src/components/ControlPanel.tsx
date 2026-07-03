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

      <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
      <input list="countryList" value={params.country || ''} placeholder="SG / MY / ID / TH / VN"
             onChange={(e) => onChange({ country: e.target.value })} />
      <datalist id="countryList">{meta.countries.map((c) => <option key={c} value={c} />)}</datalist>
      <div className="sub">Scope to one bootstrap (e.g. SG, MY) via its imports / routeContextRef.</div>

      <label>Source directory <span style={{ color: '#dc2626' }}>*</span></label>
      <input value={params.sourceDir || ''} placeholder="path to the framework source"
             onChange={(e) => onChange({ sourceDir: e.target.value })} />

      <button className="trace" disabled={loading || !(params.country || '').trim() || !(params.sourceDir || '').trim()} onClick={onTrace}
              title={!(params.sourceDir || '').trim() ? 'Enter a source directory' : !(params.country || '').trim() ? 'Enter a country first' : ''}>
        {loading ? 'Scanning…' : 'Trace'}
      </button>
    </div>
  );
}
