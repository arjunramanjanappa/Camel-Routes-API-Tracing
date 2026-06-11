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

function Badge({ s }: { s: LogStatus }) {
  return <span className={'lstat ' + s.toLowerCase()}>{STATUS_LABEL[s]}</span>;
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
  impactedApis?: string[];
}

/**
 * Upload an output log (or, soon, a Splunk export) and correlate it against the
 * traced APIs for the current client release — which APIs were exercised and
 * whether they passed end-to-end.
 */
export default function LogAnalysisPanel({ version, country, sourceDir, impactedApis = [] }: Props) {
  const [inputType, setInputType] = useState<InputType>('OUTPUT_LOG');
  const [file, setFile] = useState<File | null>(null);
  const [onlyImpacted, setOnlyImpacted] = useState(false);
  const [report, setReport] = useState<LogAnalysisReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const fileRef = useRef<HTMLInputElement>(null);

  const run = async () => {
    if (!file) return;
    setLoading(true);
    setError(null);
    try {
      const apis = onlyImpacted && impactedApis.length ? impactedApis : undefined;
      const rep = await analyzeLog(file, { version, country, sourceDir, apis });
      setReport(rep);
      setOpen(new Set());
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

  const toggle = (k: string) => {
    const n = new Set(open);
    if (n.has(k)) n.delete(k); else n.add(k);
    setOpen(n);
  };

  const exportCsv = () => {
    if (!report) return;
    const rows = [['api', 'operation', 'status', 'tested', 'responseCode', 'responseDescription',
      'feLatencyMs', 'attempts', 'success', 'failure', 'latestAt', 'correlationId', 'note']];
    report.apis.forEach((a) => rows.push([
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

      {impactedApis.length > 0 && (
        <label className="check" style={{ marginTop: 8 }}>
          <input type="checkbox" checked={onlyImpacted} onChange={(e) => setOnlyImpacted(e.target.checked)} />
          Limit to the {impactedApis.length} impacted API(s)
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
          <div className="row" style={{ gap: 6, flexWrap: 'wrap', margin: '6px 0' }}>
            {STATUS_ORDER.filter((s) => counts[s]).map((s) => (
              <span key={s} className={'lstat ' + s.toLowerCase()}>{counts[s]} {STATUS_LABEL[s]}</span>
            ))}
          </div>
          {report.warnings.map((w, i) => <div key={i} className="warn">{w}</div>)}

          <div className="row between" style={{ marginTop: 6 }}>
            <span className="muted">{report.apis.length} APIs</span>
            <button className="minibtn" onClick={exportCsv}>Export CSV</button>
          </div>

          <table className="grid">
            <thead>
              <tr><th>Status</th><th>API</th><th>Result</th><th>Latency</th><th>Attempts</th><th /></tr>
            </thead>
            <tbody>
              {report.apis.map((a) => {
                const k = a.api + a.operation;
                return <Row key={k} a={a} isOpen={open.has(k)} onToggle={() => toggle(k)} />;
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
