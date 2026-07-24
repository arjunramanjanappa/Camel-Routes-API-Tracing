import { useEffect, useState } from 'react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';
import ReleaseDiffView from './views/ReleaseDiffView';
import AppPicker from './components/AppPicker';
import ConfigMenu from './components/ConfigMenu';

type View = 'trace' | 'impact' | 'diff';
type Theme = 'light' | 'dark';
type ViewMode = 'summary' | 'detailed';

export default function App() {
  const [app, setApp] = useState<string | null>(null);
  const [view, setView] = useState<View>('trace');
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('tracer.theme') as Theme) || 'light');
  // Summary (for release managers/leads) vs Detailed (for devs/testers). Default Summary; remembered per user.
  const [viewMode, setViewMode] = useState<ViewMode>(() => (localStorage.getItem('tracer.viewMode') as ViewMode) || 'summary');
  const [showConfig, setShowConfig] = useState(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('tracer.theme', theme);
  }, [theme]);

  useEffect(() => { localStorage.setItem('tracer.viewMode', viewMode); }, [viewMode]);

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
          {/* Primary navigation — the three release views */}
          <div className="nav-group nav-tabs" role="tablist">
            <button className={view === 'trace' ? 'tab active' : 'tab'} role="tab" aria-selected={view === 'trace'} onClick={() => setView('trace')}>Release Scope</button>
            <button className={view === 'impact' ? 'tab active' : 'tab'} role="tab" aria-selected={view === 'impact'} onClick={() => setView('impact')}>Release Test</button>
            <button className={view === 'diff' ? 'tab active' : 'tab'} role="tab" aria-selected={view === 'diff'} onClick={() => setView('diff')}>Release Impact</button>
          </div>
          <span className="nav-sep" aria-hidden="true" />
          {/* Audience view */}
          <div className="view-switch" role="group" aria-label="View mode">
            <button className={viewMode === 'summary' ? 'on' : ''} title="Summary — for release managers, coordinators & delivery leads"
                    onClick={() => setViewMode('summary')}>◱ Summary</button>
            <button className={viewMode === 'detailed' ? 'on' : ''} title="Detailed — for developers & testers"
                    onClick={() => setViewMode('detailed')}>⚙ Detailed</button>
          </div>
          <span className="nav-sep" aria-hidden="true" />
          {/* Utilities */}
          <div className="nav-group nav-util">
            <button className="tab" title="Switch application" onClick={() => setApp(null)}>⇄ App</button>
            <button className="tab" title="Config — Bitbucket / npm tokens" onClick={() => setShowConfig(true)}>🔑 Config</button>
            <button className="tab theme-toggle" title="Toggle theme" onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
              {theme === 'light' ? '🌙' : '☀️'}
            </button>
          </div>
        </nav>
      </header>
      {view === 'trace' && <TraceView app={app} colorMode={theme} viewMode={viewMode} />}
      {view === 'impact' && <ImpactView app={app} colorMode={theme} viewMode={viewMode} />}
      {view === 'diff' && <ReleaseDiffView app={app} colorMode={theme} viewMode={viewMode} />}
      {showConfig && <ConfigMenu onClose={() => setShowConfig(false)} />}
    </div>
  );
}
