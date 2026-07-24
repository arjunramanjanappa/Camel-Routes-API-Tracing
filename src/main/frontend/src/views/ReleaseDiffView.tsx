import { useMemo, useState } from 'react';
import { fetchVersionDiff, analyzeLogMulti } from '../api';
import type { ApiDiff, ApiLogResult, DepSource, DiffStatus, ImpactedRoute, RouteStepDiff, VersionDiffReport } from '../types';
import { exportDiffPdf } from '../diffPdf';
import { exportDiffSummaryPdf } from '../diffSummaryPdf';
import ImpactSummary from '../components/ImpactSummary';
import Loader from '../components/Loader';
import ApiFlowModal from '../components/ApiFlowModal';
import { sourceParams } from '../components/SourceFields';
import ModulesEditor from '../components/ModulesEditor';
import ModuleSummary, { type ModuleStat } from '../components/ModuleSummary';
import NeedsReviewBox from '../components/NeedsReviewBox';
import InfoBanner from '../components/InfoBanner';
import { depParams, loadDeps, saveDeps } from '../deps';
import { analyzeModules, moduleValid, type ModuleResult } from '../modules';
import { useAppModules } from '../appModules';

// Context (sourceDir + country) is remembered per application, like the other tabs.
function appKey(app: string | undefined, f: string) { return `tracer.${app || 'Mighty'}.${f}`; }
function cardKey(d: ApiDiff) { return d.api + '|' + d.operation; }

const DIFF_MESSAGES = [
  'Scanning the framework source…',
  'Resolving each API to the target version…',
  'Finding the immediate-lower version per API…',
  'Tracing both flows end to end…',
  'Diffing the route bodies…',
];

function statusLabel(s: DiffStatus): string {
  return s === 'NEW' ? 'New' : s === 'CHANGED' ? 'Changed' : 'No change';
}

type Risk = 'High' | 'Medium' | 'Low';
const RISK_RANK: Record<Risk, number> = { High: 0, Medium: 1, Low: 2 };
const RISK_CLASS: Record<Risk, string> = { High: 'high', Medium: 'med', Low: 'low' };
function riskOf(a: ApiDiff): Risk { return (a.risk as Risk) || 'Low'; }
/** Backward compatibility must be verified when a payload field was removed OR a shared class changed. */
function needsBC(a: ApiDiff): boolean { return !!a.payloadChange?.removedKeys?.length || !!a.codeChanged; }
function bcReason(a: ApiDiff): string {
  const parts: string[] = [];
  if (a.payloadChange?.removedKeys?.length) parts.push(`${a.payloadChange.removedKeys.length} payload field(s) removed — backend must accept old clients`);
  if (a.codeChanged) parts.push('shared class changed — regression-test the older (BAU) version against the new code');
  return parts.join('; ');
}

// Version keys for the per-version test-log correlation (BASE for un-versioned / N/A).
const ROUTE_VER = /^R(\d+(?:\.\d+)*)_/;
function normVer(v?: string | null): string { return v && v !== 'N/A' && v !== 'BASE' ? v : 'BASE'; }
/** The release version an impacted route belongs to (from its route id), for looking up its own log result. */
function routeVersion(ir: ImpactedRoute): string {
  for (const rid of ir.routePath) { const m = ROUTE_VER.exec(rid); if (m) return m[1]; }
  return 'BASE';
}
/** A tested badge from an uploaded log's per-API result, or null when no log covers this API. */
function testedMeta(l?: ApiLogResult): { cls: string; label: string; title: string } | null {
  if (!l) return null;
  if (!l.tested) return { cls: 'nt', label: 'Not tested', title: 'No matching transaction in the uploaded log' };
  if (l.status === 'SUCCESS') return { cls: 'pass', label: 'Tested ✓', title: `Executed ${l.attempts}×, all passed` };
  if (l.status === 'FAILED' || l.status === 'TIMEOUT') return { cls: 'fail', label: 'Failed', title: `${l.failureCount}/${l.attempts} failed (${l.responseCode || l.status})` };
  if (l.status === 'PARTIAL') return { cls: 'part', label: 'Partial', title: `${l.successCount}/${l.attempts} passed` };
  return { cls: 'ind', label: 'Ran (unclear)', title: 'Executed but pass/fail could not be determined' };
}
/** Counts to-test APIs (Changed/New) covered by a log and how many passed. */
function testedTally(report: VersionDiffReport, log?: Record<string, ApiLogResult>): { covered: number; passed: number; toTest: number } {
  const toTestApis = report.apis.filter((a) => effectiveStatus(a) !== 'UNCHANGED' && a.status !== 'SNAPSHOT');
  let covered = 0, passed = 0;
  if (log) for (const a of toTestApis) {
    const l = log[a.api];
    if (l?.tested) { covered++; if (l.status === 'SUCCESS') passed++; }
  }
  return { covered, passed, toTest: toTestApis.length };
}

/** Why this API needs testing — compact reasons for the checklist/tooltip. */
function riskReasons(a: ApiDiff): string[] {
  const why: string[] = [];
  if (a.codeChanged) why.push('shared Java class changed');
  if (a.payloadChange?.removedKeys?.length) why.push('payload field removed (backward-incompatible)');
  if (a.payloadChange?.addedKeys?.length) why.push('payload field added');
  if (a.backendVersionChanges?.length) why.push('backend service version bumped');
  if (a.status === 'NEW') why.push('new API');
  else if (a.status === 'CHANGED' && !why.length) why.push('flow changed');
  return why;
}

/**
 * The tab a diff belongs to. A NEW API that changed shared BAU code is grouped under Changed — that Java
 * change means BAU APIs using the class need regression-testing, so it belongs where testers look for changes.
 * (The card still shows it was newly added.) Mirrors the backend's New→Changed count promotion.
 */
function effectiveStatus(a: ApiDiff): DiffStatus {
  if (a.status === 'NEW' && a.codeChanged) return 'CHANGED';
  return a.status as DiffStatus;
}

/** Everything an API diff matches against in the search box. */
function searchHaystack(a: ApiDiff): string {
  return [a.api, a.operation, a.targetRoute, a.lowerRoute,
    ...(a.addedRoutes || []), ...(a.removedRoutes || []),
    ...(a.routeDiffs || []).map((r) => r.routeBase),
    ...(a.backendVersionChanges || []).map((s) => s.backend),
    ...(a.changedClasses || []), ...(a.impactedRoutes || []).flatMap((r) => [...r.routePath, r.api || ''])]
    .filter(Boolean).join(' ').toLowerCase();
}

/** A plain-text rendering of one API's diff (for copy + export). */
function apiDiffText(a: ApiDiff): string {
  const lines = [`[${a.status}] ${a.api}  (${a.operation})`];
  if (a.note) lines.push(`    ${a.note}`);
  else if (a.status !== 'NEW') lines.push(`    ${a.targetRoute} <- ${a.lowerRoute}`);
  a.addedRoutes.forEach((r) => lines.push(`    + route ${r}`));
  a.removedRoutes.forEach((r) => lines.push(`    - route ${r}`));
  (a.backendVersionChanges || []).forEach((s) => lines.push(`    ~ svc ${s.backend}: ${s.fromVersion} -> ${s.toVersion}`));
  a.routeDiffs.forEach((rd) => {
    lines.push(`    ~ ${rd.routeBase}`);
    rd.removed.forEach((l) => lines.push(`        - ${l}`));
    rd.added.forEach((l) => lines.push(`        + ${l}`));
  });
  if (a.codeChanged) {
    lines.push('    ⚙ code changed by app version (shared @Component classes):');
    (a.changedClasses || []).forEach((c) => lines.push(`        ~ class ${c}`));
    (a.impactedRoutes || []).forEach((r) => lines.push(`        ! also re-test [${impactGroup(r)}] ${r.api ? r.api + ' — ' : ''}${r.routePath.join(' → ')}`));
  }
  return lines.join('\n');
}

type ImpactGroup = 'Current' | 'BAU' | 'Future' | 'Unknown';
const IMPACT_ORDER: ImpactGroup[] = ['Current', 'BAU', 'Future', 'Unknown'];
const IMPACT_META: Record<ImpactGroup, { icon: string; label: string; desc: string }> = {
  Current: { icon: '●', label: 'Current release', desc: 'this release — verify the change here' },
  BAU: { icon: '▲', label: 'BAU (in production)', desc: 'live now — regression-test' },
  Future: { icon: '◆', label: 'Future release', desc: "pre-test now; won't resurface under its own version" },
  Unknown: { icon: '?', label: 'Unknown (untraced)', desc: 'not wired to a controller — trace & verify manually' },
};
/** The display group: a route with no resolved API is bucketed as Unknown (needs manual back-trace). */
function impactGroup(r: ImpactedRoute): ImpactGroup {
  return r.api ? (r.category as ImpactGroup) : 'Unknown';
}

/** The code-change section: which Java @Component classes the release modified, and the API/routes to re-test. */
function CodeChangeBlock({ d, onOpenApi, routeLog }: { d: ApiDiff; onOpenApi?: (api: string) => void; routeLog?: (r: ImpactedRoute) => ApiLogResult | undefined }) {
  if (!d.codeChanged) return null;
  const classes = d.changedClasses || [];
  const impacted = d.impactedRoutes || [];
  // Group the re-test routes (Current / BAU / Future / Unknown); each is its own tinted sub-block.
  const byCat = IMPACT_ORDER
    .map((cat) => ({ cat, rows: impacted.filter((r) => impactGroup(r) === cat) }))
    .filter((g) => g.rows.length > 0);
  return (
    <div className="diff-code" title="Pre-existing (BAU) @Component Java classes wired into this API's flow that the app-version release modified">
      <span className="diff-code-label">⚙ Code changed</span>
      {classes.map((c) => {
        // Label: "bean (File.java) · <versions> — <authors>"  (· versions and — authors both optional).
        const dash = c.lastIndexOf(' — ');
        const head = dash >= 0 ? c.slice(0, dash) : c;
        const authors = dash >= 0 ? c.slice(dash + 3) : '';
        const dot = head.indexOf(' · ');
        const name = dot >= 0 ? head.slice(0, dot) : head;
        const vers = dot >= 0 ? head.slice(dot + 3) : '';
        return (
          <span key={'c' + c} className="chg code" title="changed @Component class — app version(s) that changed it, and commit authors">
            {name}
            {vers && <span className="code-ver" title="app/commit version(s) that changed this class">{vers}</span>}
            {authors && <span className="code-auth"> — {authors}</span>}
          </span>
        );
      })}
      {byCat.length > 0 && (
        <div className="diff-code-cross">
          <div className="diff-code-cross-head">⚠ Shared code — also re-test:</div>
          <div className="impact-cats">
            {byCat.map((g) => (
              <div key={g.cat} className={'impact-cat ' + g.cat.toLowerCase()}>
                <div className="impact-cat-head">
                  <span className="impact-cat-icon">{IMPACT_META[g.cat].icon}</span>
                  <span className="impact-cat-label">{IMPACT_META[g.cat].label}</span>
                  <span className="impact-cat-count">{g.rows.length}</span>
                  <span className="impact-cat-desc">{IMPACT_META[g.cat].desc}</span>
                </div>
                {g.rows.map((r) => {
                  const rl = testedMeta(routeLog?.(r));
                  return (
                  <div key={r.routePath.join('>')} className="impact-row">
                    {r.api && (
                      <>
                        {onOpenApi
                          ? <button type="button" className="impact-api linkish" title="Open the flow graph for this API" onClick={() => onOpenApi(r.api!)}>{r.api}</button>
                          : <code className="impact-api">{r.api}</code>}
                        <span className="impact-dash">—</span>
                      </>
                    )}
                    <span className="impact-chain">
                      {r.routePath.map((rt, i) => (
                        <span key={rt}>
                          {i > 0 && <span className="impact-arrow"> → </span>}
                          <code className="impact-route">{rt}</code>
                        </span>
                      ))}
                    </span>
                    {rl && <span className={'tested-badge sm ' + rl.cls} title={rl.title}>{rl.label}</span>}
                  </div>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** Release readiness at a glance: how much to test, how risky, and (if a log is merged) how much is tested. */
function ReadinessStrip({ report, log }: { report: VersionDiffReport; log?: Record<string, ApiLogResult> }) {
  const toTest = report.changedCount + report.newCount;
  const high = report.highRiskCount ?? 0;
  const bc = report.backwardCompatCount ?? 0;
  const code = report.codeChangedCount ?? 0;
  if (toTest === 0 && high === 0 && bc === 0) return null;
  const t = testedTally(report, log);
  return (
    <div className="readiness" role="group" aria-label="Release readiness">
      <span className="rd-chip total" title="Changed + new APIs to regression-test this release"><b>{toTest}</b> to test</span>
      <span className="rd-chip high" title="High test-priority: shared-class change, removed payload field, or backend version bump"><b>{high}</b> high risk</span>
      {report.appVersion && <span className="rd-chip code" title="APIs with a shared Java class change"><b>{code}</b> code-changed</span>}
      <span className="rd-chip bc" title="APIs that removed/renamed a payload field — backend must stay backward compatible"><b>{bc}</b> backward-compat</span>
      {log && <span className="rd-chip tested" title="Of the changed/new APIs, how many the uploaded log shows executed & passed"><b>{t.passed}</b>/{t.toTest} tested &amp; passed</span>}
    </div>
  );
}

/** A release-level banner summarising the app-version code scan. */
function CodeChangeSummary({ report }: { report: VersionDiffReport }) {
  if (!report.appVersion) return null;
  const n = report.codeChangedCount ?? 0;
  return (
    <div className="codebanner">
      <div className="codebanner-head">
        <span className="codebanner-icon">⚙</span>
        <b>App version {report.appVersion}</b>
        {report.codeChangeUnavailable ? (
          <span className="muted"> · source is not a git work tree — code-change detection skipped</span>
        ) : (
          <span className="muted">
            {' · '}{report.matchedCommits ?? 0} commit{(report.matchedCommits ?? 0) === 1 ? '' : 's'} tagged
            {' · '}{n} API{n === 1 ? '' : 's'} with a shared Java class change
          </span>
        )}
      </div>
    </div>
  );
}

/** At-a-glance change chips: which routes were edited / added / removed. */
function changeChips(d: ApiDiff) {
  const chips: { key: string; cls: string; sym: string; text: string; title: string }[] = [];
  (d.routeDiffs || []).forEach((rd) =>
    chips.push({ key: 'e' + rd.routeBase, cls: 'edited', sym: '✎', text: rd.routeBase, title: 'route body changed' }));
  (d.addedRoutes || []).forEach((r) =>
    chips.push({ key: '+' + r, cls: 'added', sym: '+', text: r, title: 'sub-route added by this release' }));
  (d.removedRoutes || []).forEach((r) =>
    chips.push({ key: '-' + r, cls: 'removed', sym: '−', text: r, title: 'sub-route removed by this release' }));
  return chips;
}

/** One route's +added / −removed canonical lines, with a per-route line tally. */
function RouteDiffBlock({ d }: { d: RouteStepDiff }) {
  return (
    <div className="rdiff">
      <div className="rdiff-head">
        <code>{d.routeBase}</code>
        <span className="row" style={{ gap: 8 }}>
          <span className="rdiff-tally"><span className="add">+{d.added.length}</span> <span className="del">−{d.removed.length}</span></span>
          <span className="muted">{d.targetRoute} ⟵ {d.lowerRoute}</span>
        </span>
      </div>
      {d.changedBy && d.changedBy.length > 0 && (
        <div className="rdiff-by"><span className="rdiff-by-label">Changed by</span> {d.changedBy.join(', ')}</div>
      )}
      <pre className="rdiff-body">
        {d.removed.map((l, i) => <div key={'r' + i} className="dl del">- {l}</div>)}
        {d.added.map((l, i) => <div key={'a' + i} className="dl add">+ {l}</div>)}
      </pre>
    </div>
  );
}

function ApiDiffCard({ d, open, onToggle, onViewFlow, onCopy, copied, log, onOpenApi, routeLog, remark, onRemark }: {
  d: ApiDiff; open: boolean; onToggle: () => void;
  onViewFlow: () => void; onCopy: () => void; copied: boolean; log?: ApiLogResult;
  onOpenApi?: (api: string) => void; routeLog?: (r: ImpactedRoute) => ApiLogResult | undefined;
  remark?: string; onRemark?: (text: string) => void;
}) {
  const svc = d.backendVersionChanges || [];
  const tested = testedMeta(log);
  const [editingRemark, setEditingRemark] = useState(false);
  const [remarkDraft, setRemarkDraft] = useState(remark ?? '');
  // An UNCHANGED card with a note is a fallback API (no route at the target version).
  const fallback = d.status === 'UNCHANGED' && !!d.note;
  const showPill = !!d.lowerVersion && (d.status === 'CHANGED' || (d.status === 'UNCHANGED' && !fallback));
  const chips = changeChips(d);
  return (
    <div className={'diff-card ' + d.status.toLowerCase()}>
      <div className="diff-card-head row between">
        <div className="diff-card-id">
          <code>{d.api}</code>
          <span className="muted op">{d.operation}</span>
        </div>
        <span className="row" style={{ gap: 6 }}>
          {showPill && (
            <span className="ver-pill"><b>{d.lowerVersion}</b><span className="ver-arrow">→</span><b>{d.targetVersion}</b></span>
          )}
          {d.codeChanged && (
            <span className="diff-badge code" title="A Java class or route XML in this API's flow was changed by the app-version release">Changed (code)</span>
          )}
          <span className={'risk-badge ' + RISK_CLASS[riskOf(d)]} title={'Test priority: ' + riskOf(d) + (riskReasons(d).length ? ' — ' + riskReasons(d).join('; ') : '')}>{riskOf(d)} risk</span>
          {needsBC(d) && <span className="bc-badge" title={'Backward compatibility required — ' + bcReason(d)}>BC</span>}
          {tested && <span className={'tested-badge ' + tested.cls} title={tested.title}>{tested.label}</span>}
          <span className={'diff-badge ' + d.status.toLowerCase()}>{statusLabel(d.status as DiffStatus)}</span>
        </span>
      </div>

      <div className="diff-verdict">
        {d.status === 'NEW' ? (
          <>Added in <b>{d.targetVersion}</b> — no earlier version to compare against. <span className="tag route">{d.targetRoute}</span>
            {d.authors && d.authors.length > 0 && (
              <span className="diff-added-by"><span className="rdiff-by-label">Added by</span> {d.authors.join(', ')}</span>
            )}</>
        ) : fallback ? (
          <><span className="tag route lower">{d.targetRoute}</span><span className="muted"> · {d.note}</span></>
        ) : (
          <>
            <span className="tag route">{d.targetRoute}</span>
            <span className="diff-arrow">⟵</span>
            <span className="tag route lower">{d.lowerRoute}</span>
            {d.status === 'UNCHANGED' && <span className="muted"> · version bumped, identical flow</span>}
          </>
        )}
      </div>

      {chips.length > 0 && (
        <div className="diff-changes">
          {chips.map((c) => (
            <span key={c.key} className={'chg ' + c.cls} title={c.title}>
              <span className="chg-sym">{c.sym}</span> {c.text}
            </span>
          ))}
        </div>
      )}

      {svc.length > 0 && (
        <div className="diff-svc">
          {svc.map((s) => (
            <div key={s.backend} className="diff-svc-row">
              <span className="diff-svc-label">backend service version</span>
              <code>{s.backend}</code>
              <span className="svc-from">{s.fromVersion}</span>
              <span className="diff-arrow">→</span>
              <span className="svc-to">{s.toVersion}</span>
            </div>
          ))}
        </div>
      )}

      {d.routeDiffs?.length > 0 && (
        <>
          <button type="button" className="rdiff-toggle" aria-expanded={open} onClick={onToggle}>
            <span className="collapse-caret">{open ? '▾' : '▸'}</span>
            <span className="rdiff-toggle-title">What changed ({d.routeDiffs.length} route{d.routeDiffs.length > 1 ? 's' : ''})</span>
            <span className="muted">element-level diff</span>
          </button>
          {open && d.routeDiffs.map((rd) => <RouteDiffBlock key={rd.routeBase} d={rd} />)}
        </>
      )}

      {d.payloadChange && (d.payloadChange.addedKeys.length > 0 || d.payloadChange.removedKeys.length > 0) && (
        <div className="diff-payload" title="JSON keys added/removed in the request-body template (.ftl/.vm) — serviceVersionNumber excluded">
          <span className="diff-payload-label">Payload change</span>
          {d.payloadChange.addedKeys.map((k) => <span key={'+' + k} className="pk add">+ {k}</span>)}
          {d.payloadChange.removedKeys.map((k) => <span key={'-' + k} className="pk del">− {k}</span>)}
          {d.payloadChange.removedKeys.length > 0 ? (
            <div className="bc-flag warn" title="A request field was removed/renamed — older clients may still send it, so the backend must stay backward compatible">
              ⚠ Backward compatibility required — {d.payloadChange.removedKeys.length} field{d.payloadChange.removedKeys.length === 1 ? '' : 's'} removed
            </div>
          ) : (
            <div className="bc-flag ok" title="Only new fields were added — existing clients are unaffected">
              ✓ Backward compatible — fields added only
            </div>
          )}
        </div>
      )}

      <CodeChangeBlock d={d} onOpenApi={onOpenApi} routeLog={routeLog} />

      {editingRemark ? (
        <div className="remark-edit">
          <span className="remark-lbl">📝 Remark</span>
          <input className="remark-input" value={remarkDraft} autoFocus
                 placeholder="e.g. Not tested — data issue · log-line changed, no retest needed · waived"
                 onChange={(e) => setRemarkDraft(e.target.value)}
                 onKeyDown={(e) => {
                   if (e.key === 'Enter') { onRemark?.(remarkDraft.trim()); setEditingRemark(false); }
                   if (e.key === 'Escape') { setRemarkDraft(remark ?? ''); setEditingRemark(false); }
                 }} />
          <button className="linkbtn" onClick={() => { onRemark?.(remarkDraft.trim()); setEditingRemark(false); }}>Save</button>
          {remark && <button className="linkbtn" onClick={() => { onRemark?.(''); setRemarkDraft(''); setEditingRemark(false); }}>Remove</button>}
        </div>
      ) : remark ? (
        <div className="remark-note" onClick={() => { setRemarkDraft(remark); setEditingRemark(true); }} title="Click to edit this remark">
          <span className="remark-lbl">📝 Remark</span> {remark} <span className="remark-edit-hint">✎</span>
        </div>
      ) : null}

      <div className="diff-actions">
        {onRemark && !editingRemark && !remark && (
          <button className="linkbtn" onClick={() => { setRemarkDraft(''); setEditingRemark(true); }}>＋ Remark</button>
        )}
        <button className="linkbtn" onClick={onViewFlow}>View flow ▸</button>
        <button className="linkbtn" onClick={onCopy}>{copied ? 'Copied ✓' : 'Copy'}</button>
      </div>
    </div>
  );
}

const ALL_STATUSES: DiffStatus[] = ['CHANGED', 'NEW', 'UNCHANGED'];
const GROUP_LABEL: Record<DiffStatus, string> = { CHANGED: 'Changed', NEW: 'New', UNCHANGED: 'Unchanged' };

export default function ReleaseDiffView({ app, colorMode = 'light', viewMode = 'detailed' }: { app?: string; colorMode?: 'light' | 'dark'; viewMode?: 'summary' | 'detailed' }) {
  const { modules, setModules, fromConfig, hasConfig, hasLocal, resetToConfig, saveAsDefault, saving } = useAppModules(app || 'Mighty');
  const [modulesOpen, setModulesOpen] = useState(true);
  const [country, setCountry] = useState(() => localStorage.getItem(appKey(app, 'country')) ?? '');
  const [version, setVersion] = useState('N/A');   // mandatory; N/A = latest per API, else base
  // Optional app/commit version (e.g. 19.18.0) for Java code-change detection — the version token in commits.
  const [appVersion, setAppVersion] = useState(() => localStorage.getItem(appKey(app, 'appVersion')) ?? '');
  const [deps] = useState<DepSource[]>(() => loadDeps(appKey(app, 'deps')));
  const anyValid = modules.some(moduleValid);
  const [reports, setReports] = useState<ModuleResult<VersionDiffReport>[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [report, setReport] = useState<VersionDiffReport | null>(null);
  const names = useMemo(() => Object.fromEntries(reports.map((r) => [r.module.id, r.name])), [reports]);
  const activeModule = modules.find((m) => m.id === activeId) || modules[0];
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One group is shown at a time, picked from the left-hand nav. Defaults to Changed.
  const [activeGroup, setActiveGroup] = useState<DiffStatus>('CHANGED');
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [flowApi, setFlowApi] = useState<{ api: string; version?: string } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  // Optional test-log correlation, per version: version → module id → (api path → log result). Per-version so a
  // shared-code re-test at an OLDER version (e.g. 9.8) is verified against a 9.8 transaction in the log.
  const [logByVer, setLogByVer] = useState<Record<string, Record<string, Record<string, ApiLogResult>>>>({});
  const [logInfo, setLogInfo] = useState<string | null>(null);
  const [logBusy, setLogBusy] = useState(false);
  const hasLog = Object.keys(logByVer).length > 0;
  // A row's tested result, looked up by the version it resolves to (main card = its targetVersion; re-test route = its own version).
  const testedFor = (api?: string | null, version?: string | null): ApiLogResult | undefined =>
    api ? logByVer[normVer(version)]?.[activeId ?? '']?.[api] : undefined;
  const activeLog = logByVer[normVer(version)]?.[activeId ?? ''];   // compared-version map (for the readiness tally)
  // Quick filters for the checklist (AND-combined).
  const [filters, setFilters] = useState<Set<string>>(new Set());
  const toggleFilter = (k: string) => setFilters((prev) => { const n = new Set(prev); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  // Manual per-API remarks (e.g. "Not tested — data issue / log-line change / no retest needed"), persisted locally.
  const [remarks, setRemarks] = useState<Record<string, string>>(() => {
    try { return JSON.parse(localStorage.getItem(appKey(app, 'diffRemarks')) || '{}'); } catch { return {}; }
  });
  const remarkKey = (d: ApiDiff) => `${activeId ?? ''}|${d.api}|${d.operation}`;
  const setRemark = (key: string, text: string) => setRemarks((prev) => {
    const next = { ...prev };
    if (text) next[key] = text; else delete next[key];
    try { localStorage.setItem(appKey(app, 'diffRemarks'), JSON.stringify(next)); } catch { /* storage full/blocked — keep in memory */ }
    return next;
  });

  const show = (rep: VersionDiffReport | null) => {
    setReport(rep); setExpanded(new Set()); setQuery('');
    setActiveGroup(rep && rep.changedCount > 0 ? 'CHANGED'
      : rep && rep.newCount > 0 ? 'NEW'
        : rep && rep.unchangedCount > 0 ? 'UNCHANGED' : 'CHANGED');
  };

  const load = async () => {
    localStorage.setItem(appKey(app, 'country'), country);
    localStorage.setItem(appKey(app, 'appVersion'), appVersion);
    saveDeps(appKey(app, 'deps'), deps);
    setLoading(true); setError(null);
    try {
      const results = await analyzeModules(
        modules.filter(moduleValid),
        (m) => { const sp = sourceParams(m); return fetchVersionDiff(sp.sourceDir, country, version, sp.repo, sp.branch, depParams(deps), app, appVersion.trim() || undefined); },
        (r) => r.moduleName,
      );
      setReports(results);
      setLogByVer({}); setLogInfo(null);   // a fresh comparison invalidates any merged test log
      const first = results.find((r) => r.result) || results[0];
      setActiveId(first?.module.id ?? null);
      show(first?.result ?? null);
      if (results.length > 1) setModulesOpen(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  // Correlate an uploaded test/Splunk log against the compared modules (reuses the log-analysis endpoint),
  // then merge each API's executed/passed status into the checklist by API path.
  const onLogUpload = async (files: FileList | null) => {
    const valid = modules.filter(moduleValid);
    if (!files || !files.length || !valid.length) return;
    setLogBusy(true); setLogInfo(null);
    try {
      // Every version we need to prove: the compared version, each API's resolved version, and every
      // impacted re-test route's version (BAU/Future). The log is correlated once per distinct version.
      const versions = new Set<string>([normVer(version)]);
      reports.forEach((r) => (r.result?.apis ?? []).forEach((a) => {
        versions.add(normVer(a.targetVersion));
        (a.impactedRoutes ?? []).forEach((ir) => versions.add(routeVersion(ir)));
      }));
      const specs = valid.map((m) => { const sp = sourceParams(m); return { name: names[m.id] || m.id, sourceDir: sp.sourceDir, repo: sp.repo, branch: sp.branch, app }; });
      const fileArr = Array.from(files);
      // Correlate every version in parallel (overlaps the uploads) instead of one-after-another.
      const perVer = await Promise.all([...versions].map((v) =>
        analyzeLogMulti(fileArr, specs, { version: v === 'BASE' ? undefined : v, country, dep: depParams(deps) })
          .then((res) => ({ v, res }))));
      const next: Record<string, Record<string, Record<string, ApiLogResult>>> = {};
      for (const { v, res } of perVer) {
        const byMod: Record<string, Record<string, ApiLogResult>> = {};
        valid.forEach((m, i) => {
          const byApi: Record<string, ApiLogResult> = {};
          (res[i]?.report?.apis ?? []).forEach((a) => { byApi[a.api] = a; });
          byMod[m.id] = byApi;
        });
        next[v] = byMod;
      }
      setLogByVer(next);
      const passed = Object.values(next).reduce((n, byMod) =>
        n + Object.values(byMod).reduce((k, mm) => k + Object.values(mm).filter((a) => a.tested).length, 0), 0);
      setLogInfo(`Merged test results across ${versions.size} version(s) — ${passed} executed API result(s)`);
    } catch (e) {
      setLogInfo(e instanceof Error ? e.message : String(e));
    } finally {
      setLogBusy(false);
    }
  };

  const selectModule = (id: string) => { setActiveId(id); show(reports.find((r) => r.module.id === id)?.result ?? null); };
  const impactRollup = useMemo<ModuleStat[]>(() => {
    if (reports.length <= 1) return [];
    const reps = reports.map((r) => r.result).filter((x): x is VersionDiffReport => !!x);
    return [
      { label: 'modules', value: reports.length, tone: 'muted' },
      { label: 'changed', value: reps.reduce((n, r) => n + r.changedCount, 0), tone: 'warn' },
      { label: 'new', value: reps.reduce((n, r) => n + r.newCount, 0), tone: 'good' },
      { label: 'unchanged', value: reps.reduce((n, r) => n + r.unchangedCount, 0), tone: 'muted' },
    ];
  }, [reports]);
  const statsOf = (r: ModuleResult<VersionDiffReport>): ModuleStat[] => {
    const rep = r.result;
    if (!rep) return [];
    if (rep.snapshot) return [{ label: 'latest', value: rep.snapshotCount ?? rep.apis.length, tone: 'info' }];
    return [
      { label: 'changed', value: rep.changedCount, tone: 'warn' },
      { label: 'new', value: rep.newCount, tone: 'good' },
      { label: 'unchanged', value: rep.unchangedCount, tone: 'muted' },
    ];
  };

  const counts: Record<DiffStatus, number> = {
    CHANGED: report?.changedCount ?? 0,
    NEW: report?.newCount ?? 0,
    UNCHANGED: report?.unchangedCount ?? 0,
  };
  const visible = useMemo(() => {
    if (!report) return [];
    const q = query.trim().toLowerCase();
    return report.apis
      .filter((a) => effectiveStatus(a) === activeGroup)
      .filter((a) => !q || searchHaystack(a).includes(q))
      .filter((a) => {
        if (filters.has('high') && riskOf(a) !== 'High') return false;
        if (filters.has('code') && !a.codeChanged) return false;
        if (filters.has('bc') && !needsBC(a)) return false;
        if (filters.has('failed')) { const l = activeLog?.[a.api]; if (!(l?.tested && l.status !== 'SUCCESS')) return false; }
        return true;
      })
      // Highest test-priority first, so the list reads as a prioritised checklist; stable within a risk band.
      .slice().sort((a, b) => RISK_RANK[riskOf(a)] - RISK_RANK[riskOf(b)]);
  }, [report, activeGroup, query, filters, activeLog]);

  // N/A snapshot: every API resolved to its latest/base route — a flat list, not the diff nav.
  const snapshotVisible = useMemo(() => {
    if (!report?.snapshot) return [];
    const q = query.trim().toLowerCase();
    return report.apis.filter((a) => !q || searchHaystack(a).includes(q));
  }, [report, query]);

  const expandableKeys = useMemo(
    () => visible.filter((d) => d.routeDiffs?.length > 0).map(cardKey), [visible]);
  const allOpen = expandableKeys.length > 0 && expandableKeys.every((k) => expanded.has(k));

  const toggleOne = (k: string) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(k)) next.delete(k); else next.add(k);
    return next;
  });
  const toggleAll = () => setExpanded(allOpen ? new Set() : new Set(expandableKeys));

  const copyOne = (d: ApiDiff) => {
    const rmk = remarks[remarkKey(d)];
    const text = apiDiffText(d) + (rmk ? `\n    * remark: ${rmk}` : '');
    const done = () => {
      setCopiedKey(cardKey(d));
      window.setTimeout(() => setCopiedKey((k) => (k === cardKey(d) ? null : k)), 1400);
    };
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
        done();
      } catch { /* clipboard unavailable — silently ignore */ }
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(fallback);
    } else {
      fallback();
    }
  };

  // One report covering every module (each module's Changed + New + snapshot), independent of which
  // module/group is on screen. Shared by both exports so Summary & Detailed cover the same data.
  const buildMods = () => reports.map((r) => {
    // Reshape the per-version log into this module's (version -> api -> result) for the PDF.
    const byVer: Record<string, Record<string, ApiLogResult>> = {};
    for (const [v, byMod] of Object.entries(logByVer)) { const mm = byMod[r.module.id]; if (mm) byVer[v] = mm; }
    // This module's remarks, keyed by `api|operation` (strip the module-id prefix from the stored key).
    const rem: Record<string, string> = {};
    const pfx = r.module.id + '|';
    for (const [k, v] of Object.entries(remarks)) { if (k.startsWith(pfx)) rem[k.slice(pfx.length)] = v; }
    return { name: r.name, report: r.result, error: r.error,
      logByVer: Object.keys(byVer).length ? byVer : undefined,
      remarks: Object.keys(rem).length ? rem : undefined };
  }).filter((m) => m.report || m.error);

  /** Detailed PDF — full route/class/test report for developers & testers. */
  const exportPdf = () => { const mods = buildMods(); if (mods.length) exportDiffPdf(mods, app).catch(() => {}); };
  /** Summary PDF — 1–2 page RAG overview for release managers & delivery leads. */
  const exportSummaryPdf = () => { const mods = buildMods(); if (mods.length) exportDiffSummaryPdf(mods, app).catch(() => {}); };

  return (
    <div className="impact">
      <div className="scope-controls">
        <ModulesEditor modules={modules} onChange={setModules} names={names}
                       open={modulesOpen} onToggleOpen={() => setModulesOpen((o) => !o)}
                       fromConfig={fromConfig} hasConfig={hasConfig} hasLocal={hasLocal}
                       onReset={resetToConfig} onSaveDefault={saveAsDefault} saving={saving} />
        <div className="context-bar">
          <div style={{ width: 160 }}>
            <label>Country <span style={{ color: '#dc2626' }}>*</span></label>
            <input value={country} placeholder="SG / MY / ID / TH / VN" onChange={(e) => setCountry(e.target.value)} />
          </div>
          <div style={{ width: 200 }}>
            <label>API Version <span style={{ color: '#dc2626' }}>*</span></label>
            <input list="diffVersionList" value={version} placeholder="9.18 or N/A (latest / base)" onChange={(e) => setVersion(e.target.value)}
                   onKeyDown={(e) => { if (e.key === 'Enter' && country.trim() && anyValid && version.trim()) load(); }} />
            <datalist id="diffVersionList">
              <option value="N/A" label="latest version of each API (or its default)" />
            </datalist>
          </div>
          <div style={{ width: 200 }}>
            <label title="The exact version token(s) in your commit messages, e.g. 19.18.0. Matched literally (19.10 ≠ 19.10.0). List several, comma-separated, to catch every Jira that touched shared code — e.g. 19.10, 19.10.1, 19.18.0.">Commit/App version(s) <span className="muted" style={{ fontWeight: 400 }}>(optional)</span></label>
            <input value={appVersion} placeholder="19.10, 19.10.1, 19.18.0" onChange={(e) => setAppVersion(e.target.value)}
                   onKeyDown={(e) => { if (e.key === 'Enter' && country.trim() && anyValid && version.trim()) load(); }} />
          </div>
          <button className="trace" style={{ width: 150, marginTop: 0, alignSelf: 'flex-end' }}
                  disabled={loading || !country.trim() || !anyValid || !version.trim()} onClick={load}
                  title={!anyValid ? 'Add at least one module source' : !country.trim() ? 'Enter a country first' : !version.trim() ? 'Enter a client release version (or N/A)' : ''}>
            {loading ? 'Comparing…' : 'Compare modules'}
          </button>
        </div>
      </div>
      {reports.length > 1 && (
        <div style={{ padding: '0 18px' }}>
          <ModuleSummary results={reports} activeId={activeId} onSelect={selectModule}
                         statsOf={statsOf} unversionedOf={(r) => !!r.result?.snapshot}
                         rollup={impactRollup}
                         onExport={exportPdf}
                         exportTitle="One PDF covering every module — changed + new APIs to test" />
        </div>
      )}


      {error && <div className="err" style={{ padding: '0 18px' }}>Error: {error}</div>}

      {loading && <div className="impact-loading"><Loader messages={DIFF_MESSAGES} note="comparing versions" /></div>}

      {!loading && !report && !error && (
        <div className="impact-empty">
          <div className="impact-empty-title">Compare a release against the one before it</div>
          <div className="sub">Enter a <b>release version</b> (e.g. <b>9.18</b>) and click <b>Compare</b>. For every API this release touched, TraceGuard shows what changed versus the previous release — what was added, removed or modified. Use <b>N/A</b> to see each API&rsquo;s latest version instead of comparing.</div>
        </div>
      )}

      {!loading && report && viewMode === 'summary' && (
        <div className="impact-body" style={{ padding: '0 18px 20px' }}>
          <div className="row between" style={{ margin: '4px 0 6px', flexWrap: 'wrap', gap: 8 }}>
            <h2 style={{ margin: 0 }}>Release {report.version || 'N/A'}{report.country ? ` · ${report.country}` : ''}</h2>
            <span className="row" style={{ gap: 6 }}>
              <button className="minibtn" onClick={exportSummaryPdf} title="1–2 page overview for release managers & delivery leads">⤓ Summary PDF</button>
              <button className="minibtn" onClick={exportPdf} title="Full route/class/test report for developers & testers">⤓ Detailed PDF</button>
            </span>
          </div>
          <CodeChangeSummary report={report} />
          <div className="testlog-bar" style={{ marginTop: 10 }}>
            <label className={'testlog-btn' + (logBusy ? ' busy' : '')} title="Upload a Splunk export / output log to see which APIs were executed and passed">
              {logBusy ? <><span className="mini-spin" aria-hidden="true" /> Correlating test log…</> : '⤒ Attach test log'}
              <input type="file" multiple accept=".log,.txt,.csv,.json,.gz" style={{ display: 'none' }}
                     disabled={logBusy} onChange={(e) => { onLogUpload(e.target.files); e.currentTarget.value = ''; }} />
            </label>
            {!logBusy && logInfo && <span className="testlog-info">{logInfo}</span>}
            {hasLog && <button className="linkbtn" onClick={() => { setLogByVer({}); setLogInfo(null); }}>Clear</button>}
          </div>
          <ImpactSummary report={report} log={activeLog} />
        </div>
      )}

      {!loading && report && report.snapshot && viewMode === 'detailed' && (
        <div className="impact-body diff-layout">
          <div className="diff-nav">
            <div className="diff-nav-head">Release {report.version || 'N/A'}{report.country ? ` · ${report.country}` : ''}</div>
            <div className="diff-nav-item active">
              <span className="diff-nav-label">Latest routes</span>
              <span className="diff-nav-count">{report.snapshotCount ?? report.apis.length}</span>
            </div>
            {report.apis.length > 0 && reports.length <= 1 && (
              <button className="minibtn diff-nav-export" onClick={exportPdf} title="Download the latest-routes snapshot as a PDF">⤓ Export PDF</button>
            )}
          </div>
          <div className="diff-main">
            <NeedsReviewBox items={report.needsReview ?? []} />
            {(() => {
              const review = new Set(report.needsReview ?? []);
              const other = report.warnings.filter((w) => !review.has(w));
              return other.length > 0 ? (
                <div className="warnbox">{other.map((w, i) => <div key={i}>⚠ {w}</div>)}</div>
              ) : null;
            })()}
            <InfoBanner>Showing each API at its latest version{report.country ? ` for ${report.country}` : ''} (or its default when it has no versions). This is a current snapshot for review — there is no earlier release to compare it against.</InfoBanner>
            <CodeChangeSummary report={report} />
            <div className="diff-main-head row between">
              <h2 style={{ margin: 0 }}>Latest routes <span className="muted">{snapshotVisible.length}</span></h2>
              <input className="diff-search" placeholder="🔍 filter by path, route or operation"
                     value={query} onChange={(e) => setQuery(e.target.value)} />
            </div>
            {snapshotVisible.length === 0 ? (
              <div className="impact-empty">
                <div className="impact-empty-title">{report.apis.length === 0 ? 'No APIs in scope' : 'No matches'}</div>
                <div className="sub">{report.apis.length === 0 ? 'No APIs found in this scope.' : `Nothing matches “${query.trim()}”.`}</div>
              </div>
            ) : (
              <div className="diff-list">
                {snapshotVisible.map((a) => (
                  <div className={'diff-card snapshot' + (a.codeChanged ? ' code' : '')} key={a.api + '|' + a.operation}>
                    <div className="diff-card-head row between">
                      <div className="diff-card-id"><code>{a.api}</code><span className="muted op">{a.operation}</span></div>
                      <span className="row" style={{ gap: 6 }}>
                        {a.codeChanged && report.appVersion && (
                          <span className="diff-badge code" title="A shared Java class in this API's flow was changed by the app-version release">changed in {(a.changedVersions && a.changedVersions.length ? a.changedVersions.join(', ') : report.appVersion)}</span>
                        )}
                        <span className="diff-badge" title="the version this API is currently on">
                          {a.targetVersion === 'BASE' ? 'Base' : 'Release ' + a.targetVersion}
                        </span>
                      </span>
                    </div>
                    <div className="diff-verdict">
                      <span className="tag route">{a.targetRoute}</span>
                    </div>
                    <CodeChangeBlock d={a} />
                    <div className="diff-actions">
                      <button className="linkbtn" onClick={() => setFlowApi({ api: a.api, version: report.version || undefined })}>View flow ▸</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {!loading && report && !report.snapshot && viewMode === 'detailed' && (
        <div className="impact-body diff-layout">
          <div className="diff-nav">
            <div className="diff-nav-head">Release {report.version || 'BASE'}{report.country ? ` · ${report.country}` : ''}</div>
            {ALL_STATUSES.map((s) => (
              <button key={s} className={'diff-nav-item ' + s.toLowerCase() + (activeGroup === s ? ' active' : '')}
                      aria-pressed={activeGroup === s} onClick={() => setActiveGroup(s)}>
                <span className="diff-nav-label">{GROUP_LABEL[s]}</span>
                <span className="diff-nav-count">{counts[s]}</span>
              </button>
            ))}
            {report.apis.length > 0 && reports.length <= 1 && (
              <button className="minibtn diff-nav-export" onClick={exportPdf} title="Download the full report as a PDF">⤓ Export PDF</button>
            )}
          </div>

          <div className="diff-main">
            <NeedsReviewBox items={report.needsReview ?? []} />
            {(() => {
              // The needs-review items are shown in their own highlighted box above; keep them out of
              // the plain warning banner so the two sections don't repeat the same lines.
              const review = new Set(report.needsReview ?? []);
              const other = report.warnings.filter((w) => !review.has(w));
              return other.length > 0 ? (
                <div className="warnbox">{other.map((w, i) => <div key={i}>⚠ {w}</div>)}</div>
              ) : null;
            })()}

            <ReadinessStrip report={report} log={activeLog} />
            <div className="testlog-bar">
              <label className={'testlog-btn' + (logBusy ? ' busy' : '')} title="Upload a Splunk export / output log — TraceGuard correlates it and shows which of these APIs were executed and passed">
                {logBusy
                  ? <><span className="mini-spin" aria-hidden="true" /> Correlating test log…</>
                  : '⤒ Attach test log'}
                <input type="file" multiple accept=".log,.txt,.csv,.json,.gz" style={{ display: 'none' }}
                       disabled={logBusy} onChange={(e) => { onLogUpload(e.target.files); e.currentTarget.value = ''; }} />
              </label>
              {logBusy && <span className="testlog-info">matching your log against the impacted APIs across versions…</span>}
              {!logBusy && logInfo && <span className="testlog-info">{logInfo}</span>}
              {hasLog && (
                <button className="linkbtn" onClick={() => { setLogByVer({}); setLogInfo(null); }}>Clear</button>
              )}
            </div>
            <CodeChangeSummary report={report} />

            <div className="diff-main-head row between">
              <h2 style={{ margin: 0 }}>{GROUP_LABEL[activeGroup]} APIs <span className="muted">{visible.length}</span></h2>
              <span className="row" style={{ gap: 8 }}>
                <input className="diff-search" placeholder="🔍 filter by path, route or backend"
                       value={query} onChange={(e) => setQuery(e.target.value)} />
                {expandableKeys.length > 0 && (
                  <button className="linkbtn" onClick={toggleAll}>{allOpen ? 'Collapse all' : 'Expand all'}</button>
                )}
              </span>
            </div>
            <div className="filter-chips">
              <button className={'fchip' + (filters.has('high') ? ' on' : '')} onClick={() => toggleFilter('high')}>High risk</button>
              {report.appVersion && <button className={'fchip' + (filters.has('code') ? ' on' : '')} onClick={() => toggleFilter('code')}>Code-changed</button>}
              <button className={'fchip' + (filters.has('bc') ? ' on' : '')} onClick={() => toggleFilter('bc')}>Backward-compat</button>
              {activeLog && <button className={'fchip' + (filters.has('failed') ? ' on' : '')} onClick={() => toggleFilter('failed')}>Test failed</button>}
              {filters.size > 0 && <button className="linkbtn" onClick={() => setFilters(new Set())}>Clear filters</button>}
            </div>

            {visible.length === 0 ? (
              <div className="impact-empty">
                <div className="impact-empty-title">
                  {report.apis.length === 0 ? 'Nothing to compare'
                    : query.trim() ? 'No matches'
                      : `No ${GROUP_LABEL[activeGroup].toLowerCase()} APIs`}
                </div>
                <div className="sub">
                  {report.apis.length === 0
                    ? 'No APIs found for this version in the selected scope.'
                    : query.trim()
                      ? <>Nothing matches “{query.trim()}” in this group. Clear the search or pick another group.</>
                      : `This release has no ${GROUP_LABEL[activeGroup].toLowerCase()} APIs.`}
                </div>
              </div>
            ) : (
              <div className="diff-list">
                {visible.map((d) => (
                  <ApiDiffCard key={cardKey(d)} d={d} log={testedFor(d.api, d.targetVersion)}
                               routeLog={hasLog ? (ir) => testedFor(ir.api, routeVersion(ir)) : undefined}
                               remark={remarks[remarkKey(d)]} onRemark={(t) => setRemark(remarkKey(d), t)}
                               open={expanded.has(cardKey(d))} onToggle={() => toggleOne(cardKey(d))}
                               onViewFlow={() => setFlowApi({ api: d.api, version: d.targetVersion || report.version || undefined })}
                               onOpenApi={(api) => setFlowApi({ api, version: report.version || undefined })}
                               onCopy={() => copyOne(d)} copied={copiedKey === cardKey(d)} />
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {flowApi && activeModule && (
        <ApiFlowModal api={flowApi.api} version={flowApi.version} sourceDir={sourceParams(activeModule).sourceDir}
                      repo={sourceParams(activeModule).repo} branch={sourceParams(activeModule).branch} country={country} app={app}
                      deps={depParams(deps)} colorMode={colorMode} onClose={() => setFlowApi(null)} />
      )}
    </div>
  );
}
