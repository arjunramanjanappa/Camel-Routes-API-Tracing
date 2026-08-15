import { useEffect, useState } from 'react';
import { ShieldCheck, Radar, ClipboardCheck, GitCompareArrows, LayoutDashboard, Table2,
         ArrowLeftRight, KeyRound, SlidersHorizontal, Moon, Sun } from 'lucide-react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';
import ReleaseDiffView from './views/ReleaseDiffView';
import AppPicker from './components/AppPicker';
import ConfigMenu from './components/ConfigMenu';
import RulesMenu from './components/RulesMenu';
import { appLabel } from './appName';

type View = 'trace' | 'impact' | 'diff';
type Theme = 'light' | 'dark';
type ViewMode = 'summary' | 'detailed';

const TABS: { id: View; label: string; Icon: typeof Radar }[] = [
  { id: 'trace', label: 'Release Scope', Icon: Radar },
  { id: 'impact', label: 'Release Test', Icon: ClipboardCheck },
  { id: 'diff', label: 'Release Impact', Icon: GitCompareArrows },
];

export default function App() {
  const [app, setApp] = useState<string | null>(null);
  const [view, setView] = useState<View>('trace');
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('tracer.theme') as Theme) || 'light');
  // Summary (for release managers/leads) vs Detailed (for devs/testers). Default Summary; remembered per user.
  const [viewMode, setViewMode] = useState<ViewMode>(() => (localStorage.getItem('tracer.viewMode') as ViewMode) || 'summary');
  const [showConfig, setShowConfig] = useState(false);
  const [showRules, setShowRules] = useState(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('tracer.theme', theme);
  }, [theme]);

  useEffect(() => { localStorage.setItem('tracer.viewMode', viewMode); }, [viewMode]);

  if (!app) {
    return <AppPicker onPick={(a) => { setApp(a); localStorage.setItem('tracer.app', a); }} />;
  }

  const iconBtn = 'grid place-items-center w-[34px] h-[34px] rounded-lg border border-white/15 bg-white/[.06] text-[#cdd7e6] hover:bg-white/[.14] hover:text-white transition-colors';

  return (
    <div className="app" data-app={app}>
      <header className="sticky top-0 z-20 flex items-center gap-4 h-[54px] px-5 bg-navy text-[#e8eef8]">
        {/* Brand */}
        <div className="flex items-center gap-2.5 font-extrabold text-[16px] tracking-tight shrink-0">
          <ShieldCheck className="w-[26px] h-[26px] text-white" />
          <span className="leading-none">TraceGuard
            <span className="block text-[9px] font-semibold tracking-[.14em] uppercase text-[#8fb0e6] mt-0.5">Release intelligence</span>
          </span>
        </div>
        {/* Primary tabs */}
        <nav className="flex gap-1 ml-1.5" role="tablist">
          {TABS.map(({ id, label, Icon }) => {
            const active = view === id;
            return (
              <button key={id} role="tab" aria-selected={active} onClick={() => setView(id)}
                className={'flex items-center gap-2 rounded-lg px-3.5 py-[7px] text-[13px] font-semibold transition-colors '
                  + (active ? 'bg-accent text-white shadow' : 'text-[#93a4bd] hover:text-white hover:bg-white/[.06]')}>
                <Icon className="w-[15px] h-[15px]" /> {label}
              </button>
            );
          })}
        </nav>

        <div className="flex-1" />

        {/* Audience view switch */}
        <div className="flex rounded-lg overflow-hidden border border-white/15" role="group" aria-label="View mode">
          <button title="Summary — for release managers, coordinators & delivery leads" onClick={() => setViewMode('summary')}
            className={'flex items-center gap-1.5 px-3 py-[7px] text-[12px] font-semibold ' + (viewMode === 'summary' ? 'bg-accent text-white' : 'bg-white/[.05] text-[#b9c6db] hover:text-white')}>
            <LayoutDashboard className="w-[14px] h-[14px]" /> Summary
          </button>
          <button title="Detailed — for developers & testers" onClick={() => setViewMode('detailed')}
            className={'flex items-center gap-1.5 px-3 py-[7px] text-[12px] font-semibold border-l border-white/15 ' + (viewMode === 'detailed' ? 'bg-accent text-white' : 'bg-white/[.05] text-[#b9c6db] hover:text-white')}>
            <Table2 className="w-[14px] h-[14px]" /> Detailed
          </button>
        </div>

        {/* App context + utilities */}
        <span className="text-[11.5px] font-bold text-white bg-white/10 border border-white/15 rounded-full px-2.5 py-1" title="Selected application">{appLabel(app)}</span>
        <div className="flex items-center gap-1.5">
          <button className={iconBtn} title="Switch application" onClick={() => setApp(null)}><ArrowLeftRight className="w-[17px] h-[17px]" /></button>
          <button className={iconBtn} title="Config — Bitbucket / npm tokens · Splunk URL" onClick={() => setShowConfig(true)}><KeyRound className="w-[17px] h-[17px]" /></button>
          <button className={iconBtn} title="Host response-code rules — skip a backend or read a custom result code" onClick={() => setShowRules(true)}><SlidersHorizontal className="w-[17px] h-[17px]" /></button>
          <button className={iconBtn} title="Toggle theme" onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
            {theme === 'light' ? <Moon className="w-[17px] h-[17px]" /> : <Sun className="w-[17px] h-[17px]" />}
          </button>
        </div>
      </header>
      {view === 'trace' && <TraceView app={app} colorMode={theme} viewMode={viewMode} />}
      {view === 'impact' && <ImpactView app={app} colorMode={theme} viewMode={viewMode} />}
      {view === 'diff' && <ReleaseDiffView app={app} colorMode={theme} viewMode={viewMode} />}
      {showConfig && <ConfigMenu onClose={() => setShowConfig(false)} />}
      {showRules && <RulesMenu onClose={() => setShowRules(false)} />}
    </div>
  );
}
