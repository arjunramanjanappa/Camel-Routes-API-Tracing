import { useMemo, useRef, useState } from 'react';
import { analyzeLog } from '../api';
import type { ApiLogResult, LogAnalysisReport, LogStatus } from '../types';
import { downloadText } from '../spl';

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
      <text x="43" y="54" textAnchor="middle" className="donut-lbl">APIs</text>
    </svg>
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
        <td>{a.attempts > 0 ? `${a.attempts} (${a.successCount}✓/${a.failureCount}✗)` : '—'}</td>
        <td>{a.backends.length > 0 && <button className="linkbtn" onClick={onToggle}>{isOpen ? 'hide' : 'backends'}</button>}</td>
      </tr>
      {isOpen && a.backends.map((b, i) => (
        <tr key={i} className="lsub">
          <td><Badge s={b.status} /></td>
          <td colSpan={2}>
            <code>{b.backend}</code>
            <span className="muted">{b.observedPath ? ' seen: ' + b.observedPath : ' not observed'}</span>
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
  selectedApis?: string[];
}

/**
 * Upload an output log (or, soon, a Splunk export) and correlate it against the
 * traced APIs for the current client release — which APIs were exercised and
 * whether they passed end-to-end.
 */
export default function LogAnalysisPanel({ version, country, sourceDir, selectedApis = [] }: Props) {
  const [inputType, setInputType] = useState<InputType>('OUTPUT_LOG');
  const [file, setFile] = useState<File | null>(null);
  const [onlySelected, setOnlySelected] = useState(false);
  const [report, setReport] = useState<LogAnalysisReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<LogStatus | 'ALL' | 'ISSUES'>('ALL');
  const [sort, setSort] = useState<'severity' | 'api'>('severity');
  const fileRef = useRef<HTMLInputElement>(null);

  const run = async () => {
    if (!file) return;
    setLoading(true);
    setError(null);
    try {
      const apis = onlySelected && selectedApis.length ? selectedApis : undefined;
      const rep = await analyzeLog(file, { version, country, sourceDir, apis });
      setReport(rep);
      setOpen(new Set());
      setFilter('ALL');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const counts = useMemo(() => {
    const c = {} as Record<LogStatus, number>;
    report?.apis.forEach((a) => { c[a.status] = (c[a.status] || 0) + 1; });
    return c;
  }, [report]);
  const issuesCount = useMemo(() => report?.apis.filter((a) => a.status !== 'SUCCESS').length ?? 0, [report]);

  // Apply the status filter + sort to get the rows actually shown.
  const shown = useMemo(() => {
    if (!report) return [];
    let list = report.apis;
    if (filter === 'ISSUES') list = list.filter((a) => a.status !== 'SUCCESS');
    else if (filter !== 'ALL') list = list.filter((a) => a.status === filter);
    return [...list].sort((a, b) => (sort === 'api'
      ? a.api.localeCompare(b.api)
      : SEVERITY[a.status] - SEVERITY[b.status] || a.api.localeCompare(b.api)));
  }, [report, filter, sort]);

  const toggle = (k: string) => {
    const n = new Set(open);
    if (n.has(k)) n.delete(k); else n.add(k);
    setOpen(n);
  };
  const pick = (f: LogStatus | 'ALL' | 'ISSUES') => setFilter((cur) => (cur === f ? 'ALL' : f));

  const exportCsv = () => {
    if (!report) return;
    const rows = [['api', 'operation', 'status', 'tested', 'responseCode', 'responseDescription',
      'feLatencyMs', 'attempts', 'success', 'failure', 'latestAt', 'correlationId', 'note']];
    shown.forEach((a) => rows.push([
      a.api, a.operation, a.status, String(a.tested), a.responseCode || '', a.responseDescription || '',
      a.feLatencyMs == null ? '' : String(a.feLatencyMs), String(a.attempts), String(a.successCount),
      String(a.failureCount), a.latestAt || '', a.correlationId || '', a.note || '',
    ]));
    downloadText('log-analysis.csv', rows.map((r) => r.map((c) => `"${(c || '').replace(/"/g, '""')}"`).join(',')).join('\n'));
  };

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
          Upload the CSV or JSON report you exported from Splunk for the generated query. The original event is
          read from its <code>_raw</code> field, so the format is detected automatically.
        </div>
      )}

      <div className={'uploader' + (file ? ' has' : '')} onClick={() => fileRef.current?.click()}>
        <input ref={fileRef} type="file"
               accept={inputType === 'SPLUNK' ? '.csv,.json,.gz' : '.log,.txt,.gz'} style={{ display: 'none' }}
               onChange={(e) => setFile(e.target.files?.[0] || null)} />
        {file
          ? <span><b>{file.name}</b> · {(file.size / 1024).toFixed(0)} KB — click to change</span>
          : inputType === 'SPLUNK'
            ? <span>Click to choose a Splunk export (.csv / .json / .gz)</span>
            : <span>Click to choose an output log file (.log / .txt / .gz)</span>}
      </div>

      {selectedApis.length > 0 && (
        <label className="check" style={{ marginTop: 8 }}>
          <input type="checkbox" checked={onlySelected} onChange={(e) => setOnlySelected(e.target.checked)} />
          Limit to the {selectedApis.length} selected API(s)
        </label>
      )}

      <button className="trace" disabled={!file || loading} onClick={run}>
        {loading ? 'Analysing…' : 'Analyse'}
      </button>

      {error && <div className="err">Error: {error}</div>}

      {report && (
        <div style={{ marginTop: 12 }}>
          <div className="kv">
            <b>{report.transactions}</b> transactions · <b>{report.matchedLines}</b> matched / {report.linesScanned} lines
            {report.unparsedLines > 0 ? ` · ${report.unparsedLines} unparsed` : ''} · {report.uploadType}
          </div>

          <div className="report-summary">
            <Donut counts={counts} />
            <div className="report-side">
              <div className="fchips">
                <button className={'fchip all' + (filter === 'ALL' ? ' active' : '')} onClick={() => pick('ALL')}>All {report.apis.length}</button>
                {issuesCount > 0 && (
                  <button className={'fchip issues' + (filter === 'ISSUES' ? ' active' : '')} onClick={() => pick('ISSUES')}>Issues {issuesCount}</button>
                )}
                {STATUS_ORDER.filter((s) => counts[s]).map((s) => (
                  <button key={s} className={'lstat fchip ' + s.toLowerCase() + (filter === s ? ' active' : '')} onClick={() => pick(s)}>
                    {STATUS_LABEL[s]} {counts[s]}
                  </button>
                ))}
              </div>
              <div className="sub" style={{ marginTop: 2 }}>Click a status to filter the table below.</div>
            </div>
          </div>

          {report.warnings.map((w, i) => <div key={i} className="warn">{w}</div>)}

          <div className="row between" style={{ marginTop: 8 }}>
            <span className="muted">Showing {shown.length} of {report.apis.length}</span>
            <span className="row" style={{ gap: 8 }}>
              <select className="sortsel" value={sort} onChange={(e) => setSort(e.target.value as 'severity' | 'api')}>
                <option value="severity">Sort: worst first</option>
                <option value="api">Sort: API name</option>
              </select>
              <button className="minibtn" onClick={exportCsv}>Export CSV</button>
            </span>
          </div>

          <table className="grid">
            <thead>
              <tr><th>Status</th><th>API</th><th>Result</th><th>Latency</th><th>Attempts</th><th /></tr>
            </thead>
            <tbody>
              {shown.map((a) => {
                const k = a.api + a.operation;
                return <Row key={k} a={a} isOpen={open.has(k)} onToggle={() => toggle(k)} />;
              })}
              {shown.length === 0 && (
                <tr><td colSpan={6} className="muted" style={{ padding: 10 }}>No APIs match this filter.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
