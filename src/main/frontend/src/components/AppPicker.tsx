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

/** The TraceGuard mark: a shield (guard) wrapping a small route graph (trace). */
function Logo() {
  return (
    <svg className="tg-logo" viewBox="0 0 64 64" width="72" height="72" aria-hidden="true">
      <defs>
        <linearGradient id="tgGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#2563eb" />
          <stop offset="1" stopColor="#0891b2" />
        </linearGradient>
      </defs>
      <path d="M32 4 L56 12 V31 C56 46 45 56 32 60 C19 56 8 46 8 31 V12 Z" fill="url(#tgGrad)" />
      <g stroke="#fff" strokeWidth="2.6" strokeLinecap="round" fill="#fff">
        <path d="M22 26 H42 M32 26 V41" fill="none" />
        <circle cx="22" cy="26" r="4.2" />
        <circle cx="42" cy="26" r="4.2" />
        <circle cx="32" cy="41" r="4.2" />
      </g>
    </svg>
  );
}

/** Landing page: choose which application to work with. */
export default function AppPicker({ onPick }: { onPick: (app: string) => void }) {
  return (
    <div className="app-picker">
      <div className="app-picker-inner">
        <Logo />
        <div className="app-picker-head">TraceGuard</div>
        <div className="app-picker-tagline">Trace the flow. Guard the release.</div>
        <div className="app-picker-ai">✨ Powered by AI</div>
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
