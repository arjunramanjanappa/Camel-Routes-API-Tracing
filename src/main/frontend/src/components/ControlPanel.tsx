import type { Meta } from '../types';
import ModulesEditor from './ModulesEditor';
import type { ModuleSource } from '../modules';
import { moduleValid } from '../modules';

interface Props {
  modules: ModuleSource[];
  onModulesChange: (m: ModuleSource[]) => void;
  names?: Record<string, string>;
  country: string;
  version: string;
  meta: Meta;
  loading: boolean;
  modulesOpen: boolean;
  onToggleModules: () => void;
  onField: (patch: { country?: string; version?: string }) => void;
  onAnalyse: () => void;
  /** App-config controls, forwarded to ModulesEditor (see useAppModules). */
  config?: {
    fromConfig?: boolean; hasConfig?: boolean; hasLocal?: boolean;
    onReset?: () => void; onSaveDefault?: () => void; saving?: boolean;
  };
}

/** Release Scope inputs — the module list, then the shared Country + release version for all modules. */
export default function ControlPanel({ modules, onModulesChange, names, country, version, meta, loading, modulesOpen, onToggleModules, onField, onAnalyse, config }: Props) {
  const anyValid = modules.some(moduleValid);
  const noCountry = !country.trim();
  const noVersion = !version.trim();
  return (
    <div className="scope-controls">
      <ModulesEditor modules={modules} onChange={onModulesChange} names={names}
                     open={modulesOpen} onToggleOpen={onToggleModules}
                     fromConfig={config?.fromConfig} hasConfig={config?.hasConfig} hasLocal={config?.hasLocal}
                     onReset={config?.onReset} onSaveDefault={config?.onSaveDefault} saving={config?.saving} />
      <div className="context-bar">
        <div style={{ width: 150 }}>
          <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
          <input list="countryList" value={country} placeholder="SG / MY / ID / TH / VN"
                 onChange={(e) => onField({ country: e.target.value })} />
          <datalist id="countryList">{meta.countries.map((c) => <option key={c} value={c} />)}</datalist>
        </div>
        <div style={{ width: 200 }}>
          <label>Client release version <span style={{ color: '#dc2626' }}>*</span></label>
          <input list="versionList" value={version} placeholder="9.18 or N/A (latest / base)"
                 onChange={(e) => onField({ version: e.target.value })} />
          <datalist id="versionList">
            <option value="N/A" label="latest version of each API (or its default)" />
            {meta.versions.map((v) => <option key={v} value={v} />)}
          </datalist>
        </div>
        <button className="trace" style={{ width: 150, marginTop: 0, alignSelf: 'flex-end' }}
                disabled={loading || noCountry || noVersion || !anyValid} onClick={onAnalyse}
                title={!anyValid ? 'Add at least one module source'
                  : noCountry ? 'Enter a country first'
                    : noVersion ? 'Enter a client release version (or N/A)' : ''}>
          {loading ? 'Scanning…' : 'Analyse modules'}
        </button>
      </div>
    </div>
  );
}
