import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { RotateCw, Table2, FileText, FileStack, CircleX, AlertTriangle, CircleCheck, Minus, Check, X } from 'lucide-react';
import { analyzeLog, analyzeLogMulti, resolveCapabilities, exportCapabilitiesXlsx, type UploadProgress, type CapabilityScope, type CapabilityResult } from '../api';
import type { ApiLogResult, BackendLogResult, LogAnalysisReport, LogStatus } from '../types';
import { backendPath } from '../spl';
import { exportLogPdf, exportLogPdfMulti } from '../logPdf';
import { exportLogSummaryPdf, exportLogSummaryPdfMulti } from '../logSummaryPdf';
import TestSummary from './TestSummary';

type InputType = 'OUTPUT_LOG' | 'SPLUNK';

const STATUS_LABEL: Record<LogStatus, string> = {
  SUCCESS: 'Success',
  FAILED: 'Failed',
  TIMEOUT: 'Timeout',
  PARTIAL: 'Partial',
  INDETERMINATE: 'Check',
  SKIPPED: 'Skipped',
  NOT_TESTED: 'Not tested',
};

const STATUS_ORDER: LogStatus[] = ['SUCCESS', 'PARTIAL', 'FAILED', 'TIMEOUT', 'INDETERMINATE', 'SKIPPED', 'NOT_TESTED'];

const STATUS_COLOR: Record<LogStatus, string> = {
  SUCCESS: '#16a34a',
  PARTIAL: '#d97706',
  FAILED: '#ea580c',
  TIMEOUT: '#7c3aed',
  INDETERMINATE: '#2563eb',
  SKIPPED: '#94a3b8',
  NOT_TESTED: '#dc2626',
};

// Worst-first ordering so the rows that need investigation float to the top. Skipped is neutral (after Success).
const SEVERITY: Record<LogStatus, number> = {
  FAILED: 0, TIMEOUT: 1, PARTIAL: 2, INDETERMINATE: 3, NOT_TESTED: 4, SUCCESS: 5, SKIPPED: 6,
};

function Badge({ s }: { s: LogStatus }) {
  return <span className={'lstat ' + s.toLowerCase()}>{STATUS_LABEL[s]}</span>;
}

/** Backend service version: logged vs expected, with a match/mismatch indicator. */
function SvcChip({ expected, logged, ok, pass, seen }: { expected?: string | null; logged?: string | null; ok?: boolean | null; pass?: boolean; seen?: boolean }) {
  if (!expected && !logged) return null;
  if (ok === true) return <span className="svcchip ok" title={'expected ' + expected}>svc {logged} ✓</span>;
  // Mismatch = the run tested a DIFFERENT backend service version than the release expects — surface it as the
  // shared version-change chip (tested-old → expects-new) so it reads at a glance across every tab.
  if (ok === false) return (
    <span className="vbump bad" title={'This run tested svc ' + logged + ', but the release expects ' + expected + ' — re-test on the new version'}>
      svc got <span className="vo">{logged}</span> · exp <span className="vn">{expected}</span>
    </span>
  );
  // No expected version to compare against. If the call PASSED, the version that was actually used is fine —
  // show it green so it reads consistently with the green Success status (not as an unresolved grey warning).
  if (logged) return (
    <span className={'svcchip' + (pass ? ' ok' : '')}
          title={pass ? 'service version ' + logged + ' used — call passed (no separate expected version to check against)'
                      : (expected ? 'expected ' + expected : 'no expected version to check against')}>
      svc {logged}{pass ? <> <Check size={12} aria-hidden="true" /></> : ''}
    </span>
  );
  // Expected version known, but the call logged no service version. If the backend WAS exercised, nothing in
  // the log contradicts the expected version (this backend just doesn't log one) — so show it as a confirmed ✓,
  // consistent with the calls being counted toward the flow. If it was never observed, show it greyed instead.
  if (seen && expected) return (
    <span className="svcchip ok" title={'expected ' + expected + ' — backend exercised; it logs no service version, so ' + expected + ' is assumed'}>
      svc {expected} <Check size={12} aria-hidden="true" />
    </span>
  );
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
  const resultText = b.bau
    ? (b.note || 'BAU')
    : b.status === 'NOT_TESTED' || b.status === 'TIMEOUT' || b.status === 'SKIPPED'
      ? b.note || '—'
      : `${b.responseCode || '—'}${b.responseDescription ? ' · ' + b.responseDescription : ''}`;
  return (
    <tr className={'lrow ' + (b.bau ? 'bau' : b.status.toLowerCase())}>
      <td>{b.bau
        ? <span className="bau-pill" title="BAU reuse of this backend at a lower/unchanged service version — not part of this release's change, so not verified">BAU</span>
        : <Badge s={b.status} />}</td>
      <td>
        <code>{backendPath(b.backend)}</code>
        {' '}<SvcChip expected={b.expectedServiceVersion} logged={b.loggedServiceVersion} ok={b.bau ? null : b.serviceVersionOk} pass={b.status === 'SUCCESS'} seen={!b.bau && (b.attempts ?? 0) > 0} />
      </td>
      <td title={b.correlationId ? 'correlation ' + b.correlationId + (b.latestAt ? ' @ ' + b.latestAt : '') : undefined}>
        {resultText}
      </td>
      <td>{b.latencyMs != null ? b.latencyMs + ' ms' : '—'}</td>
      <td title={fbTitle(b.failuresByCode)}>{b.attempts > 0 ? (
        <>{b.attempts} (<span className="att-ok">{b.successCount}<Check size={11} aria-hidden="true" /></span>/<span className="att-bad">{b.failureCount}<X size={11} aria-hidden="true" /></span>)</>
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
    a.status === 'NOT_TESTED' || a.status === 'TIMEOUT' || a.status === 'SKIPPED'
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
          <>{a.attempts} (<span className="att-ok">{a.successCount}<Check size={11} aria-hidden="true" /></span>/<span className="att-bad">{a.failureCount}<X size={11} aria-hidden="true" /></span>)</>
        ) : '—'}</td>
        <td>{(a.backends.length > 0 || hasFailures(a.failuresByCode)) &&
          <button className="linkbtn" onClick={onToggle}>{isOpen ? 'hide' : 'details'}</button>}</td>
      </tr>
      {/* Front-end group: the FE response failures sit directly under the FE row they belong to. */}
      {isOpen && hasFailures(a.failuresByCode) && (
        <tr className="lsub">
          <td />
          <td colSpan={5}>
            <div className="lsub-group-head">Front-end response failures</div>
            <FailureBreakdown m={a.failuresByCode} />
          </td>
        </tr>
      )}
      {/* Backend group: a header, then one row per backend with its own failures beneath it. */}
      {isOpen && a.backends.length > 0 && (
        <tr className="lsub">
          <td />
          <td colSpan={5}><div className="lsub-group-head">Backends ({a.backends.length})</div></td>
        </tr>
      )}
      {isOpen && a.backends.map((b, i) => (
        <Fragment key={i}>
          <tr className={'lsub' + (b.bau ? ' bau' : '')}>
            {/* First column stays empty for backends — the API's own verdict owns it. The backend's own
                status is an inline pill after the svc chip, so it reads as per-backend detail, not a peer of
                the FE verdict. */}
            <td />
            <td colSpan={2}>
              {b.flowRoute && <span className="flow-route" title="the release route that owns this flow">{b.flowRoute} → </span>}
              <code>{backendPath(b.backend)}</code>
              <span className="muted">{b.observedPath ? ' seen: ' + b.observedPath : (b.bau ? ' unchanged route' : ' not observed')}</span>
              {' '}<SvcChip expected={b.expectedServiceVersion} logged={b.loggedServiceVersion} ok={b.bau ? null : b.serviceVersionOk} pass={b.status === 'SUCCESS'} seen={!b.bau && (b.attempts ?? 0) > 0} />
              {' '}{b.bau
                ? <span className="bau-pill" title="BAU reuse of this backend at a lower/unchanged service version — not part of this release's change, so not verified">BAU</span>
                : <Badge s={b.status} />}
            </td>
            <td>{b.latencyMs != null ? b.latencyMs + ' ms' : '—'}</td>
            <td colSpan={2}>{
              b.status === 'NOT_TESTED'
                ? (b.bau ? 'BAU – no logs found' : <span className="muted">not tested</span>)
                : (b.attempts && b.attempts > 0
                    ? <FlowBar attempts={b.attempts} passed={b.passed || 0} failed={b.failed || 0} bau={b.bau} />
                    : (b.bau ? 'BAU – ' : '') + (b.responseCode || '') + (b.responseDescription ? ' · ' + b.responseDescription : ''))
            }</td>
          </tr>
          {hasFailures(b.failuresByCode) && (
            <tr className={'lsub' + (b.bau ? ' bau' : '')}>
              <td />
              <td colSpan={5}><FailureBreakdown m={b.failuresByCode} /></td>
            </tr>
          )}
        </Fragment>
      ))}
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

/**
 * A flow's coverage as a two-tone pass/fail bar with an "N calls · F failed" label. Binary by design — the
 * exact response codes live in the expandable FailureBreakdown below, so the bar never bloats with codes.
 */
function FlowBar({ attempts, passed, failed, bau }: { attempts: number; passed: number; failed: number; bau?: boolean }) {
  const okPct = attempts > 0 ? (100 * passed) / attempts : 0;
  const badPct = attempts > 0 ? (100 * failed) / attempts : 0;
  const label = failed > 0 ? `${failed} failed` : 'all ok';
  return (
    <div className={'flowbar-wrap' + (bau ? ' bau' : '')} title={`${attempts} call${attempts === 1 ? '' : 's'} · ${passed} ok · ${failed} failed`}>
      <div className="flowbar">
        <span className="flowbar-ok" style={{ width: okPct + '%' }} />
        <span className="flowbar-bad" style={{ width: badPct + '%' }} />
      </div>
      <span className="flowbar-lbl">{attempts} call{attempts === 1 ? '' : 's'} · {label}</span>
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
  /** 'summary' renders the leadership readiness view (donut + Result/Remark) instead of the full tables. */
  viewMode?: 'summary' | 'detailed';
}

/** Passed / issues / not-tested tallies for a module's report — drives the per-module coverage strip. */
function tally(report: LogAnalysisReport) {
  let passed = 0, notTested = 0, issues = 0, skipped = 0;
  report.apis.forEach((a) => {
    if (a.status === 'SUCCESS') passed++;
    else if (a.status === 'NOT_TESTED') notTested++;
    else if (a.status === 'SKIPPED') skipped++;   // neutral — not an issue
    else issues++;
  });
  return { passed, notTested, issues, skipped, total: report.apis.length };
}

/** Release-test readiness for a module (issues-only rule): issues → at risk, else not-tested → review, else ready. */
type Readiness = 'risk' | 'review' | 'ready' | 'empty';
function readiness(t: { passed: number; issues: number; notTested: number; total: number }): Readiness {
  if (t.total === 0) return 'empty';
  if (t.issues > 0) return 'risk';
  if (t.notTested > 0) return 'review';
  return 'ready';
}
const READINESS: Record<Readiness, { label: string; cls: string }> = {
  risk: { label: 'At risk', cls: 'risk' },
  review: { label: 'Review', cls: 'review' },
  ready: { label: 'Ready', cls: 'ready' },
  empty: { label: 'No APIs', cls: 'empty' },
};

function kb(n: number): string {
  const mb = n / (1024 * 1024);
  return mb >= 1 ? mb.toFixed(mb >= 10 ? 0 : 1) + ' MB' : (n / 1024).toFixed(0) + ' KB';
}

/** Trim a raw log timestamp to the second (drop the fractional part) for a compact display. */
function shortTs(ts?: string | null): string {
  return (ts || '').replace(/[.:]\d{1,3}$/, '').trim();
}

/** A coarse human duration from seconds: "3h 12m" / "45m 8s" / "12s". Empty when unknown (< 0). */
function fmtSpan(seconds?: number): string {
  if (seconds == null || seconds < 0) return '';
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

/** The log window from a report: "start → end (span)", or '' when no timestamps were parseable. */
function logRange(r: { logStart?: string | null; logEnd?: string | null; logSpanSeconds?: number } | null | undefined): string {
  if (!r) return '';
  const a = shortTs(r.logStart), b = shortTs(r.logEnd);
  if (!a && !b) return '';
  if (a === b || !b) return a;
  const span = fmtSpan(r.logSpanSeconds);
  return `${a} → ${b}${span ? ` (${span})` : ''}`;
}

/** Parse a raw log timestamp to epoch millis (UTC, for comparison only), or null. Mirrors the backend. */
function tsToMs(ts?: string | null): number | null {
  const m = (ts || '').match(/(\d{4})-(\d{2})-(\d{2})[ T](\d{2})[.:](\d{2})[.:](\d{2})(?:[.:](\d{1,3}))?/);
  if (!m) return null;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], m[7] ? +m[7] : 0);
}

/** The overall window across N module reports (one upload → many modules): earliest start, latest end.
 *  This is the "logs range" the user wants when several files/modules feed one analysis. */
function overallRange(reports: (LogAnalysisReport | null | undefined)[]): { logStart: string | null; logEnd: string | null; logSpanSeconds: number } {
  let lo: number | null = null, hi: number | null = null, loS: string | null = null, hiS: string | null = null;
  for (const r of reports) {
    if (!r) continue;
    const a = tsToMs(r.logStart), b = tsToMs(r.logEnd);
    if (a != null && (lo == null || a < lo)) { lo = a; loS = r.logStart ?? null; }
    if (b != null && (hi == null || b > hi)) { hi = b; hiS = r.logEnd ?? null; }
  }
  return { logStart: loS, logEnd: hiS, logSpanSeconds: lo != null && hi != null ? Math.round((hi - lo) / 1000) : -1 };
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
  const [adding, setAdding] = useState(false);   // brief feedback between picking a file and the list updating
  const total = files.reduce((n, f) => n + f.size, 0);
  return (
    <div className="filezone">
      {label && <div className="filezone-label">{label}</div>}
      <div className={'uploader' + (files.length ? ' has' : '') + (adding ? ' busy' : '')}
           onClick={() => { if (!adding) ref.current?.click(); }}>
        <input ref={ref} type="file" multiple accept=".log,.txt,.csv,.json,.gz" style={{ display: 'none' }}
               onChange={(e) => {
                 // Read the FileList into a File[] SYNCHRONOUSLY — resetting value='' below empties the
                 // live FileList, so the async state updater would otherwise see nothing selected.
                 const picked = e.target.files ? Array.from(e.target.files) : [];
                 if (ref.current) ref.current.value = '';   // allow re-picking the same file after a remove
                 if (!picked.length) return;
                 // Show "Adding…" first, then add on the next tick so it paints before the (possibly
                 // heavy) list + report re-render — otherwise nothing changes on screen for a beat.
                 setAdding(true);
                 setTimeout(() => { onAdd(picked); setAdding(false); }, 0);
               }} />
        {adding
          ? <span><span className="mini-spinner" aria-hidden="true" /> Adding file…</span>
          : files.length
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
export default function LogAnalysisPanel({ version, country, sourceDir, repo, branch, app, selectedApis = [], selectedBackends = [], modules, deps = [], needsReview, onReport, viewMode = 'detailed' }: Props) {
  const [inputType, setInputType] = useState<InputType>('SPLUNK');
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
    // Every row starts collapsed — the table is a scannable status overview; the user opens a row's
    // "details" to drill into its backends / failure breakdown when they want it.
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

  // Coverage rollup across modules + attention count (a module errored, at-risk, or to-review).
  const cov = useMemo(() => {
    let passed = 0, issues = 0, notTested = 0, attention = 0;
    perModule.forEach((p) => {
      if (p.error) { attention++; return; }
      if (!p.report) return;
      const t = tally(p.report);
      passed += t.passed; issues += t.issues; notTested += t.notTested;
      const r = readiness(t);
      if (r === 'risk' || r === 'review') attention++;
    });
    return { passed, issues, notTested, attention };
  }, [perModule]);
  // Worst-first: errored, then at-risk, review, ready, empty — so what needs attention leads.
  const sortedPer = useMemo(() => {
    const rank = (p: PerModuleLog) => (p.error ? 0 : p.report ? ({ risk: 1, review: 2, ready: 3, empty: 4 } as const)[readiness(tally(p.report))] : 4);
    return [...perModule].map((p, i) => ({ p, i })).sort((a, b) => rank(a.p) - rank(b.p) || a.i - b.i).map((x) => x.p);
  }, [perModule]);

  const selectLogModule = (id: string) => {
    const p = perModule.find((x) => x.id === id);
    setActiveLogId(id);
    showReport(p?.report ?? null);
  };

  // Status distribution across BOTH sections (front-end APIs + backends). BAU rows are unchanged reuse —
  // not part of this release — so they don't count toward the readiness tally.
  const counts = useMemo(() => {
    const c = {} as Record<LogStatus, number>;
    report?.apis.forEach((a) => { c[a.status] = (c[a.status] || 0) + 1; });
    report?.backends.forEach((b) => { if (!b.bau) c[b.status] = (c[b.status] || 0) + 1; });
    return c;
  }, [report]);
  const total = (report?.apis.length ?? 0) + (report?.backends.length ?? 0);
  const issuesCount = useMemo(() =>
    (report?.apis.filter((a) => a.status !== 'SUCCESS' && a.status !== 'SKIPPED').length ?? 0)
    + (report?.backends.filter((b) => !b.bau && b.status !== 'SUCCESS' && b.status !== 'SKIPPED').length ?? 0), [report]);

  const keep = (s: LogStatus) => filter === 'ALL' || (filter === 'ISSUES' ? s !== 'SUCCESS' && s !== 'SKIPPED' : s === filter);

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

  // Dependency repos analyse to zero APIs of their own (shared code, not part of this release's surface) — so
  // they're excluded from every export. An errored module is kept in the PDFs so its failure is still surfaced.
  const exportableModules = () => perModule.filter((p) => p.error || (p.report && p.report.apis.length > 0));

  const exportPdf = () => {
    if (multi) {
      const mods = exportableModules();
      if (!mods.length) return;
      exportLogPdfMulti(mods.map((p) => ({ name: p.name, report: p.report, error: p.error })), app, version, needsReview).catch(() => {});
      return;
    }
    if (!report) return;
    exportLogPdf(report, app, version, needsReview).catch(() => {});
  };
  /** The impacted FE + BE API paths of the current report, for the VAL capability lookup (backend paths are
   *  stripped of the {{baseUrl}} placeholder so they match the Interface Spec Col G by ends-with). */
  // FE APIs only — each backend is exercised as part of its FE API's end-to-end test, so the capability
  // list stays at the FE level. statusByApi is the FE API's log verdict for the coloured Test Status column.
  const scopeForReport = (rep: LogAnalysisReport): CapabilityScope => {
    const feApis = [...new Set(rep.apis.map((a) => a.api).filter(Boolean))];
    const statusByApi: Record<string, string> = {};
    for (const a of rep.apis) statusByApi[a.api] = STATUS_LABEL[a.status];
    return { feApis, beApis: [], country, statusByApi, sheetName: 'New App Coverage', fileName: 'Release Test - Capability Matrix' };
  };
  const capabilityScope = (): CapabilityScope => (report ? scopeForReport(report) : { feApis: [], beApis: [], country });
  /** Per-module scope for a multi-module run — ONE workbook with a sheet PER MODULE (the tab name is the module,
   *  so no Module column is needed). Each module carries its own FE APIs + log verdicts. */
  const capabilityScopeByModule = (): CapabilityScope => {
    const groups = perModule
      .filter((p) => p.report && p.report.apis.length > 0)   // skip dependency repos (no APIs of their own)
      .map((p) => {
        const rep = p.report!;
        const feApis = [...new Set(rep.apis.map((a) => a.api).filter(Boolean))];
        const statusByApi: Record<string, string> = {};
        for (const a of rep.apis) statusByApi[a.api] = STATUS_LABEL[a.status];
        return { name: p.name, feApis, beApis: [], statusByApi };
      });
    return { feApis: [], beApis: [], country, modules: groups, fileName: 'Release Test - Capability Matrix' };
  };
  /** VAL Capability Matrix export (.xlsx) for the impacted APIs — how to test each, for the testing team.
   *  Multi-module: ONE workbook with a tab per module (like the consolidated PDFs). */
  const exportCapabilities = () => {
    if (multi) {
      const scope = capabilityScopeByModule();
      if (scope.modules && scope.modules.length) exportCapabilitiesXlsx(scope).catch(() => {});
      return;
    }
    if (report) exportCapabilitiesXlsx(capabilityScope()).catch(() => {});
  };
  /** Leadership Summary PDF. Multi-module: ONE consolidated doc across every module (like Release Impact),
   *  each module's APIs enriched with its own VAL capabilities. Single module: the one report. */
  const exportSummaryPdf = async () => {
    if (multi) {
      const src = exportableModules();
      if (!src.length) return;
      const mods = await Promise.all(src.map(async (p) => {
        let caps: CapabilityResult | undefined;
        if (p.report) { try { caps = await resolveCapabilities(scopeForReport(p.report)); } catch { caps = undefined; } }
        return { name: p.name, report: p.report, error: p.error, caps };
      }));
      exportLogSummaryPdfMulti(mods, app, version, country).catch(() => {});
      return;
    }
    if (!report) return;
    let caps: CapabilityResult | undefined;
    try { caps = await resolveCapabilities(capabilityScope()); } catch { caps = undefined; }
    exportLogSummaryPdf(report, app, version, country, caps).catch(() => {});
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
          <button className={inputType === 'SPLUNK' ? 'on' : ''} onClick={() => setInputType('SPLUNK')}>Splunk report</button>
          <button className={inputType === 'OUTPUT_LOG' ? 'on' : ''} onClick={() => setInputType('OUTPUT_LOG')}>Output log</button>
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

      <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <button className="trace" disabled={!canAnalyse || loading} onClick={run}>
          {loading ? 'Analysing…' : files.length > 1 ? `Analyse ${files.length} files` : 'Analyse'}
        </button>
        {report && canAnalyse && !loading && (
          <button className="minibtn accent" onClick={run}
                  title="Re-analyse the SAME uploaded log(s) with the current host response-code rules — no need to re-attach the file after editing a rule.">
            <RotateCw aria-hidden="true" /> Re-run with current rules
          </button>
        )}
      </div>

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

      {loading && !report && perModule.length === 0 && (
        <div className="sk-report" aria-hidden="true">
          <div className="sk-band"><span className="sk" /><span className="sk" /><span className="sk" /><span className="sk" /></div>
          <div className="sk-rows"><span className="sk" /><span className="sk" /><span className="sk" /><span className="sk" /><span className="sk" /></div>
        </div>
      )}

      {error && <div className="err">Error: {error}</div>}

      {multi && perModule.length > 0 && (
        <div style={{ marginTop: 12 }} ref={resultsRef}>
          <div className="row between" style={{ marginBottom: 6, flexWrap: 'wrap', gap: 6 }}>
            <span className="sub" style={{ margin: 0 }}>Test coverage by module — worst first · click one to see its APIs below. The exported PDF covers all {perModule.length}.</span>
            <span className="cov-roll">
              {cov.passed > 0 && <span className="lm-ok">{cov.passed} passed</span>}
              {cov.notTested > 0 && <span className="lm-nt">{cov.notTested} not tested</span>}
              {cov.issues > 0 && <span className="lm-bad">{cov.issues} issue{cov.issues === 1 ? '' : 's'}</span>}
              {cov.attention > 0 && <span className="attn-chip">{cov.attention} need attention</span>}
            </span>
          </div>
          <div className="logmods">
            {sortedPer.map((p) => {
              const t = p.report ? tally(p.report) : null;
              const rd = p.error ? null : t ? READINESS[readiness(t)] : null;
              return (
                <button key={p.id} className={'logmod' + (p.id === activeLogId ? ' active' : '') + (p.error ? ' err' : '')}
                        onClick={() => selectLogModule(p.id)} title={p.error || undefined}>
                  <div className="logmod-name">{p.name}
                    {p.error ? <span className="rd-pill risk">Not analysed</span>
                      : rd && <span className={'rd-pill ' + rd.cls}>{rd.label}</span>}
                  </div>
                  {p.error ? <div className="logmod-sub err">{p.error}</div>
                    : t ? (
                      <div className="logmod-stats">
                        {/* Show a count only when it's non-zero — a 0 means "the log derived none of
                            these", which is noise. All-not-tested reads as just "N not tested". */}
                        {t.passed > 0 && <span className="lm-ok">{t.passed} passed</span>}
                        {t.issues > 0 && <span className="lm-bad">{t.issues} issue{t.issues === 1 ? '' : 's'}</span>}
                        {t.notTested > 0 && <span className="lm-nt hot">{t.notTested} not tested</span>}
                        {t.passed === 0 && t.issues === 0 && t.notTested === 0 && <span className="lm-nt">{t.total} API{t.total === 1 ? '' : 's'}</span>}
                      </div>
                    ) : <div className="logmod-sub">—</div>}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {report && (
        <div className="export-bar" style={{ paddingLeft: 0, paddingRight: 0 }}>
          <div className="export-bar-right">
            <button className="minibtn" onClick={exportCapabilities} title="How to test each impacted API — the VAL Capability Matrix rows for the FE APIs, with each API's test verdict (needs the VAL reports attached in ⚙ Config)"><Table2 aria-hidden="true" /> Release Test - Capability Matrix</button>
            <button className="minibtn" onClick={exportSummaryPdf} title="1–2 page verification summary for release managers & delivery leads"><FileText aria-hidden="true" /> Summary PDF</button>
            <button className="minibtn" onClick={exportPdf} title="Full verification report (response codes, latency, backends) for developers & testers"><FileStack aria-hidden="true" /> Detailed PDF</button>
          </div>
        </div>
      )}

      {report && viewMode === 'summary' && (
        <div style={{ marginTop: 4 }} ref={multi ? undefined : resultsRef}>
          <TestSummary report={report} />
        </div>
      )}

      {report && viewMode !== 'summary' && (
        <div style={{ marginTop: 12, scrollMarginTop: 12 }} ref={multi ? undefined : resultsRef}>
          {multi && activeLogId && (
            <div className="sub" style={{ marginBottom: 6 }}>Showing module <b>{perModule.find((p) => p.id === activeLogId)?.name || 'module'}</b>.</div>
          )}
          <div className="report-sticky">
          <div className="kv">
            <b>{report.transactions}</b> transactions · <b>{report.matchedLines}</b> matched / {report.linesScanned} lines
            {report.unparsedLines > 0 ? ` · ${report.unparsedLines} unparsed` : ''} · {report.uploadType}
            {(() => {
              // The window the log actually covers — from the active module, or the overall span across all
              // modules when one upload fed several. Tells you how much time the analysed logs represent.
              const rng = logRange(multi ? overallRange(perModule.map((p) => p.report)) : report);
              return rng
                ? <> · <span title="Time span of the analysed log lines — earliest → latest timestamp (across all modules when several were analysed).">🕑 {rng}</span></>
                : null;
            })()}
          </div>

          {(() => {
            // Prominent overall verdict for higher-ups: readiness + the front-end pass rate at a glance.
            const t = tally(report);
            const testedFe = t.passed + t.issues;
            const rate = testedFe > 0 ? Math.round((100 * t.passed) / testedFe) : 0;
            const rd = readiness(t);
            const label = rd === 'risk' ? 'At risk' : rd === 'review' ? 'Needs review' : rd === 'ready' ? 'Ready' : 'No data';
            const VIcon = rd === 'risk' ? CircleX : rd === 'review' ? AlertTriangle : rd === 'ready' ? CircleCheck : Minus;
            return (
              <div className={'test-verdict ' + rd} role="status">
                <span className="tv-badge"><VIcon size={14} aria-hidden="true" /> {label}</span>
                <span className="tv-detail">
                  {t.passed} passed · {t.issues} issue{t.issues === 1 ? '' : 's'} · {t.notTested} not tested
                  {testedFe > 0 && <> · <b>{rate}%</b> pass rate <span className="muted">({t.passed}/{testedFe} tested)</span></>}
                </span>
              </div>
            );
          })()}

          {/* Readiness KPI band — the mock's hero tiles (total / passed / issues / not tested). */}
          {(() => {
            const t = tally(report);
            return (
              <div className="mod-rollup" style={{ marginTop: 10 }}>
                <div className="mr-tile"><div className="mr-n info">{t.total}</div><div className="mr-l">Total APIs</div></div>
                <div className="mr-tile"><div className="mr-n good">{t.passed}</div><div className="mr-l">Passed</div></div>
                <div className="mr-tile"><div className="mr-n bad">{t.issues}</div><div className="mr-l">Issues</div></div>
                <div className="mr-tile"><div className="mr-n warn">{t.notTested}</div><div className="mr-l">Not tested</div></div>
              </div>
            );
          })()}

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
            </span>
          </div>

          {showFe && (
            <table className="grid">
              <thead>
                <tr>
                  <th title="Overall verdict for this API. Coverage first: if any impacted backend flow wasn't tested it's Partial; if a flow failed it's Failed. Once every flow is tested, the front-end pass rate decides — Success at/above the threshold, else Failed. So it can differ from the Latest Result. Open 'details' to see why.">Status</th>
                  <th>Front-end API</th>
                  <th title="The LATEST run's front-end (controller) response — its responseCode / description. Just the most recent call's reply, NOT the aggregate verdict; see Status (and Attempts for the pass/fail counts).">Latest Result</th>
                  <th>Latency</th><th>Attempts</th><th />
                </tr>
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
                <tr>
                  <th title="This backend's verdict — its response code plus the service-version check. Can differ from Result (e.g. a 200/all-zeros response but a service-version mismatch reads as Failed).">Status</th>
                  <th>Backend</th>
                  <th title="This backend's response — its responseCode / description.">Result</th>
                  <th>Latency</th><th>Attempts</th><th />
                </tr>
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
