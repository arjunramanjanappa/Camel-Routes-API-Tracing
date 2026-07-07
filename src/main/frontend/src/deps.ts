import type { DepSource } from './types';

/** A blank dependency row, defaulting to the given mode (matches the primary source's mode). */
export function blankDep(sourceType: DepSource['sourceType'] = 'local'): DepSource {
  return { sourceType, sourceDir: '', repo: '', branch: '' };
}

/** True once this row has enough entered to be sent (so half-filled rows are ignored). */
export function depComplete(d: DepSource): boolean {
  return d.sourceType === 'bitbucket'
    ? !!(d.repo.trim() && d.branch.trim())
    : !!d.sourceDir.trim();
}

/**
 * Encode one dependency for the wire: `local:<path>` or `bit:<repoUrl>|<branch>`.
 * Returns null for an incomplete row so it's dropped rather than sent half-specified.
 */
export function encodeDep(d: DepSource): string | null {
  if (!depComplete(d)) return null;
  return d.sourceType === 'bitbucket'
    ? `bit:${d.repo.trim()}|${d.branch.trim()}`
    : `local:${d.sourceDir.trim()}`;
}

/** The encoded, complete dependency sources to send as repeated `dep` params. */
export function depParams(deps: DepSource[]): string[] {
  return deps.map(encodeDep).filter((x): x is string => x !== null);
}

// --- per-application persistence (JSON in localStorage) ---

export function loadDeps(key: string): DepSource[] {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((d) => d && typeof d === 'object')
      .map((d) => ({
        sourceType: d.sourceType === 'bitbucket' ? 'bitbucket' : 'local',
        sourceDir: String(d.sourceDir ?? ''),
        repo: String(d.repo ?? ''),
        branch: String(d.branch ?? ''),
      }));
  } catch {
    return [];
  }
}

export function saveDeps(key: string, deps: DepSource[]): void {
  try {
    localStorage.setItem(key, JSON.stringify(deps));
  } catch {
    /* storage unavailable — non-fatal */
  }
}
