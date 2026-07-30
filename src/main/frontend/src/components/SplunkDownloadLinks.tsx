import { useEffect, useMemo, useState } from 'react';
import { fetchSettings } from '../api';
import CopyBtn from './CopyBtn';

const DEFAULT_PAGE = 50000;

/**
 * Builds the paginated Splunk result-download links for a finished search job — automating the offset
 * arithmetic the user does by hand. Given the Job SID and the total result count (page size defaults to
 * 50,000, the per-request cap), it emits one `…/<SID>/results?output_mode=csv&count=<page>&offset=<n>`
 * URL per page. TraceGuard never calls Splunk: the links open in the user's own browser, which is already
 * authenticated to Splunk Web (the `/en-US/splunkd/__raw/...` proxy rides the session cookie), so no
 * export permission or credential is needed here. The downloaded CSV chunk(s) are then attached under
 * "Verify with logs" for correlation. The base URL comes from the machine-wide Config store (editable inline).
 */
export default function SplunkDownloadLinks() {
  const [base, setBase] = useState('');
  const [sid, setSid] = useState('');
  const [count, setCount] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE);

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

  const allUrls = links.map((l) => l.url).join('\n');

  return (
    <div className="spl-block">
      <div className="row between">
        <b>Download results by Job SID</b>
        {links.length > 0 && <CopyBtn text={allUrls} label={`Copy all ${links.length} URLs`} />}
      </div>
      <div className="sub">
        Run the query above in Splunk, then paste its <b>Job SID</b> and <b>result count</b> — TraceGuard builds the paginated
        CSV download links. Open each (it uses your logged-in Splunk Web session) and upload the file(s) under <b>Verify with logs</b>.
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
          <div className="sub" style={{ marginTop: 6 }}>{n.toLocaleString()} rows → <b>{pages}</b> file{pages === 1 ? '' : 's'} of up to {size.toLocaleString()} each:</div>
          <ol className="spl-links">
            {links.map((l) => (
              <li key={l.i}>
                <a href={l.url} target="_blank" rel="noopener noreferrer" title={l.url}>
                  page {l.i + 1} · rows {l.from.toLocaleString()}–{l.to.toLocaleString()}
                </a>
              </li>
            ))}
          </ol>
        </>
      ) : (s && trimmedBase && n === 0
        ? <div className="sub" style={{ marginTop: 6 }}>Enter the result count to generate the download links.</div>
        : null)}
    </div>
  );
}
