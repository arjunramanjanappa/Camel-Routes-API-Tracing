import { useEffect, useMemo, useRef, useState } from 'react';
import { analyzeLog, analyzeLogMulti, type UploadProgress } from '../api';
import type { ApiLogResult, BackendLogResult, LogAnalysisReport, LogStatus } from '../types';
import { backendPath } from '../spl';
import { exportLogPdf, exportLogPdfMulti } from '../logPdf';

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
      <td title={fbTitle(b.failuresByCode)}>{b.attempts > 0 ? (
        <>{b.attempts} (<span className="att-ok">{b.successCount}✓</span>/<span className="att-bad">{b.failureCount}✗</span>)</>
      ) : '—'}</td>
      <td />
    </tr>
  );
}

/** Hover text listing failed attempts grouped by response code / reason, most-frequent first. */
function fbTitle(m?: Record<string, number> | null): string | undefined {
  if (!m) return undefined;
  const entries = Object.entries(m);
  return entries.length ? 'Failed by code: ' + entries.map(([c, n]) => `${c} ×${n}`).join(', ') : undefined;
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
        <td title={fbTitle(a.failuresByCode)}>{a.attempts > 0 ? (
          <>{a.attempts} (<span className="att-ok">{a.successCount}✓</span>/<span className="att-bad">{a.failureCount}✗</span>)</>
        ) : '—'}</td>
        <td>{(a.backends.length > 0 || hasFailures(a.failuresByCode)) &&
          <button className="linkbtn" onClick={onToggle}>{isOpen ? 'hide' : 'details'}</button>}</td>
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
      {isOpen && hasFailures(a.failuresByCode) && (
        <tr className="lsub"><td colSpan={6}><FailureBreakdown m={a.failuresByCode} /></td></tr>
      )}
    </>
  );
}

function hasFailures(m?: Record<string, number> | null): boolean {
  return !!m && Object.keys(m).length > 0;
}

/** Compact "Failed responses" table (code · count · proportional bar · share) shown when a row is expanded. */
function FailureBreakdown({ m }: { m?: Record<string, number> | null }) {
  const entries = m ? Object.entries(m) : [];
  if (!entries.length) return null;
  const total = entries.reduce((n, [, c]) => n + c, 0);
  const max = Math.max(...entries.map(([, c]) => c));
  return (
    <div className="failbreak">
      <div className="failbreak-title">Failed responses</div>
      <table className="failbreak-tbl">
        <tbody>
          {entries.map(([code, c]) => (
            <tr key={code}>
              <td className="fb-code">{code}</td>
              <td className="fb-count">{c}</td>
              <td className="fb-bar"><span className="fb-bar-fill" style={{ width: Math.max(4, (100 * c) / max) + '%' }} /></td>
              <td className="fb-pct">{Math.round((100 * c) / total)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** One module (repo) to correlate the uploaded log against, with its marker flavour and source. */
export interface LogModule {
  id: string;
  name: string;
  app: string;        // marker flavour (Mighty for the entry app's main module, else SPL)
  sourceDir?: string;
  repo?: string;
  branch?: string;
}

/** One module's log verification outcome for the grouped multi-module view. */
interface PerModuleLog { id: string; name: string; report: LogAnalysisReport | null; error?: string; }

interface Props {
  version?: string;
  country?: string;
  sourceDir?: string;
  repo?: string;
  branch?: string;
  app?: string;
  selectedApis?: string[];
  selectedBackends?: string[];
  /**
   * Multi-module release test: the same uploaded log is correlated against every module (repo),
   * each with its own marker flavour, and the results are grouped so it is clear which module's
   * APIs were missed. When more than one module is passed the panel runs in multi mode (whole
   * release per module — selection is ignored). Single/absent → the classic single-source flow.
   */
  modules?: LogModule[];
  /** Encoded dependency sources (see deps.ts) — threaded so the log analysis resolves the same routes. */
  deps?: string[];
  /** Unresolved imports/routes from the impact index — surfaced in the exported report. */
  needsReview?: string[];
  onReport?: (hasReport: boolean) => void;
}

/** Passed / issues / not-tested tallies for a module's report — drives the per-module coverage strip. */
function tally(report: LogAnalysisReport) {
  let passed = 0, notTested = 0, issues = 0;
  report.apis.forEach((a) => { if (a.status === 'SUCCESS') passed++; else if (a.status === 'NOT_TESTED') notTested++; else issues++; });
  return { passed, notTested, issues, total: report.apis.length };
}

function kb(n: number): string {
  const mb = n / (1024 * 1024);
  return mb >= 1 ? mb.toFixed(mb >= 10 ? 0 : 1) + ' MB' : (n / 1024).toFixed(0) + ' KB';
}

// Keep in step with spring.servlet.multipart in application.yml.
const SIZE_CAVEAT = 'Up to 1 GB per file and 6 GB per upload. Larger logs — split into chunks and add them all.';

/** A reusable drop-zone: pick one or more files, list them with size + remove, add more on click. */
function FileZone({ files, onAdd, onRemove, onClear, hint, label }: {
  files: File[];
  onAdd: (picked: File[]) => void;
  onRemove: (i: number) => void;
  onClear: () => void;
  hint: string;
  label?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const total = files.reduce((n, f) => n + f.size, 0);
  return (
    <div className="filezone">
      {label && <div className="filezone-label">{label}</div>}
      <div className={'uploader' + (files.length ? ' has' : '')} onClick={() => ref.current?.click()}>
        <input ref={ref} type="file" multiple accept=".log,.txt,.csv,.json,.gz" style={{ display: 'none' }}
               onChange={(e) => {
                 // Read the FileList into a File[] SYNCHRONOUSLY — resetting value='' below empties the
                 // live FileList, so the async state updater would otherwise see nothing selected.
                 const picked = e.target.files ? Array.from(e.target.files) : [];
                 if (ref.current) ref.current.value = '';   // allow re-picking the same file after a remove
                 onAdd(picked);
               }} />
        {files.length
          ? <span><b>{files.length} file{files.length === 1 ? '' : 's'}</b> · {kb(total)} — click to add more</span>
          : <span>{hint}</span>}
      </div>
      {files.length > 0 && (
        <div className="logfiles">
          <div className="logfiles-head">
            <span className="muted">This set will be analysed ({files.length} file{files.length === 1 ? '' : 's'} · {kb(total)})</span>
            <button type="button" className="logfiles-clear" onClick={onClear}>Clear all</button>
          </div>
          {files.map((f, i) => (
            <div className="logfile" key={f.name + f.size}>
              <span className="logfile-name" title={f.name}>{f.name}</span>
              <span className="logfile-size">{kb(f.size)}</span>
              <button type="button" className="logfile-x" title="Remove"
                      onClick={(e) => { e.stopPropagation(); onRemove(i); }}>×</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Upload an output log or Splunk export and correlate it against the traced APIs
 * for the current client release. The report is log-type aware: selected
 * front-end APIs are read from front-end log lines, selected backends from backend
 * log lines; with nothing selected the whole release is analysed.
 */
export default function LogAnalysisPanel({ version, country, sourceDir, repo, branch, app, selectedApis = [], selectedBackends = [], modules, deps = [], needsReview, onReport }: Props) {
  const [inputType, setInputType] = useState<InputType>('OUTPUT_LOG');
  const [files, setFiles] = useState<File[]>([]);   // one upload (chunks) analysed against every selected module
  const [limitToSelection, setLimitToSelection] = useState(true);
  const [progress, setProgress] = useState<UploadProgress | null>(null);   // upload progress while analysing
  const [elapsed, setElapsed] = useState(0);                                // seconds since Analyse was clicked
  const [report, setReport] = useState<LogAnalysisReport | null>(null);
  const [perModule, setPerModule] = useState<PerModuleLog[]>([]);   // multi-module: one report per repo
  const [activeLogId, setActiveLogId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<LogStatus | 'ALL' | 'ISSUES'>('ALL');
  const [sort, setSort] = useState<'severity' | 'api'>('severity');
  const [section, setSection] = useState<'FE' | 'BE'>('FE');   // which result table is shown
  const resultsRef = useRef<HTMLDivElement>(null);

  const multi = !!modules && modules.length > 1;
  const hasSelection = selectedApis.length > 0 || selectedBackends.length > 0;

  const mergeFiles = (prev: File[], picked: File[]) => {
    const next = [...prev];
    picked.forEach((f) => { if (!next.some((x) => x.name === f.name && x.size === f.size)) next.push(f); });
    return next;
  };
  const addFiles = (picked: File[]) => setFiles((prev) => mergeFiles(prev, picked));
  const removeFile = (i: number) => setFiles((prev) => prev.filter((_, ix) => ix !== i));
  const canAnalyse = files.length > 0;

  // When a report lands, bring the results into view — the panel sits far down the
  // long Impact page, so otherwise the screen looks static after "Analyse".
  useEffect(() => {
    onReport?.(!!report);
    if (report) resultsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report]);

  // Tick an elapsed-seconds counter while a scan is running, so the wait is visible.
  useEffect(() => {
    if (!loading) { setElapsed(0); return; }
    const t0 = Date.now();
    const id = window.setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 500);
    return () => window.clearInterval(id);
  }, [loading]);

  const showReport = (rep: LogAnalysisReport | null) => {
    setReport(rep);
    setOpen(new Set());
    setFilter('ALL');
    setSection((rep?.apis.length ?? 0) ? 'FE' : 'BE');   // default to whichever section has data
  };

  const run = async () => {
    if (!canAnalyse) return;
    setLoading(true);
    setError(null);
    setProgress({ loaded: 0, total: files.reduce((n, f) => n + f.size, 0) || 1, done: false });
    const onProg = (p: UploadProgress) => setProgress(p);
    try {
      if (multi && modules) {
        // Upload the chunk(s) ONCE; the backend parses the merged dataset once per distinct marker
        // flavour and correlates it against every module — so each API is attributed to its owning
        // module, and a line logged on one server still matches wherever it appears in the upload.
        const specs = modules.map((m) => ({ name: m.name, sourceDir: m.sourceDir, repo: m.repo, branch: m.branch, app: m.app }));
        const results = await analyzeLogMulti(files, specs, { version, country, dep: deps }, onProg);
        const per: PerModuleLog[] = results.map((res, i) => ({
          id: modules[i]?.id ?? String(i),
          name: res.name || modules[i]?.name || 'module',
          report: res.report,
          error: res.error || undefined,
        }));
        setPerModule(per);
        const firstOk = per.find((p) => p.report) || per[0];
        setActiveLogId(firstOk?.id ?? null);
        showReport(firstOk?.report ?? null);
      } else {
        // Single module: unchecked (or nothing selected) ⇒ analyse the whole release (front-end + backends).
        // Multiple chunks are merged into one dataset server-side.
        const all = !hasSelection || !limitToSelection;
        const rep = await analyzeLog(files, {
          version, country, sourceDir, repo, branch, all, app, dep: deps,
          apis: all ? undefined : selectedApis,
          backends: all ? undefined : selectedBackends,
        }, onProg);
        setPerModule([]);
        showReport(rep);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setProgress(null);
    }
  };

  const selectLogModule = (id: string) => {
    const p = perModule.find((x) => x.id === id);
    setActiveLogId(id);
    showReport(p?.report ?? null);
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
    if (multi) {
      if (!perModule.length) return;
      exportLogPdfMulti(perModule.map((p) => ({ name: p.name, report: p.report, error: p.error })), app, version, needsReview).catch(() => {});
      return;
    }
    if (!report) return;
    exportLogPdf(report, app, version, needsReview).catch(() => {});
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

      <FileZone files={files} onAdd={addFiles} onRemove={removeFile} onClear={() => setFiles([])}
                hint={inputType === 'SPLUNK'
                  ? 'Click to choose Splunk export(s) — .csv / .json, or a _raw .txt — one file or several chunks (format auto-detected)'
                  : 'Click to choose output log(s) — .txt / .log — one file or several chunks (format auto-detected)'} />
      {multi && (
        <div className="sub" style={{ marginTop: 6 }}>
          The uploaded log(s) are analysed as one dataset and correlated against all <b>{modules!.length}</b> modules — each API is
          attributed to its owning module (by that module’s marker), so a request logged on one server and its backend call on another
          are still matched. Upload one combined log or several chunks; it’s scanned once per marker flavour, not once per module.
        </div>
      )}

      <div className="sub" style={{ marginTop: 6, fontSize: 11 }}>{SIZE_CAVEAT}</div>
      {!multi && hasSelection && (
        <>
          <label className="check" style={{ marginTop: 8 }}>
            <input type="checkbox" checked={limitToSelection} onChange={(e) => setLimitToSelection(e.target.checked)} />
            Limit to my selection ({selectedApis.length} API{selectedApis.length === 1 ? '' : 's'} → front-end logs,
            {' '}{selectedBackends.length} backend{selectedBackends.length === 1 ? '' : 's'} → backend logs)
          </label>
          <div className="sub">Unchecked → analyse the whole {version || 'BASE'} release (front-end + backends).</div>
        </>
      )}

      <button className="trace" disabled={!canAnalyse || loading} onClick={run}>
        {loading ? 'Analysing…' : files.length > 1 ? `Analyse ${files.length} files` : 'Analyse'}
      </button>

      {loading && (() => {
        const uploading = !!progress && !progress.done;
        const pct = progress && progress.total > 0 ? Math.min(100, (progress.loaded / progress.total) * 100) : 0;
        return (
          <div className="analyse-progress">
            <div className={'ap-bar' + (uploading ? '' : ' indet')}>
              <div className="ap-fill" style={uploading ? { width: pct + '%' } : undefined} />
            </div>
            <div className="ap-label">
              {uploading
                ? <>Uploading the log… <b>{Math.round(pct)}%</b> {progress ? <span className="muted">({kb(progress.loaded)} / {kb(progress.total)})</span> : null}</>
                : <>Upload complete — scanning on the server… <span className="muted">large logs take a little longer</span></>}
              {' '}· {elapsed}s elapsed
            </div>
          </div>
        );
      })()}

      {error && <div className="err">Error: {error}</div>}

      {multi && perModule.length > 0 && (
        <div style={{ marginTop: 12 }} ref={resultsRef}>
          <div className="sub" style={{ marginBottom: 6 }}>Test coverage by module — click one to see its APIs below. The exported PDF covers all {perModule.length}.</div>
          <div className="logmods">
            {perModule.map((p) => {
              const t = p.report ? tally(p.report) : null;
              return (
                <button key={p.id} className={'logmod' + (p.id === activeLogId ? ' active' : '') + (p.error ? ' err' : '')}
                        onClick={() => selectLogModule(p.id)} title={p.error || undefined}>
                  <div className="logmod-name">{p.name}</div>
                  {p.error ? <div className="logmod-sub err">not analysed</div>
                    : t ? (
                      <div className="logmod-stats">
                        <span className="lm-ok">{t.passed}✓</span>
                        <span className="lm-bad">{t.issues} issue{t.issues === 1 ? '' : 's'}</span>
                        <span className={'lm-nt' + (t.notTested ? ' hot' : '')}>{t.notTested} not tested</span>
                      </div>
                    ) : <div className="logmod-sub">—</div>}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {report && (
        <div style={{ marginTop: 12, scrollMarginTop: 12 }} ref={multi ? undefined : resultsRef}>
          {multi && activeLogId && (
            <div className="sub" style={{ marginBottom: 6 }}>Showing module <b>{perModule.find((p) => p.id === activeLogId)?.name || 'module'}</b>.</div>
          )}
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

      {multi && perModule.length > 0 && !report && activeLogId && (
        <div className="sub" style={{ marginTop: 10 }}>
          {perModule.find((p) => p.id === activeLogId)?.error
            ? 'This module was not analysed — ' + perModule.find((p) => p.id === activeLogId)?.error
            : 'No APIs were correlated from the log for this module — its export still lists it as not tested.'}
        </div>
      )}
    </div>
  );
}
