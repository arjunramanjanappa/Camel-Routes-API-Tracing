import { useEffect, useState } from 'react';

/**
 * Detects a stale UI: the page asks the server which bundle it serves (/internal/ui-version reads the shipped
 * index.html) and compares it with the bundle this page is running. They differ when a newer build was deployed
 * after the page loaded, or when the browser is holding an old copy — in both cases a reload fixes it. Checked on
 * load, whenever the tab becomes visible again, and every few minutes.
 */

const BUNDLE_NAME = /^index-[A-Za-z0-9_-]+\.js$/;

/** The hashed file name of the bundle this page runs (Vite sets import.meta.url to the asset URL); '' in dev. */
function myBundle(): string {
  try {
    const name = new URL(import.meta.url).pathname.split('/').pop() || '';
    return BUNDLE_NAME.test(name) ? name : '';
  } catch {
    return '';
  }
}

export function useStaleBundle(): { stale: boolean; served: string } {
  const [served, setServed] = useState('');
  useEffect(() => {
    let alive = true;
    const check = () => fetch('/internal/ui-version', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((j: { bundle?: string } | null) => { if (alive && j?.bundle) setServed(j.bundle); })
      .catch(() => {});
    check();
    const onVisible = () => { if (document.visibilityState === 'visible') check(); };
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(check, 5 * 60 * 1000);
    return () => { alive = false; document.removeEventListener('visibilitychange', onVisible); window.clearInterval(timer); };
  }, []);
  const mine = myBundle();
  return { stale: !!served && !!mine && served !== mine, served };
}
