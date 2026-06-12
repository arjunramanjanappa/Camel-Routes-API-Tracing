import { useEffect, useState } from 'react';

/**
 * An engaging loader for long, blocking analyses. The backend can't stream progress,
 * so we reassure the user it's alive: an indeterminate bar, a rotating description of
 * the step underway, and an elapsed-time counter — a full-framework scan never reads
 * as "hung".
 */
export default function Loader({ messages, note }: { messages: string[]; note?: string }) {
  const [i, setI] = useState(0);
  const [secs, setSecs] = useState(0);
  useEffect(() => {
    const m = setInterval(() => setI((x) => (x + 1) % messages.length), 1900);
    const s = setInterval(() => setSecs((x) => x + 1), 1000);
    return () => { clearInterval(m); clearInterval(s); };
  }, [messages.length]);

  return (
    <div className="loader" role="status" aria-live="polite">
      <div className="loader-bar"><span /></div>
      <div className="loader-msg" key={i}>{messages[i]}</div>
      <div className="loader-sub">{note ? note + ' · ' : ''}{secs}s elapsed{secs >= 8 ? ' · hang tight' : ''}</div>
    </div>
  );
}

export const SCAN_MESSAGES = [
  'Scanning the framework source…',
  'Indexing controllers and operations…',
  'Resolving route versions and fallbacks…',
  'Walking direct: and seda: routes…',
  'Extracting backend APIs…',
  'Reading template service versions…',
  'Laying out the flow graph…',
  'Almost there…',
];

export const IMPACT_MESSAGES = [
  'Scanning the framework source…',
  'Cataloguing every API in the release…',
  'Resolving routes and version fallbacks…',
  'Mapping routes to their backends…',
  'Reading backend service versions…',
  'Cross-referencing impacted APIs…',
  'Building the impact index…',
  'Almost there…',
];
