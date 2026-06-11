import { useEffect, useState } from 'react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';

type View = 'trace' | 'impact';
type Theme = 'light' | 'dark';

export default function App() {
  const [view, setView] = useState<View>('trace');
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('tracer.theme') as Theme) || 'light');

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('tracer.theme', theme);
  }, [theme]);

  return (
    <div className="app">
      <header>
        <div className="brand">
          <h1>Camel Route Tracer</h1>
          <span>API → Resolved Route → Sub-routes → Backend APIs</span>
        </div>
        <nav className="tabs">
          <button className={view === 'trace' ? 'tab active' : 'tab'} onClick={() => setView('trace')}>Trace</button>
          <button className={view === 'impact' ? 'tab active' : 'tab'} onClick={() => setView('impact')}>Impact analysis</button>
          <button className="tab theme-toggle" title="Toggle theme"
                  onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
            {theme === 'light' ? '🌙' : '☀️'}
          </button>
        </nav>
      </header>
      {view === 'trace' ? <TraceView colorMode={theme} /> : <ImpactView />}
    </div>
  );
}
