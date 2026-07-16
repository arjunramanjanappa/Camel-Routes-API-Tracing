import type { SourceType } from './types';

/**
 * One module (repo) in a multi-module release analysis. Extends the single-source shape so the
 * existing SourceFields editor can drive each module's Local/Bitbucket coordinates unchanged.
 */
export interface ModuleSource {
  id: string;
  sourceType: SourceType;
  sourceDir: string;
  repo: string;
  branch: string;
}

let seq = 0;
export function newModule(partial?: Partial<ModuleSource>): ModuleSource {
  seq += 1;
  return { id: 'm' + seq + Math.random().toString(36).slice(2, 6), sourceType: 'local', sourceDir: '', repo: '', branch: '', ...partial };
}

/** Load the persisted module list for an app (migrating an old single-source layout to one module). */
export function loadModulesForApp(app: string): ModuleSource[] {
  try {
    const raw = localStorage.getItem(`tracer.${app}.modules`);
    if (raw) { const arr = JSON.parse(raw) as ModuleSource[]; if (Array.isArray(arr) && arr.length) return arr; }
  } catch { /* fall through */ }
  const g = (f: string) => localStorage.getItem(`tracer.${app}.${f}`) || '';
  return [newModule({ sourceType: (g('sourceType') as ModuleSource['sourceType']) || 'local', sourceDir: g('sourceDir'), repo: g('repo'), branch: g('branch') })];
}
export function saveModulesForApp(app: string, modules: ModuleSource[]) {
  localStorage.setItem(`tracer.${app}.modules`, JSON.stringify(modules));
}

/** Is this module's source fully specified for its mode? (Analyse gate.) */
export function moduleValid(m: ModuleSource): boolean {
  return m.sourceType === 'bitbucket'
    ? !!(m.repo && m.repo.trim() && m.branch && m.branch.trim())
    : !!(m.sourceDir && m.sourceDir.trim());
}

/** A convenient display label before the backend returns the pom module name. */
export function moduleLabel(m: ModuleSource): string {
  if (m.sourceType === 'bitbucket') {
    const r = (m.repo || '').replace(/\.git$/, '').replace(/[/\\]+$/, '');
    const slug = r.slice(Math.max(r.lastIndexOf('/'), r.lastIndexOf('\\')) + 1);
    return slug || 'module';
  }
  const d = (m.sourceDir || '').replace(/[/\\]+$/, '');
  return d.slice(Math.max(d.lastIndexOf('/'), d.lastIndexOf('\\')) + 1) || 'module';
}

/** One module's analysis outcome (or an error), tagged with its source and resolved name. */
export interface ModuleResult<T> {
  module: ModuleSource;
  name: string;         // pom module name from the response, else a source-derived label
  result: T | null;     // null when the module's analysis failed
  error?: string;
}

/**
 * Run `fetchOne` for every valid module, in order (sequential — avoids hammering the backend and
 * Bitbucket concurrently). A module that fails is captured as an error instead of aborting the rest.
 * `nameOf` extracts the pom module name from a successful result.
 */
export async function analyzeModules<T>(
  modules: ModuleSource[],
  fetchOne: (m: ModuleSource) => Promise<T>,
  nameOf: (result: T) => string | undefined,
): Promise<ModuleResult<T>[]> {
  const out: ModuleResult<T>[] = [];
  for (const m of modules) {
    if (!moduleValid(m)) continue;
    try {
      const result = await fetchOne(m);
      out.push({ module: m, name: nameOf(result) || moduleLabel(m), result });
    } catch (e) {
      out.push({ module: m, name: moduleLabel(m), result: null, error: e instanceof Error ? e.message : String(e) });
    }
  }
  return out;
}
