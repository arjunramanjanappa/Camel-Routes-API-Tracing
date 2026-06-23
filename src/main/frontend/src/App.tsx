import { useEffect, useState } from 'react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';
import ReleaseDiffView from './views/ReleaseDiffView';
import AppPicker from './components/AppPicker';

type View = 'trace' | 'impact' | 'diff';
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
    <div className="app" data-app={app}>
      <header>
        <div className="brand">
          <h1>TraceGuard <span className="app-badge" title="selected application">{app}</span></h1>
          <span>Trace the flow. Guard the release. <span className="header-ai">· ✨ Powered by AI</span></span>
        </div>
        <nav className="tabs">
          <button className={view === 'trace' ? 'tab active' : 'tab'} onClick={() => setView('trace')}>Trace</button>
          <button className={view === 'impact' ? 'tab active' : 'tab'} onClick={() => setView('impact')}>Impact analysis</button>
          <button className={view === 'diff' ? 'tab active' : 'tab'} onClick={() => setView('diff')}>Release Diff</button>
          <button className="tab" title="Switch application" onClick={() => setApp(null)}>⇄ App</button>
          <button className="tab theme-toggle" title="Toggle theme"
                  onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
            {theme === 'light' ? '🌙' : '☀️'}
          </button>
        </nav>
      </header>
      {view === 'trace' && <TraceView app={app} colorMode={theme} />}
      {view === 'impact' && <ImpactView app={app} colorMode={theme} />}
      {view === 'diff' && <ReleaseDiffView app={app} colorMode={theme} />}
    </div>
  );
}
