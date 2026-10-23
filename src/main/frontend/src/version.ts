/**
 * The release shown in the header, so a user can tell at a glance whether the bundle they are looking at is
 * the latest. Bump it in the same commit as the git tag (vX.Y). The build time comes from Vite at build time,
 * so two builds of the same version are still distinguishable (a stale bundle served from an old target/ shows
 * an older time).
 */
export const APP_VERSION = 'v3.381';

declare const __BUILD_TIME__: string;
/** ISO timestamp of the bundle build (injected by vite.config.ts); empty in a plain type-check. */
export const BUILD_TIME: string = typeof __BUILD_TIME__ === 'string' ? __BUILD_TIME__ : '';

/** "23 Oct 2026, 12:40" in the viewer's locale, or '' when unknown. */
export function buildTimeLabel(): string {
  if (!BUILD_TIME) return '';
  const d = new Date(BUILD_TIME);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
