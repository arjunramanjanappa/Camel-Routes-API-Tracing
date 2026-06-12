import type { CSSProperties } from 'react';

interface AppDef {
  id: string;
  name: string;
  markers: string;
  desc: string;
  accent: string;
}

const APPS: AppDef[] = [
  { id: 'Mighty', name: 'Mighty', markers: 'MightyMessage · MightyHostMessage', desc: 'Analyse the Mighty application’s routes and logs.', accent: '#2563eb' },
  { id: 'SPL', name: 'SPL', markers: 'SPLMessage · SPLHostMessage', desc: 'Analyse the SPL application’s routes and logs.', accent: '#0891b2' },
];

/** Landing page: choose which application to work with (sets the log markers). */
export default function AppPicker({ onPick }: { onPick: (app: string) => void }) {
  return (
    <div className="app-picker">
      <div className="app-picker-inner">
        <div className="app-picker-head">Camel Route Tracer</div>
        <div className="app-picker-sub">
          Choose an application. Tracing, impact analysis and the Splunk workflow are identical —
          only the log markers differ.
        </div>
        <div className="app-cards">
          {APPS.map((a) => (
            <button key={a.id} className="app-card" style={{ ['--accent']: a.accent } as CSSProperties} onClick={() => onPick(a.id)}>
              <span className="app-card-badge" style={{ background: a.accent }}>{a.name.charAt(0)}</span>
              <span className="app-card-name">{a.name}</span>
              <span className="app-card-desc">{a.desc}</span>
              <code className="app-card-markers">{a.markers}</code>
              <span className="app-card-cta">Open →</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
