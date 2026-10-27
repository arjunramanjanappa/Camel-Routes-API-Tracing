import { Fragment, type ReactNode } from 'react';
import { CircleCheck, CircleX, Minus } from 'lucide-react';
import type { FeatureLabels } from '../labels';
import type { ApiLogResult, VersionDiffReport } from '../types';
import { buildTestPlan, buckets, logForRow, testedState, appOf, jiraOf, jiraSummary, jiraApp, changedBy,
  type AppTag, type Bucket, type PlanRow, type SharedClass } from '../testPlan';
import { buildAreas, changeTag, type Area } from '../areas';

/**
 * The manager-facing "Summary" of a Release Impact report — the same model and the same order as the Summary
 * PDF: the verdict and the four counts, then each business area (highest impact first) with its sub-features and
 * one row per API: API · Which App · Change · Jira Details · Commit Message. Shared code and the release's Jira
 * tickets close it. Derived entirely from the existing report ({@link buildTestPlan}, {@link buildAreas}).
 */
export default function ImpactSummary({ report, log, logByVer, labels = {} }: {
  report: VersionDiffReport;
  /** Per-API results for the compared version (fallback lookup). */
  log?: Record<string, ApiLogResult>;
  /** Per-version results (version → api → result) so a BAU row is verified at the LIVE route's version. */
  logByVer?: Record<string, Record<string, ApiLogResult>>;
  /** Business-area labels (⚙ Config) for the area and sub-feature headers; a missing label falls back to the path segment. */
  labels?: FeatureLabels;
}) {
  const plan = buildTestPlan(report);
  const hasLog = !!log || !!(logByVer && Object.keys(logByVer).length);
  const [B_BAU, B_BC, B_NEW, B_SHARED] = buckets(plan.version, plan.snapshot);
  const tested = (r: PlanRow) => (hasLog ? <Tested l={logForRow(r, logByVer, log)} /> : null);
  const sharedVersions = new Set(plan.shared.flatMap((s) => s.routes.map((x) => x.version))).size;
  const rows = [...plan.bau, ...plan.newOnly];
  const areas: Area[] = buildAreas(rows, labels);
  const jira = jiraSummary(rows);
  const hasJira = jira.length > 0;

  return (
    <div className="tp">
      <p className="tp-verdict">{plan.verdict}</p>

      <div className="tp-tiles">
        <Tile b={B_BAU} n={plan.bau.length} />
        <Tile b={B_BC} n={plan.bc.length} />
        <Tile b={B_NEW} n={plan.newOnly.length} />
        <Tile b={B_SHARED} n={plan.shared.length} sub={plan.shared.length ? `${plan.shared.length} class${plan.shared.length === 1 ? '' : 'es'} · ${sharedVersions} version${sharedVersions === 1 ? '' : 's'}` : undefined} />
      </div>

      {areas.map((area) => {
        const owners = hasJira ? changedBy(area.rows) : null;
        return (
          <section className="tp-area" key={area.key}>
            <div className={'tp-area-head risk-' + area.risk.toLowerCase()}>
              <span className="k">{area.label}</span>
              <span className="c">{area.rows.length} · {area.risk}</span>
            </div>
            {owners && owners.developers.length > 0 && (
              <p className="tp-owners"><b>Changed by</b> {owners.developers.map((d) => `${d.name} (${d.tickets})`).join(' · ')}
                <span className="tp-owners-tickets">{owners.tickets.map((t) => <code key={t} className="tp-jira">{t}</code>)}</span></p>
            )}
            {area.subs.map((sub) => (
              <Fragment key={sub.key}>
                <div className="tp-sub">{sub.label} <span className="c">{sub.rows.length}</span></div>
                <RowsTable rows={sub.rows} hasLog={hasLog} hasJira={hasJira} tested={tested} />
              </Fragment>
            ))}
            {area.direct.length > 0 && (
              <>
                {area.subs.length > 0 && <div className="tp-sub">Other <span className="c">{area.direct.length}</span></div>}
                <RowsTable rows={area.direct} hasLog={hasLog} hasJira={hasJira} tested={tested} />
              </>
            )}
          </section>
        );
      })}

      <Section b={B_SHARED} count={plan.shared.length} empty="None — no shared class changed.">
        <table className="tp-table">
          <thead><tr><th>Class</th><th>Changed by</th><th>Versions to test</th><th>APIs</th></tr></thead>
          <tbody>{plan.shared.map((s) => <SharedRow key={s.name} s={s} logByVer={logByVer} hasLog={hasLog} />)}</tbody>
        </table>
      </Section>

      {hasJira && (
        <section className="tp-area">
          <div className="tp-area-head jira"><span className="k">Jira in this release</span><span className="c">{jira.length}</span></div>
          <div className="tp-tablewrap">
            <table className="tp-table">
              <thead><tr><th>Jira Details</th><th>Which App</th><th>Commit Message</th><th>APIs</th></tr></thead>
              <tbody>
                {jira.map((j) => (
                  <tr key={j.ticket || 'none'}>
                    <td className="tp-jcell"><code className="tp-jira">{j.ticket || 'no Jira'}</code>{j.developers.length > 0 && <> · <b>{j.developers.join(', ')}</b></>}</td>
                    <td><App app={jiraApp(j)} /></td>
                    <td className="tp-msg">{j.messages.map((m, i) => <span key={i}>{m}</span>)}</td>
                    <td className="tp-num">{j.apis.length}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {plan.unchanged > 0 && (
        <div className="tp-carry">{plan.unchanged} unchanged API{plan.unchanged === 1 ? '' : 's'} — carried forward, no action.</div>
      )}
    </div>
  );
}

/** API · Which App · Change · Jira Details · Commit Message (· Tested). Each Jira ticket is its own sub-row. */
function RowsTable({ rows, hasLog, hasJira, tested }: { rows: PlanRow[]; hasLog: boolean; hasJira: boolean; tested: (r: PlanRow) => ReactNode }) {
  return (
    <div className="tp-tablewrap">
      <table className="tp-table tp-rows">
        <thead><tr><th>API</th><th>Which App</th><th>Change</th>{hasJira && <><th>Jira Details</th><th>Commit Message</th></>}{hasLog && <th>Tested</th>}</tr></thead>
        <tbody>
          {rows.map((r) => {
            const refs = hasJira ? jiraOf(r.a) : [];
            const span = Math.max(1, refs.length);
            const key = r.a.api + '|' + r.a.operation;
            return (
              <Fragment key={key}>
                <tr className={span > 1 ? 'tp-first' : 'tp-last'}>
                  <td className="tp-api" rowSpan={span}><span className="path">{r.a.api}</span><span className="op">{r.a.operation}{r.a.targetVersion && r.a.lowerVersion ? ` · ${r.a.lowerVersion} → ${r.a.targetVersion}` : ''}</span></td>
                  <td rowSpan={span}><App app={appOf(r)} /></td>
                  <td className="tp-what" rowSpan={span}>{changeTag(r)}</td>
                  {hasJira && (refs.length
                    ? <><td className="tp-jcell"><code className="tp-jira">{refs[0].ticket || 'no Jira'}</code>{refs[0].developers.length > 0 && <> · <b>{refs[0].developers.join(', ')}</b></>}</td><td className="tp-msg">{refs[0].messages.map((m, i) => <span key={i}>{m}</span>)}</td></>
                    : <><td className="tp-jcell muted">—</td><td className="tp-msg muted">—</td></>)}
                  {hasLog && <td rowSpan={span}>{tested(r)}</td>}
                </tr>
                {refs.slice(1).map((j, i) => (
                  <tr key={key + '|' + i} className={i === refs.length - 2 ? 'tp-last' : 'tp-cont'}>
                    <td className="tp-jcell"><code className="tp-jira">{j.ticket || 'no Jira'}</code>{j.developers.length > 0 && <> · <b>{j.developers.join(', ')}</b></>}</td>
                    <td className="tp-msg">{j.messages.map((m, k) => <span key={k}>{m}</span>)}</td>
                  </tr>
                ))}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function App({ app }: { app: AppTag }) {
  const cls = app === 'BAU app' ? 'bau' : app === 'New app' ? 'new' : app === 'All clients' ? 'all' : 'both';
  return <span className={'tp-app ' + cls}>{app}</span>;
}

function Tile({ b, n, sub }: { b: Bucket; n: number; sub?: string }) {
  return (
    <div className={'tp-tile ' + b.key}>
      <div className="n">{n}</div>
      <div className="t">{b.title}</div>
      <div className="d">{sub ?? b.question}</div>
      <div className="w">{b.who}</div>
    </div>
  );
}

function Section({ b, count, empty, children }: { b: Bucket; count: number; empty: string; children: ReactNode }) {
  return (
    <section className={'tp-sec ' + b.key}>
      <div className="tp-sec-head">
        <span className="k">{b.title}<span className="c">{count}</span></span>
        <span className="q">{b.question}</span>
        <span className="w">{b.who}</span>
      </div>
      {count === 0 ? <div className="tp-empty">{empty}</div> : <div className="tp-tablewrap">{children}</div>}
    </section>
  );
}

function Tested({ l }: { l?: ApiLogResult }) {
  const t = testedState(l);
  const icon = t.state === 'pass' ? <CircleCheck size={13} aria-hidden="true" /> : t.state === 'fail' || t.state === 'partial' ? <CircleX size={13} aria-hidden="true" /> : <Minus size={13} aria-hidden="true" />;
  const cls = t.state === 'pass' ? 'pass' : t.state === 'fail' || t.state === 'partial' ? 'fail' : 'none';
  return <span className={'sumv-tst ' + cls}>{icon} {t.label}</span>;
}

const GROUP_CLS: Record<string, string> = { 'This release': 'cur', 'Live now': 'live', Upcoming: 'fut', Untraced: 'unk' };

function SharedRow({ s, logByVer, hasLog }: { s: SharedClass; logByVer?: Record<string, Record<string, ApiLogResult>>; hasLog: boolean }) {
  const shown = s.routes.slice(0, 6);
  return (
    <tr>
      <td className="tp-api"><span className="path">{s.name}{s.removed ? ' — removed' : ''}</span><span className="op">{s.file}{s.versions.length ? ' · ' + s.versions.join(', ') : ''}</span></td>
      <td className="tp-what">{s.authors || '—'}</td>
      <td>
        <ul className="tp-routes">
          {shown.map((x) => {
            const l = hasLog && x.api ? logByVer?.[x.version === 'BASE' ? 'BASE' : x.version]?.[x.api] : undefined;
            const t = hasLog && x.api ? testedState(l) : null;
            return (
              <li key={x.route}>
                <span className={'tp-grp ' + GROUP_CLS[x.group]}>{x.group}</span>
                <code>{x.route}</code>
                {x.api && <span className="tp-rapi">{x.api}</span>}
                {t && <span className={'tp-mini ' + t.state}>{t.label}</span>}
              </li>
            );
          })}
          {s.routes.length > shown.length && <li className="tp-more">+{s.routes.length - shown.length} more</li>}
        </ul>
      </td>
      <td className="tp-what">{s.apis.slice(0, 3).map((p) => <code key={p} className="tp-ver">{p}</code>)}{s.apis.length > 3 ? ` +${s.apis.length - 3}` : ''}</td>
    </tr>
  );
}
