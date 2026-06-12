import { useEffect, useState } from 'react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';
import AppPicker from './components/AppPicker';

type View = 'trace' | 'impact';
type Theme = 'light' | 'dark';

export default function App() {
  const [app, setApp] = useState<string | null>(null);
  const [view, setView] = useState<View>('trace');
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('tracer.theme') as Theme) || 'light');

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('tracer.theme', theme);
  }, [theme]);

  if (!app) {
    return <AppPicker onPick={(a) => { setApp(a); localStorage.setItem('tracer.app', a); }} />;
  }

  return (
    <div className="app">
      <header>
        <div className="brand">
          <h1>TraceGuard <span className="app-badge" title="selected application">{app}</span></h1>
          <span>API → Resolved Route → Sub-routes → Backend APIs</span>
        </div>
        <nav className="tabs">
          <button className={view === 'trace' ? 'tab active' : 'tab'} onClick={() => setView('trace')}>Trace</button>
          <button className={view === 'impact' ? 'tab active' : 'tab'} onClick={() => setView('impact')}>Impact analysis</button>
          <button className="tab" title="Switch application" onClick={() => setApp(null)}>⇄ App</button>
          <button className="tab theme-toggle" title="Toggle theme"
                  onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
            {theme === 'light' ? '🌙' : '☀️'}
          </button>
        </nav>
      </header>
      {view === 'trace' ? <TraceView app={app} colorMode={theme} /> : <ImpactView app={app} />}
    </div>
  );
}
