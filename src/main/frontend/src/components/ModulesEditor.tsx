import type { ModuleSource } from '../modules';
import { newModule, moduleLabel, moduleValid } from '../modules';

interface Props {
  modules: ModuleSource[];
  onChange: (modules: ModuleSource[]) => void;
  /** Resolved pom module names by module id (after an analysis), for the row/chip labels. */
  names?: Record<string, string>;
  open: boolean;
  onToggleOpen: () => void;
  /** App-config integration (see useAppModules). When provided, the header shows the config state + actions. */
  fromConfig?: boolean;      // current list matches the saved config
  hasConfig?: boolean;       // a config exists for this app
  hasLocal?: boolean;        // list contains a Local source (warn before saving as the shared default)
  onReset?: () => void;      // discard the local override, reload from config
  onSaveDefault?: () => void; // write the current list back to the config file
  saving?: boolean;
}

/**
 * Compact multi-module source list: one line per module (name · Local/Bitbucket · source · remove),
 * scrollable when there are many. Collapses to a row of chips (used after an analysis) so the
 * results below aren't pushed off-screen; the header toggles editing back open.
 */
export default function ModulesEditor({ modules, onChange, names = {}, open, onToggleOpen,
                                        fromConfig, hasConfig, hasLocal, onReset, onSaveDefault, saving }: Props) {
  const update = (id: string, patch: Partial<ModuleSource>) =>
    onChange(modules.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  const remove = (id: string) => onChange(modules.filter((m) => m.id !== id));
  const add = () => onChange([...modules, newModule()]);
  const label = (m: ModuleSource) => names[m.id] || moduleLabel(m);

  const configAware = onReset != null || onSaveDefault != null;
  const saveDefault = () => {
    if (!onSaveDefault) return;
    if (hasLocal && !window.confirm('This list includes a Local source path, which is specific to this machine and won’t resolve for teammates. Save it as the shared default anyway?')) return;
    onSaveDefault();
  };

  return (
    <div className="modules-editor">
      <div className="modules-head">
        <button type="button" className="mod-toggle" onClick={onToggleOpen}
                aria-expanded={open} title={open ? 'Collapse modules' : 'Edit modules'}>
          <span className="caret">{open ? '▾' : '▸'}</span> Modules <span className="muted">({modules.length})</span>
        </button>
        {configAware && (
          <span className={'cfg-chip' + (fromConfig ? ' on' : '')}
                title={fromConfig ? 'This list matches the saved config' : 'You have unsaved edits to the module list'}>
            {fromConfig ? 'from config' : 'edited · not saved'}
          </span>
        )}
        {open ? (
          <>
            <span className="mod-head-spacer" />
            {configAware && (
              <>
                <button type="button" className="mod-cfgbtn" onClick={onReset} disabled={!hasConfig || saving}
                        title="Discard your edits and reload the module list from the config">↺ Reset to config</button>
                <button type="button" className="mod-cfgbtn" onClick={saveDefault} disabled={saving || !modules.some(moduleValid)}
                        title="Save this module list back to the config as the default for everyone">
                  {saving ? 'Saving…' : '💾 Save as default'}
                </button>
              </>
            )}
            <button type="button" className="addmod" onClick={add}>＋ Add module</button>
          </>
        ) : (
          <div className="mod-chiprow">
            {modules.map((m) => (
              <span key={m.id} className={'mchip' + (moduleValid(m) ? '' : ' invalid')}>{label(m)}</span>
            ))}
          </div>
        )}
      </div>

      {open && (
        <div className="modules-list">
          {modules.map((m, i) => (
            <div className="module-crow" key={m.id}>
              <span className="module-idx">{i + 1}</span>
              <span className="module-cname" title={label(m)}>
                {label(m)}{i === 0 && <span className="tag entry">main</span>}
              </span>
              <div className="seg mini">
                <button type="button" className={m.sourceType === 'local' ? 'on' : ''}
                        onClick={() => update(m.id, { sourceType: 'local' })}>Local</button>
                <button type="button" className={m.sourceType === 'bitbucket' ? 'on' : ''}
                        onClick={() => update(m.id, { sourceType: 'bitbucket' })}>Bitbucket</button>
              </div>
              {m.sourceType === 'local'
                ? <input className="module-inp grow" placeholder="path to module source"
                         value={m.sourceDir} onChange={(e) => update(m.id, { sourceDir: e.target.value })} />
                : <>
                    <input className="module-inp grow" placeholder="bitbucket repo URL"
                           value={m.repo} onChange={(e) => update(m.id, { repo: e.target.value })} />
                    <input className="module-inp" style={{ width: 130 }} placeholder="branch / tag"
                           value={m.branch} onChange={(e) => update(m.id, { branch: e.target.value })} />
                  </>}
              <button type="button" className="module-del" title="Remove module"
                      disabled={modules.length === 1} onClick={() => remove(m.id)}>×</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
