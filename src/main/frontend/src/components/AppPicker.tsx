import type { CSSProperties } from 'react';

interface AppDef {
  id: string;
  name: string;
  desc: string;
  accent: string;
}

const APPS: AppDef[] = [
  { id: 'Mighty', name: 'Mighty', desc: 'Trace, impact-analyse and verify the Mighty application.', accent: '#2563eb' },
  { id: 'SPL', name: 'SPL', desc: 'Trace, impact-analyse and verify the SPL application.', accent: '#0891b2' },
];

/** Landing page: choose which application to work with. */
export default function AppPicker({ onPick }: { onPick: (app: string) => void }) {
  return (
    <div className="app-picker">
      <div className="app-picker-inner">
        <div className="app-picker-head">TraceGuard</div>
        <div className="app-picker-sub">
          Choose an application to work with. Tracing, impact analysis and the Splunk workflow are the same for both.
        </div>
        <div className="app-cards">
          {APPS.map((a) => (
            <button key={a.id} className="app-card" style={{ ['--accent']: a.accent } as CSSProperties} onClick={() => onPick(a.id)}>
              <span className="app-card-badge" style={{ background: a.accent }}>{a.name.charAt(0)}</span>
              <span className="app-card-name">{a.name}</span>
              <span className="app-card-desc">{a.desc}</span>
              <span className="app-card-cta">Open →</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
