import { useEffect, useMemo, useState } from 'react';
import { fetchSettings } from '../api';
import CopyBtn from './CopyBtn';

const DEFAULT_PAGE = 50000;
const STAGGER_MS = 700;   // space out bulk downloads so the browser's single "allow multiple downloads" prompt settles

/**
 * Builds the paginated Splunk result-download links for a finished search job — automating the offset
 * arithmetic the user does by hand. Given the Job SID and the total result count (page size defaults to
 * 50,000, the per-request cap), it emits one `…/<SID>/results?output_mode=csv&count=<page>&offset=<n>`
 * URL per page. TraceGuard never calls Splunk: the links open in the user's own browser, already
 * authenticated to Splunk Web (the `/en-US/splunkd/__raw/...` proxy rides the session cookie), so no
 * export permission or credential is needed. Downloaded CSV chunk(s) are attached under "Verify with logs".
 *
 * <p>"Download all" triggers every page (staggered) with one click. The browser does NOT expose per-file
 * cross-origin download success to the page, so a missing page is self-identified (it saves as a small .html
 * error file, or the file count comes up short) and re-downloaded by clicking its row again. Rows dim once
 * clicked, as a light "I've fetched this" marker for long jobs.
 */
export default function SplunkDownloadLinks() {
  const [base, setBase] = useState('');
  const [sid, setSid] = useState('');
  const [count, setCount] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE);
  const [clicked, setClicked] = useState<Record<number, boolean>>({});

  useEffect(() => {
    let alive = true;
    fetchSettings().then((v) => { if (alive && v.splunkUrl) setBase(v.splunkUrl); }).catch(() => { /* offline / no config — inline field still works */ });
    return () => { alive = false; };
  }, []);

  const n = Math.max(0, parseInt(count, 10) || 0);
  const size = Math.max(1, pageSize || DEFAULT_PAGE);
  const pages = n > 0 ? Math.ceil(n / size) : 0;
  const trimmedBase = base.trim().replace(/\/+$/, '');   // one slash is added before the SID
  const s = sid.trim();
  const ready = !!trimmedBase && !!s;
  const single = pages === 1;

  const links = useMemo(() => {
    if (!ready || pages === 0) return [];
    return Array.from({ length: pages }, (_, i) => {
      const offset = i * size;
      return {
        i,
        from: offset + 1,
        to: Math.min(offset + size, n),
        url: `${trimmedBase}/${encodeURIComponent(s)}/results?output_mode=csv&count=${size}&offset=${offset}`,
      };
    });
  }, [ready, trimmedBase, s, pages, size, n]);

  // Clear the click markers whenever the target (job / count / page size) changes.
  useEffect(() => { setClicked({}); }, [trimmedBase, s, size, n]);

  const allUrls = links.map((l) => l.url).join('\n');
  const markClicked = (i: number) => setClicked((c) => (c[i] ? c : { ...c, [i]: true }));

  // A cross-origin download is a navigation: an <a download> click makes the browser fetch it with the
  // Splunk session cookie and save it, without navigating this app away (for an attachment/CSV response).
  const triggerDownload = (url: string, i: number) => {
    const a = document.createElement('a');
    a.href = url;
    a.download = '';           // hint download intent (filename is ignored cross-origin — Splunk names it)
    a.rel = 'noopener';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    markClicked(i);
  };

  const downloadAll = () => {
    if (!links.length) return;
    links.forEach((l, idx) => window.setTimeout(() => triggerDownload(l.url, l.i), idx * STAGGER_MS));
  };

  return (
    <div className="spl-block">
      <div className="spl-dl-head">
        <b>Download results by Job SID</b>
        {links.length > 0 && (
          <span className="spl-dl-actions">
            <button className="minibtn primary" onClick={downloadAll}
                    title={single ? 'Download the CSV' : `Trigger all ${links.length} downloads (your browser asks once to allow multiple)`}>
              ⬇ {single ? 'Download CSV' : `Download all (${links.length})`}
            </button>
            <CopyBtn text={allUrls} label={single ? 'Copy URL' : `Copy URLs (${links.length})`} />
          </span>
        )}
      </div>

      <div className="sub">
        Run the query above in Splunk, then paste its <b>Job SID</b> + <b>result count</b> — TraceGuard builds the paginated
        CSV download link(s). They use your logged-in Splunk Web session; upload the file(s) under <b>Verify with logs</b>.
      </div>

      <div className="spl-dl-fields">
        <label className="spl-dl-field">
          <span>Job SID</span>
          <input value={sid} placeholder="1699999999.12345" spellCheck={false} onChange={(e) => setSid(e.target.value)} />
        </label>
        <div className="spl-dl-row2">
          <label className="spl-dl-field">
            <span>Result count <span className="muted">(events)</span></span>
            <input value={count} inputMode="numeric" placeholder="e.g. 820000" onChange={(e) => setCount(e.target.value.replace(/[^\d]/g, ''))} />
          </label>
          <label className="spl-dl-field">
            <span>Page size <span className="muted">(per request)</span></span>
            <input value={String(pageSize)} inputMode="numeric"
                   onChange={(e) => setPageSize(Math.max(1, parseInt(e.target.value.replace(/[^\d]/g, ''), 10) || DEFAULT_PAGE))} />
          </label>
        </div>
        <label className="spl-dl-field">
          <span>Splunk base URL {!trimmedBase && <span style={{ color: 'var(--warn, #b45309)' }}>— set in ⚙ Config or here</span>}</span>
          <input value={base} spellCheck={false}
                 placeholder="https://host:8000/en-US/splunkd/__raw/services/search/jobs/"
                 onChange={(e) => setBase(e.target.value)} />
        </label>
      </div>

      {links.length > 0 ? (
        <div className="spl-dl-result">
          <div className="sub" style={{ margin: 0 }}>
            {n.toLocaleString()} rows → <b>{pages}</b> file{single ? '' : 's'} of up to {size.toLocaleString()} each.
            {!single && <> The browser can’t confirm each download — if one is missing (saved as a small <code>.html</code>), click that row again to re-download.</>}
          </div>
          {!single && (
            <ol className="spl-links">
              {links.map((l) => (
                <li key={l.i} className={clicked[l.i] ? 'done' : ''}>
                  <span className="spl-link-no">{l.i + 1}.</span>
                  <a href={l.url} target="_blank" rel="noopener noreferrer" title={'Download / re-download — ' + l.url}
                     onClick={() => markClicked(l.i)}>
                    rows {l.from.toLocaleString()}–{l.to.toLocaleString()}
                  </a>
                  <span className="spl-link-check" aria-hidden="true">{clicked[l.i] ? '✓' : ''}</span>
                </li>
              ))}
            </ol>
          )}
        </div>
      ) : (ready && n === 0
        ? <div className="sub" style={{ marginTop: 6 }}>Enter the result count to build the download link(s).</div>
        : null)}
    </div>
  );
}
