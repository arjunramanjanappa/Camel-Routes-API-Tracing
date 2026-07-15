import SourceFields from './SourceFields';
import type { ModuleSource } from '../modules';
import { newModule, moduleLabel } from '../modules';

interface Props {
  modules: ModuleSource[];
  onChange: (modules: ModuleSource[]) => void;
  /** Resolved pom module names by module id (after an analysis), for the row header. */
  names?: Record<string, string>;
}

/**
 * The multi-module source list: add each repo (Mighty + its sub-modules), each its own
 * Local/Bitbucket source. Analysed together for the same release + country and grouped by module.
 */
export default function ModulesEditor({ modules, onChange, names = {} }: Props) {
  const update = (id: string, patch: Partial<ModuleSource>) =>
    onChange(modules.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  const remove = (id: string) => onChange(modules.filter((m) => m.id !== id));
  const add = () => onChange([...modules, newModule()]);

  return (
    <div className="modules-editor">
      <div className="modules-head">
        <label>Modules <span className="muted">({modules.length})</span></label>
        <button type="button" className="addmod" onClick={add}>＋ Add module</button>
      </div>
      {modules.map((m, i) => (
        <div className="module-row" key={m.id}>
          <div className="module-bar">
            <span className="module-name">{names[m.id] || moduleLabel(m) || `Module ${i + 1}`}</span>
            {i === 0 && <span className="tag entry">main / entry</span>}
            {modules.length > 1 && (
              <button type="button" className="linkbtn module-x" onClick={() => remove(m.id)}>remove</button>
            )}
          </div>
          <div className="module-fields">
            <SourceFields value={m} onChange={(patch) => update(m.id, patch)} bar />
          </div>
        </div>
      ))}
    </div>
  );
}
