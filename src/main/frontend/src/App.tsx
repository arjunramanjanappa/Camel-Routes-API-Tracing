import { useState } from 'react';
import TraceView from './views/TraceView';
import ImpactView from './views/ImpactView';

type View = 'trace' | 'impact';

export default function App() {
  const [view, setView] = useState<View>('trace');
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
        </nav>
      </header>
      {view === 'trace' ? <TraceView /> : <ImpactView />}
    </div>
  );
}
