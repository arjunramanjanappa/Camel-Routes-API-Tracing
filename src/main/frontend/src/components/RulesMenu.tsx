import { useEffect } from 'react';
import { X, ListChecks } from 'lucide-react';
import LogRulesEditor from './LogRulesEditor';

/**
 * Header ⚙ Rules modal — the machine-wide host response-code rules (log-rules.json) for log analysis: read a
 * backend's code from a different key, treat a custom value as success, or skip a backend from the verdict.
 * Separate from ⚙ Config (which holds tokens / the Splunk URL); reachable from anywhere, no Load required.
 */
export default function RulesMenu({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onEsc);
    return () => window.removeEventListener('keydown', onEsc);
  }, [onClose]);
  return (
    <div className="flow-modal-backdrop" onClick={onClose}>
      <div className="flow-modal config-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Host response-code rules">
        <div className="flow-modal-head">
          <span className="flow-modal-title"><ListChecks size={16} aria-hidden="true" /> Host response-code rules <span className="muted">— log analysis (skip / custom response code)</span></span>
          <button className="minibtn" onClick={onClose}><X aria-hidden="true" /> Close</button>
        </div>
        <div className="flow-modal-body config-body">
          <LogRulesEditor />
        </div>
      </div>
    </div>
  );
}
