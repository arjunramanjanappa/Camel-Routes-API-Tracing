import { useEffect, useMemo, useRef, useState } from 'react';
import { analyzeLog } from '../api';
import type { ApiLogResult, BackendLogResult, LogAnalysisReport, LogStatus } from '../types';
import { backendPath } from '../spl';
import { exportLogPdf } from '../logPdf';

type InputType = 'OUTPUT_LOG' | 'SPLUNK';

const STATUS_LABEL: Record<LogStatus, string> = {
  SUCCESS: 'Success',
  FAILED: 'Failed',
  TIMEOUT: 'Timeout',
  PARTIAL: 'Partial',
  INDETERMINATE: 'Check',
  NOT_TESTED: 'Not tested',
};

const STATUS_ORDER: LogStatus[] = ['SUCCESS', 'PARTIAL', 'FAILED', 'TIMEOUT', 'INDETERMINATE', 'NOT_TESTED'];

const STATUS_COLOR: Record<LogStatus, string> = {
  SUCCESS: '#16a34a',
  PARTIAL: '#d97706',
  FAILED: '#ea580c',
  TIMEOUT: '#7c3aed',
  INDETERMINATE: '#2563eb',
  NOT_TESTED: '#dc2626',
};

// Worst-first ordering so the rows that need investigation float to the top.
const SEVERITY: Record<LogStatus, number> = {
  FAILED: 0, TIMEOUT: 1, PARTIAL: 2, INDETERMINATE: 3, NOT_TESTED: 4, SUCCESS: 5,
};

function Badge({ s }: { s: LogStatus }) {
  return <span className={'lstat ' + s.toLowerCase()}>{STATUS_LABEL[s]}</span>;
}

/** Backend service version: logged vs expected, with a match/mismatch indicator. */
function SvcChip({ expected, logged, ok }: { expected?: string | null; logged?: string | null; ok?: boolean | null }) {
  if (!expected && !logged) return null;
  if (ok === true) return <span className="svcchip ok" title={'expected ' + expected}>svc {logged} ✓</span>;
  if (ok === false) return <span className="svcchip bad" title={'expected ' + expected}>svc {logged} ✗ (exp {expected})</span>;
  if (logged) return <span className="svcchip" title={expected ? 'expected ' + expected : undefined}>svc {logged}</span>;
  return <span className="svcchip" title="not seen in the log">exp svc {expected}</span>;
}

/** A donut summarising the per-status API counts. */
function Donut({ counts }: { counts: Record<LogStatus, number> }) {
  const segs = STATUS_ORDER.filter((s) => counts[s]);
  const total = segs.reduce((n, s) => n + counts[s], 0);
  const r = 34;
  const c = 2 * Math.PI * r;
  let acc = 0;
  return (
    <svg width="86" height="86" viewBox="0 0 86 86" className="donut">
      <circle className="dtrack" cx="43" cy="43" r={r} fill="none" strokeWidth="12" />
      {total > 0 && segs.map((s) => {
        const len = (counts[s] / total) * c;
        const seg = (
          <circle key={s} cx="43" cy="43" r={r} fill="none" stroke={STATUS_COLOR[s]} strokeWidth="12"
                  strokeDasharray={`${len} ${c - len}`} strokeDashoffset={-acc} transform="rotate(-90 43 43)" />
        );
        acc += len;
        return seg;
      })}
      <text x="43" y="40" textAnchor="middle" className="donut-num">{total}</text>
      <text x="43" y="54" textAnchor="middle" className="donut-lbl">checked</text>
    </svg>
  );
}

/** One row of the backend-only report. */
function BackendRow({ b }: { b: BackendLogResult }) {
  const resultText = b.status === 'NOT_TESTED' || b.status === 'TIMEOUT'
    ? b.note || '—'
    : `${b.responseCode || '—'}${b.responseDescription ? ' · ' + b.responseDescription : ''}`;
  return (
    <tr className={'lrow ' + b.status.toLowerCase()}>
      <td><Badge s={b.status} /></td>
      <td>
        <code>{backendPath(b.backend)}</code>
        {' '}<SvcChip expected={b.expectedServiceVersion} logged={b.loggedServiceVersion} ok={b.serviceVersionOk} />
      </td>
      <td title={b.correlationId ? 'correlation ' + b.correlationId + (b.latestAt ? ' @ ' + b.latestAt : '') : undefined}>
        {resultText}
      </td>
      <td>{b.latencyMs != null ? b.latencyMs + ' ms' : '—'}</td>
      <td>{b.attempts > 0 ? (
        <>{b.attempts} (<span className="att-ok">{b.successCount}✓</span>/<span className="att-bad">{b.failureCount}✗</span>)</>
      ) : '—'}</td>
      <td />
    </tr>
  );
}

function Row({ a, isOpen, onToggle }: { a: ApiLogResult; isOpen: boolean; onToggle: () => void }) {
  const resultText =
    a.status === 'NOT_TESTED' || a.status === 'TIMEOUT'
      ? a.note || '—'
      : `${a.responseCode || '—'}${a.responseDescription ? ' · ' + a.responseDescription : ''}`;
  return (
    <>
      <tr className={'lrow ' + a.status.toLowerCase()}>
        <td><Badge s={a.status} /></td>
        <td>
          <code>{a.api}</code>
          <div className="muted">{a.operation}{a.resolvedRoute ? ' → ' + a.resolvedRoute : ''}</div>
        </td>
        <td title={a.correlationId ? 'correlation ' + a.correlationId + (a.latestAt ? ' @ ' + a.latestAt : '') : undefined}>
          {resultText}
        </td>
        <td>{a.feLatencyMs != null ? a.feLatencyMs + ' ms' : '—'}</td>
        <td>{a.attempts > 0 ? (
          <>{a.attempts} (<span className="att-ok">{a.successCount}✓</span>/<span className="att-bad">{a.failureCount}✗</span>)</>
        ) : '—'}</td>
        <td>{a.backends.length > 0 && <button className="linkbtn" onClick={onToggle}>{isOpen ? 'hide' : 'backends'}</button>}</td>
      </tr>
      {isOpen && a.backends.map((b, i) => (
        <tr key={i} className="lsub">
          <td><Badge s={b.status} /></td>
          <td colSpan={2}>
            <code>{b.backend}</code>
            <span className="muted">{b.observedPath ? ' seen: ' + b.observedPath : ' not observed'}</span>
            {' '}<SvcChip expected={b.expectedServiceVersion} logged={b.loggedServiceVersion} ok={b.serviceVersionOk} />
          </td>
          <td>{b.latencyMs != null ? b.latencyMs + ' ms' : '—'}</td>
          <td colSpan={2}>{b.responseCode || ''}{b.responseDescription ? ' · ' + b.responseDescription : ''}</td>
        </tr>
      ))}
    </>
  );
}

interface Props {
  version?: string;
  country?: string;
  sourceDir?: string;
  repo?: string;
  branch?: string;
  app?: string;
  selectedApis?: string[];
  selectedBackends?: string[];
  onReport?: (hasReport: boolean) => void;
}

/**
 * Upload an output log or Splunk export and correlate it against the traced APIs
 * for the current client release. The report is log-type aware: selected
 * front-end APIs are read from front-end log lines, selected backends from backend
 * log lines; with nothing selected the whole release is analysed.
 */
export default function LogAnalysisPanel({ version, country, sourceDir, repo, branch, app, selectedApis = [], selectedBackends = [], onReport }: Props) {
  const [inputType, setInputType] = useState<InputType>('OUTPUT_LOG');
  const [file, setFile] = useState<File | null>(null);
  const [limitToSelection, setLimitToSelection] = useState(true);
  const [report, setReport] = useState<LogAnalysisReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<LogStatus | 'ALL' | 'ISSUES'>('ALL');
  const [sort, setSort] = useState<'severity' | 'api'>('severity');
  const [section, setSection] = useState<'FE' | 'BE'>('FE');   // which result table is shown
  const fileRef = useRef<HTMLInputElement>(null);
  const resultsRef = useRef<HTMLDivElement>(null);

  const hasSelection = selectedApis.length > 0 || selectedBackends.length > 0;

  // When a report lands, bring the results into view — the panel sits far down the
  // long Impact page, so otherwise the screen looks static after "Analyse".
  useEffect(() => {
    onReport?.(!!report);
    if (report) resultsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report]);

  const run = async () => {
    if (!file) return;
    setLoading(true);
    setError(null);
    try {
      // Unchecked (or nothing selected) ⇒ analyse the whole release (front-end + backends).
      const all = !hasSelection || !limitToSelection;
      const rep = await analyzeLog(file, {
        version, country, sourceDir, repo, branch, all, app,
        apis: all ? undefined : selectedApis,
        backends: all ? undefined : selectedBackends,
      });
      setReport(rep);
      setOpen(new Set());
      setFilter('ALL');
      setSection(rep.apis.length ? 'FE' : 'BE');   // default to whichever section has data
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Status distribution across BOTH sections (front-end APIs + backends).
  const counts = useMemo(() => {
    const c = {} as Record<LogStatus, number>;
    report?.apis.forEach((a) => { c[a.status] = (c[a.status] || 0) + 1; });
    report?.backends.forEach((b) => { c[b.status] = (c[b.status] || 0) + 1; });
    return c;
  }, [report]);
  const total = (report?.apis.length ?? 0) + (report?.backends.length ?? 0);
  const issuesCount = useMemo(() =>
    (report?.apis.filter((a) => a.status !== 'SUCCESS').length ?? 0)
    + (report?.backends.filter((b) => b.status !== 'SUCCESS').length ?? 0), [report]);

  const keep = (s: LogStatus) => filter === 'ALL' || (filter === 'ISSUES' ? s !== 'SUCCESS' : s === filter);

  const shownApis = useMemo(() => {
    if (!report) return [];
    return report.apis.filter((a) => keep(a.status)).sort((a, b) => (sort === 'api'
      ? a.api.localeCompare(b.api)
      : SEVERITY[a.status] - SEVERITY[b.status] || a.api.localeCompare(b.api)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report, filter, sort]);

  const shownBackends = useMemo(() => {
    if (!report) return [];
    return report.backends.filter((b) => keep(b.status)).sort((x, y) => (sort === 'api'
      ? x.backend.localeCompare(y.backend)
      : SEVERITY[x.status] - SEVERITY[y.status] || x.backend.localeCompare(y.backend)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report, filter, sort]);

  const toggle = (k: string) => {
    const n = new Set(open);
    if (n.has(k)) n.delete(k); else n.add(k);
    setOpen(n);
  };
  const pick = (f: LogStatus | 'ALL' | 'ISSUES') => setFilter((cur) => (cur === f ? 'ALL' : f));

  const exportPdf = () => {
    if (!report) return;
    exportLogPdf(report, app, version).catch(() => {});
  };

  // Front-end APIs and backends are shown one section at a time. The segmented switch only
  // appears when a report has a STANDALONE backend section (a backend-scoped analysis). In a
  // front-end end-to-end run the backends are already listed inline under each API, so there
  // is no separate "Backends 0" tab. The donut / filter / sort stay shared across sections.
  const hasFe = (report?.apis.length ?? 0) > 0;
  const hasBe = (report?.backends.length ?? 0) > 0;
  const both = hasFe && hasBe;
  const showFe = hasFe && (!both || section === 'FE');
  const showBe = hasBe && (!both || section === 'BE');
  const shownCount = (showFe ? shownApis.length : 0) + (showBe ? shownBackends.length : 0);
  const sectionTotal = (showFe ? (report?.apis.length ?? 0) : 0) + (showBe ? (report?.backends.length ?? 0) : 0);

  return (
    <div className="panel">
      <div className="row between">
        <h2 style={{ margin: 0 }}>Verify with logs</h2>
        <div className="seg">
          <button className={inputType === 'OUTPUT_LOG' ? 'on' : ''} onClick={() => setInputType('OUTPUT_LOG')}>Output log</button>
          <button className={inputType === 'SPLUNK' ? 'on' : ''} onClick={() => setInputType('SPLUNK')}>Splunk report</button>
        </div>
      </div>
      <div className="sub">
        Run the generated query in Splunk and upload the result, or upload the raw output log. Each API is
        checked end-to-end (front-end ↔ backend) by correlation id for client release <b>{version || 'BASE'}</b>.
      </div>

      {inputType === 'SPLUNK' && (
        <div className="sub" style={{ marginTop: 8 }}>
          Upload the CSV or JSON you exported from Splunk for the generated query — the event is read from its
          <code>_raw</code> field, and the format is detected from the file. A <code>_raw</code>-only export saved as
          <code>.txt</code> is just the raw log lines, so it verifies exactly like an output log — either upload mode
          gives the same result.
        </div>
      )}

      <div className={'uploader' + (file ? ' has' : '')} onClick={() => fileRef.current?.click()}>
        <input ref={fileRef} type="file"
               accept=".log,.txt,.csv,.json,.gz" style={{ display: 'none' }}
               onChange={(e) => setFile(e.target.files?.[0] || null)} />
        {file
          ? <span><b>{file.name}</b> · {(file.size / 1024).toFixed(0)} KB — click to change</span>
          : inputType === 'SPLUNK'
            ? <span>Click to choose a Splunk export — <b>.csv</b> / .json, or a <code>_raw</code> <b>.txt</b> (format auto-detected)</span>
            : <span>Click to choose an output log — <b>.txt</b> / .log, or a Splunk export (format auto-detected)</span>}
      </div>

      {hasSelection && (
        <>
          <label className="check" style={{ marginTop: 8 }}>
            <input type="checkbox" checked={limitToSelection} onChange={(e) => setLimitToSelection(e.target.checked)} />
            Limit to my selection ({selectedApis.length} API{selectedApis.length === 1 ? '' : 's'} → front-end logs,
            {' '}{selectedBackends.length} backend{selectedBackends.length === 1 ? '' : 's'} → backend logs)
          </label>
          <div className="sub">Unchecked → analyse the whole {version || 'BASE'} release (front-end + backends).</div>
        </>
      )}

      <button className="trace" disabled={!file || loading} onClick={run}>
        {loading ? 'Analysing…' : 'Analyse'}
      </button>

      {error && <div className="err">Error: {error}</div>}

      {report && (
        <div style={{ marginTop: 12, scrollMarginTop: 12 }} ref={resultsRef}>
          <div className="report-sticky">
          <div className="kv">
            <b>{report.transactions}</b> transactions · <b>{report.matchedLines}</b> matched / {report.linesScanned} lines
            {report.unparsedLines > 0 ? ` · ${report.unparsedLines} unparsed` : ''} · {report.uploadType}
          </div>

          <div className="report-summary">
            <Donut counts={counts} />
            <div className="report-side">
              <div className="fchips">
                <button className={'fchip all' + (filter === 'ALL' ? ' active' : '')} onClick={() => pick('ALL')}>All {total}</button>
                {issuesCount > 0 && (
                  <button className={'fchip issues' + (filter === 'ISSUES' ? ' active' : '')} onClick={() => pick('ISSUES')}>Issues {issuesCount}</button>
                )}
                {STATUS_ORDER.filter((s) => counts[s]).map((s) => (
                  <button key={s} className={'lstat fchip ' + s.toLowerCase() + (filter === s ? ' active' : '')} onClick={() => pick(s)}>
                    {STATUS_LABEL[s]} {counts[s]}
                  </button>
                ))}
              </div>
              <div className="sub" style={{ marginTop: 2 }}>Click a status to filter the tables below.</div>
            </div>
          </div>
          </div>

          {report.warnings.map((w, i) => <div key={i} className="warn">{w}</div>)}

          {both && (
            <div className="seg" style={{ marginTop: 8 }}>
              <button className={section === 'FE' ? 'on' : ''} onClick={() => setSection('FE')}>Front-end APIs {report.apis.length}</button>
              <button className={section === 'BE' ? 'on' : ''} onClick={() => setSection('BE')}>Backends {report.backends.length}</button>
            </div>
          )}

          <div className="row between" style={{ marginTop: 8 }}>
            <span className="muted">Showing {shownCount} of {sectionTotal} {showBe && !showFe ? 'backend(s)' : 'front-end API(s)'}</span>
            <span className="row" style={{ gap: 8 }}>
              <select className="sortsel" value={sort} onChange={(e) => setSort(e.target.value as 'severity' | 'api')}>
                <option value="severity">Sort: worst first</option>
                <option value="api">Sort: name</option>
              </select>
              <button className="minibtn" onClick={exportPdf} title="Download a shareable PDF report">⤓ Export PDF</button>
            </span>
          </div>

          {showFe && (
            <table className="grid">
              <thead>
                <tr><th>Status</th><th>Front-end API</th><th>Result</th><th>Latency</th><th>Attempts</th><th /></tr>
              </thead>
              <tbody>
                {shownApis.map((a) => {
                  const k = a.api + a.operation;
                  return <Row key={k} a={a} isOpen={open.has(k)} onToggle={() => toggle(k)} />;
                })}
                {shownApis.length === 0 && (
                  <tr><td colSpan={6} className="muted" style={{ padding: 10 }}>No front-end APIs match this filter.</td></tr>
                )}
              </tbody>
            </table>
          )}

          {showBe && (
            <table className="grid">
              <thead>
                <tr><th>Status</th><th>Backend</th><th>Result</th><th>Latency</th><th>Attempts</th><th /></tr>
              </thead>
              <tbody>
                {shownBackends.map((b) => <BackendRow key={b.backend} b={b} />)}
                {shownBackends.length === 0 && (
                  <tr><td colSpan={6} className="muted" style={{ padding: 10 }}>No backends match this filter.</td></tr>
                )}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
