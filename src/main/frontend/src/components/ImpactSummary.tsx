import { Fragment, type ReactNode } from 'react';
import { CircleCheck, CircleX, Minus } from 'lucide-react';
import { groupItemsByFeature } from '../feature';
import { humanize, type FeatureLabels } from '../labels';
import type { ApiLogResult, VersionDiffReport } from '../types';
import { buildTestPlan, buckets, logForRow, testedState, type Bucket, type PlanRow, type SharedClass } from '../testPlan';

/**
 * The manager-facing "Summary" of a Release Impact report, laid out as a test plan: four buckets (BAU
 * regression · Backward compatibility · New app only · Shared code), each answering what changed, what to
 * test and who tests it. Same model as the Summary PDF ({@link buildTestPlan}), so screen and paper agree.
 * Derived entirely from the existing report — no backend data is added.
 */
export default function ImpactSummary({ report, log, logByVer, labels = {} }: {
  report: VersionDiffReport;
  /** Per-API results for the compared version (fallback lookup). */
  log?: Record<string, ApiLogResult>;
  /** Per-version results (version → api → result) so a BAU row is verified at the LIVE route's version. */
  logByVer?: Record<string, Record<string, ApiLogResult>>;
  /** Business-area labels (⚙ Config) for the feature headers; a missing label falls back to the path segment. */
  labels?: FeatureLabels;
}) {
  const plan = buildTestPlan(report);
  const hasLog = !!log || !!(logByVer && Object.keys(logByVer).length);
  const [B_BAU, B_BC, B_NEW, B_SHARED] = buckets(plan.version, plan.snapshot);
  const tested = (r: PlanRow) => (hasLog ? <Tested l={logForRow(r, logByVer, log)} /> : null);
  const sharedVersions = new Set(plan.shared.flatMap((s) => s.routes.map((x) => x.version))).size;

  return (
    <div className="tp">
      <p className="tp-verdict">{plan.verdict}</p>

      <div className="tp-tiles">
        <Tile b={B_BAU} n={plan.bau.length} />
        <Tile b={B_BC} n={plan.bc.length} />
        <Tile b={B_NEW} n={plan.newOnly.length} />
        <Tile b={B_SHARED} n={plan.shared.length} sub={plan.shared.length ? `${plan.shared.length} class${plan.shared.length === 1 ? '' : 'es'} · ${sharedVersions} version${sharedVersions === 1 ? '' : 's'}` : undefined} />
      </div>

      {/* BAU regression — every row is High by rule, so no Risk column; Who distinguishes BAU-only from Both. */}
      <Section b={B_BAU} count={plan.bau.length} empty="None — no live route or shared class changed.">
        <table className="tp-table">
          <thead><tr><th>API</th><th>Change</th><th>Test focus</th><th>Who</th>{hasLog && <th>Tested</th>}</tr></thead>
          <tbody>{withFeatures(labels, plan.bau, hasLog ? 5 : 4, (r) => (
            <tr key={r.a.api + '|' + r.a.operation}>
              <ApiCell r={r} />
              <td className="tp-what">{r.what}{r.detail && <small>{r.detail}</small>}</td>
              <td><Focus items={r.test} /></td>
              <td><Who who={r.who} /></td>
              {hasLog && <td>{tested(r)}</td>}
            </tr>
          ))}</tbody>
        </table>
      </Section>

      {/* Backward compatibility — the BAU set seen from the old client's side. */}
      <Section b={B_BC} count={plan.bc.length} empty="None.">
        <table className="tp-table">
          <thead><tr><th>API</th><th>Still called by</th><th>Test focus</th>{hasLog && <th>Tested</th>}</tr></thead>
          <tbody>{withFeatures(labels, plan.bc, hasLog ? 4 : 3, (r) => (
            <tr key={r.a.api + '|' + r.a.operation}>
              <ApiCell r={r} />
              <td className="tp-what">{r.oldVersions.length ? r.oldVersions.map((v) => <code key={v} className="tp-ver">{v}</code>) : 'current app'}</td>
              <td><Focus items={r.test} /></td>
              {hasLog && <td>{tested(r)}</td>}
            </tr>
          ))}</tbody>
        </table>
      </Section>

      {/* New app only — Low/Medium risk, so Risk stays; everyone here is the new-app team. */}
      <Section b={B_NEW} count={plan.newOnly.length} empty="None.">
        <table className="tp-table">
          <thead><tr><th>API</th><th>Change</th><th>Test focus</th><th>Risk</th>{hasLog && <th>Tested</th>}</tr></thead>
          <tbody>{withFeatures(labels, plan.newOnly, hasLog ? 5 : 4, (r) => (
            <tr key={r.a.api + '|' + r.a.operation}>
              <ApiCell r={r} />
              <td className="tp-what">{r.what}{r.detail && <small>{r.detail}</small>}</td>
              <td><Focus items={r.test} /></td>
              <td><span className={'sumv-risk ' + r.risk}><span className="dot" />{r.risk}</span></td>
              {hasLog && <td>{tested(r)}</td>}
            </tr>
          ))}</tbody>
        </table>
      </Section>

      <Section b={B_SHARED} count={plan.shared.length} empty="None — no shared class changed.">
        <table className="tp-table">
          <thead><tr><th>Class</th><th>Changed by</th><th>Versions to test</th><th>APIs</th></tr></thead>
          <tbody>{plan.shared.map((s) => <SharedRow key={s.name} s={s} logByVer={logByVer} hasLog={hasLog} />)}</tbody>
        </table>
      </Section>

      <div className="tp-gloss">
        <h4>What the labels mean</h4>
        <dl>
          {[B_BAU, B_BC, B_NEW, B_SHARED].map((b) => (
            <div key={b.key} className={'tp-g ' + b.key}><dt>{b.title}</dt><dd>{b.define}</dd></div>
          ))}
        </dl>
      </div>

      {plan.unchanged > 0 && (
        <div className="tp-carry">{plan.unchanged} unchanged API{plan.unchanged === 1 ? '' : 's'} — carried forward, no action.</div>
      )}
    </div>
  );
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

function ApiCell({ r }: { r: PlanRow }) {
  return <td className="tp-api"><span className="path">{r.a.api}</span><span className="op">{r.a.operation}{r.a.targetVersion && r.a.lowerVersion ? ` · ${r.a.lowerVersion} → ${r.a.targetVersion}` : ''}</span></td>;
}

function Focus({ items }: { items: string[] }) {
  return <ul className="tp-focus">{items.map((t, i) => <li key={i}>{t}</li>)}</ul>;
}

function Who({ who }: { who: PlanRow['who'] }) {
  const cls = who === 'BAU team' ? 'bau' : who === 'New-app team' ? 'new' : who === 'All clients' ? 'all' : 'both';
  return <span className={'tp-who ' + cls}>{who}</span>;
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

/** Rows grouped by business feature (payment, manage, …), each group led by a header row — like the screen.
 *  Order within a feature is preserved, so the risk sort survives. */
function withFeatures(labels: FeatureLabels, rows: PlanRow[], colSpan: number, render: (r: PlanRow) => ReactNode): ReactNode[] {
  return groupItemsByFeature(rows, (r) => r.a.api).map((fg) => (
    <Fragment key={fg.feature}>
      <tr className="tp-feat-row"><td colSpan={colSpan}><span className="tp-feat-name">{labels[fg.feature.toLowerCase()] || humanize(fg.feature)}</span><span className="tp-feat-cnt">{fg.items.length}</span></td></tr>
      {fg.items.map(render)}
    </Fragment>
  ));
}
