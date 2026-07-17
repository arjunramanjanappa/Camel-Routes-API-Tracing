import { useCallback, useEffect, useState } from 'react';
import { fetchAppConfig, saveAppConfig, type ConfigModule } from './api';
import { loadModulesForApp, newModule, saveModulesForApp, type ModuleSource } from './modules';

/** localStorage key holding a user's per-app override; its presence means "don't seed from config". */
function overrideKey(app: string) { return `tracer.${app}.modules`; }

/** Compare two module lists by their source coordinates (ignoring client-side ids). */
function sameCoords(a: ModuleSource[], b: ConfigModule[]): boolean {
  if (a.length !== b.length) return false;
  return a.every((m, i) => {
    const c = b[i];
    return (m.sourceType || 'local') === (c.sourceType || 'local')
      && (m.sourceDir || '') === (c.sourceDir || '')
      && (m.repo || '') === (c.repo || '')
      && (m.branch || '') === (c.branch || '');
  });
}

function toModules(cfg: ConfigModule[]): ModuleSource[] {
  return cfg.map((c) => newModule({ sourceType: c.sourceType || 'local', sourceDir: c.sourceDir || '', repo: c.repo || '', branch: c.branch || '' }));
}

/**
 * Per-app module list backed by the server config: prepopulated from config when there is no
 * local override, editable (edits become a per-browser override), with reset-to-config and
 * save-as-default. Shared by all three tabs so the module list stays consistent per app.
 */
export function useAppModules(app: string) {
  const [modules, setModulesState] = useState<ModuleSource[]>(() => loadModulesForApp(app));
  const [config, setConfig] = useState<ConfigModule[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Fetch the config once per app; seed the list from it only when the user has no saved override.
  useEffect(() => {
    let cancelled = false;
    fetchAppConfig().then((cfg) => {
      if (cancelled) return;
      const forApp = cfg[app] || [];
      setConfig(forApp);
      if (!localStorage.getItem(overrideKey(app)) && forApp.length) {
        setModulesState(toModules(forApp));
      }
    }).catch(() => { if (!cancelled) setConfig([]); });
    return () => { cancelled = true; };
  }, [app]);

  const setModules = useCallback((m: ModuleSource[]) => {
    setModulesState(m);
    saveModulesForApp(app, m);   // writing the key marks this app as user-overridden
  }, [app]);

  const resetToConfig = useCallback(() => {
    localStorage.removeItem(overrideKey(app));
    setModulesState((config && config.length) ? toModules(config) : [newModule()]);
    setSaveError(null);
  }, [app, config]);

  const saveAsDefault = useCallback(async () => {
    setSaving(true); setSaveError(null);
    try {
      await saveAppConfig(app, modules.map((m) => ({ sourceType: m.sourceType, sourceDir: m.sourceDir, repo: m.repo, branch: m.branch })));
      setConfig(modules.map((m) => ({ sourceType: m.sourceType, sourceDir: m.sourceDir, repo: m.repo, branch: m.branch })));
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }, [app, modules]);

  // Does the current list still match the saved config? Drives the "from config" vs "edited" chip.
  const configReady = config !== null;
  const fromConfig = configReady && config!.length > 0 && sameCoords(modules, config!);
  const hasConfig = configReady && config!.length > 0;
  // A local list containing a Local source can't resolve on a teammate's machine — warn on save.
  const hasLocal = modules.some((m) => (m.sourceType || 'local') === 'local' && (m.sourceDir || '').trim());

  return { modules, setModules, fromConfig, hasConfig, hasLocal, resetToConfig, saveAsDefault, saving, saveError };
}
