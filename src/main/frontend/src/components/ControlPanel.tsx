import type { Meta, TraceParams } from '../types';
import SourceFields, { sourceValid, type SourceState } from './SourceFields';

interface Props {
  params: TraceParams;
  meta: Meta;
  loading: boolean;
  onChange: (patch: Partial<TraceParams>) => void;
  onTrace: () => void;
  onCatalogAll: () => void;
}

/** Release Scope inputs — a horizontal context bar (aligned with Release Test / Release Impact). */
export default function ControlPanel({ params, meta, loading, onChange, onTrace, onCatalogAll }: Props) {
  const single = !!(params.api && params.api.trim());
  const src: SourceState = {
    sourceType: params.sourceType || 'local',
    sourceDir: params.sourceDir || '', repo: params.repo || '', branch: params.branch || '',
  };
  const noCountry = !(params.country || '').trim();
  const noVersion = !(params.version || '').trim();
  return (
    <div className="context-bar">
      <SourceFields value={src} onChange={onChange} bar />
      <div style={{ width: 150 }}>
        <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
        <input list="countryList" value={params.country || ''} placeholder="SG / MY / ID / TH / VN"
               onChange={(e) => onChange({ country: e.target.value })} />
        <datalist id="countryList">{meta.countries.map((c) => <option key={c} value={c} />)}</datalist>
      </div>
      <div style={{ width: 175 }}>
        <label>Client release version <span style={{ color: '#dc2626' }}>*</span></label>
        <input list="versionList" value={params.version || ''} placeholder="9.18 or N/A (latest / base)"
               onChange={(e) => onChange({ version: e.target.value })} />
        <datalist id="versionList">
          <option value="N/A" label="latest per API, else base route (unversioned repos)" />
          {meta.versions.map((v) => <option key={v} value={v} />)}
        </datalist>
      </div>
      <div style={{ width: 190 }}>
        <label>API path{single && (
          <> · <button type="button" className="linkbtn" style={{ fontWeight: 400 }} onClick={onCatalogAll}>catalog all</button></>
        )}</label>
        <input value={params.api || ''} placeholder="blank = catalog all APIs"
               onChange={(e) => onChange({ api: e.target.value })} />
      </div>
      <button className="trace" style={{ width: 120, marginTop: 0, alignSelf: 'flex-end' }}
              disabled={loading || noCountry || noVersion || !sourceValid(src)} onClick={onTrace}
              title={!sourceValid(src) ? 'Enter the source (path or Bitbucket repo + branch)'
                : noCountry ? 'Enter a country first'
                  : noVersion ? 'Enter a client release version (or N/A)' : ''}>
        {loading ? 'Scanning…' : 'Trace'}
      </button>
    </div>
  );
}
