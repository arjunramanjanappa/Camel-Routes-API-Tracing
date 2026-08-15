/** Internal application id → the label shown in the UI. The "SPL" app is presented as "TMRW"
 *  (the app-picker rename, v3.298); the internal id stays "SPL" everywhere behind the scenes. */
export function appLabel(app: string | null | undefined): string {
  return app === 'SPL' ? 'TMRW' : (app || '');
}
