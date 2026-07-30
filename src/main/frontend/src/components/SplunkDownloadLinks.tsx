import { useEffect, useMemo, useState } from 'react';
import { fetchSettings } from '../api';
import CopyBtn from './CopyBtn';

const DEFAULT_PAGE = 50000;
const STAGGER_MS = 700;   // space out bulk downloads so the browser's one "allow multiple downloads" prompt settles

/**
 * Builds the paginated Splunk result-download links for a finished search job — automating the offset
 * arithmetic the user does by hand. Given the Job SID and the total result count (page size defaults to
 * 50,000, the per-request cap), it emits one `…/<SID>/results?output_mode=csv&count=<page>&offset=<n>`
 * URL per page. TraceGuard never calls Splunk: the links open in the user's own browser, which is already
 * authenticated to Splunk Web (the `/en-US/splunkd/__raw/...` proxy rides the session cookie), so no
 * export permission or credential is needed here. The downloaded CSV chunk(s) are then attached under
 * "Verify with logs" for correlation.
 *
 * <p>Download All triggers every page (staggered) with a single click. NOTE: the browser does not expose
 * per-file cross-origin download success to the page (a navigation download has no JS result, and a fetch
 * that could read the HTTP status is CORS-blocked), so success is self-tracked: each page has a "downloaded"
 * checkbox and stays one click from a re-download. A failed page typically saves as a small .html error file.
 */
export default function SplunkDownloadLinks() {
  const [base, setBase] = useState('');
  const [sid, setSid] = useState('');
  const [count, setCount] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE);
  const [done, setDone] = useState<Record<number, boolean>>({});
  const [started, setStarted] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchSettings().then((s) => { if (alive && s.splunkUrl) setBase(s.splunkUrl); }).catch(() => { /* offline / no config — inline field still works */ });
    return () => { alive = false; };
  }, []);

  const n = Math.max(0, parseInt(count, 10) || 0);
  const size = Math.max(1, pageSize || DEFAULT_PAGE);
  const pages = n > 0 ? Math.ceil(n / size) : 0;
  const trimmedBase = base.trim().replace(/\/+$/, '');   // one slash is added before the SID
  const s = sid.trim();

  const links = useMemo(() => {
    if (!trimmedBase || !s || pages === 0) return [];
    return Array.from({ length: pages }, (_, i) => {
      const offset = i * size;
      return {
        i,
        from: offset + 1,
        to: Math.min(offset + size, n),
        url: `${trimmedBase}/${encodeURIComponent(s)}/results?output_mode=csv&count=${size}&offset=${offset}`,
      };
    });
  }, [trimmedBase, s, pages, size, n]);

  // Reset the download tracking whenever the target (job/count/page size) changes.
  useEffect(() => { setDone({}); setStarted(false); }, [trimmedBase, s, size, n]);

  const allUrls = links.map((l) => l.url).join('\n');
  const doneCount = links.filter((l) => done[l.i]).length;
  const remaining = links.length - doneCount;

  // A cross-origin download is a navigation: an <a download> click makes the browser fetch it with the
  // Splunk session cookie and save it, without navigating this app away (for an attachment/CSV response).
  const triggerDownload = (url: string) => {
    const a = document.createElement('a');
    a.href = url;
    a.download = '';           // hint download intent (filename is ignored cross-origin — Splunk names it)
    a.rel = 'noopener';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  const downloadAll = () => {
    if (!links.length) return;
    setStarted(true);
    links.forEach((l, idx) => window.setTimeout(() => triggerDownload(l.url), idx * STAGGER_MS));
  };

  const mark = (i: number, v: boolean) => setDone((d) => ({ ...d, [i]: v }));
  const markAll = (v: boolean) => setDone(v ? (Object.fromEntries(links.map((l) => [l.i, true])) as Record<number, boolean>) : {});

  return (
    <div className="spl-block">
      <div className="row between">
        <b>Download results by Job SID</b>
        {links.length > 0 && (
          <span className="row" style={{ gap: 6 }}>
            <button className="minibtn primary" onClick={downloadAll} title="Trigger every page's download at once (your browser will ask once to allow multiple downloads)">
              ⬇ Download all {links.length}
            </button>
            <CopyBtn text={allUrls} label={`Copy all ${links.length} URLs`} />
          </span>
        )}
      </div>
      <div className="sub">
        Run the query above in Splunk, then paste its <b>Job SID</b> and <b>result count</b> — TraceGuard builds the paginated
        CSV download links. Open them (uses your logged-in Splunk Web session) and upload the file(s) under <b>Verify with logs</b>.
      </div>

      <div className="spl-config">
        <div><label>Job SID</label><input value={sid} placeholder="1699999999.12345" spellCheck={false} onChange={(e) => setSid(e.target.value)} /></div>
        <div><label>Result count <span className="muted">(events)</span></label>
          <input value={count} inputMode="numeric" placeholder="e.g. 820000" onChange={(e) => setCount(e.target.value.replace(/[^\d]/g, ''))} /></div>
        <div><label>Page size <span className="muted">(per request)</span></label>
          <input value={String(pageSize)} inputMode="numeric"
                 onChange={(e) => setPageSize(Math.max(1, parseInt(e.target.value.replace(/[^\d]/g, ''), 10) || DEFAULT_PAGE))} /></div>
      </div>

      {!trimmedBase && <div className="sub" style={{ color: 'var(--warn, #b45309)' }}>Set your <b>Splunk base URL</b> in ⚙ Config (or paste it below) to build links.</div>}
      <input className="env-custom" value={base} spellCheck={false}
             placeholder="https://host:8000/en-US/splunkd/__raw/services/search/jobs/"
             onChange={(e) => setBase(e.target.value)} />

      {links.length > 0 ? (
        <>
          <div className="row between" style={{ marginTop: 8 }}>
            <div className="sub" style={{ margin: 0 }}>
              {n.toLocaleString()} rows → <b>{pages}</b> file{pages === 1 ? '' : 's'} of up to {size.toLocaleString()} each
              {started && <> · <b>{doneCount}</b>/{links.length} marked done{remaining > 0 ? ` · ${remaining} to go` : ' ✓'}</>}
            </div>
            <span className="row" style={{ gap: 8 }}>
              <button className="linkbtn" onClick={() => markAll(true)}>Mark all done</button>
              <button className="linkbtn" onClick={() => markAll(false)}>Clear</button>
            </span>
          </div>
          {started && (
            <div className="sub" style={{ marginTop: 2 }}>
              The browser can’t report which downloads succeeded, so tick the ones that arrived (or use <b>Mark all done</b> then untick any that failed).
              A failed page usually saves as a small <code>.html</code> error file — click that page to re-download it.
            </div>
          )}
          <ul className="spl-links">
            {links.map((l) => (
              <li key={l.i} className={done[l.i] ? 'done' : ''}>
                <input type="checkbox" checked={!!done[l.i]} onChange={(e) => mark(l.i, e.target.checked)}
                       title="Mark this page as downloaded" />
                <a href={l.url} target="_blank" rel="noopener noreferrer" title={l.url}
                   onClick={() => mark(l.i, true)}>
                  page {l.i + 1} · rows {l.from.toLocaleString()}–{l.to.toLocaleString()}
                </a>
                <button className="linkbtn" title="Re-download this page" onClick={() => triggerDownload(l.url)}>⟲ retry</button>
              </li>
            ))}
          </ul>
        </>
      ) : (s && trimmedBase && n === 0
        ? <div className="sub" style={{ marginTop: 6 }}>Enter the result count to generate the download links.</div>
        : null)}
    </div>
  );
}
