import { Fragment } from 'react';

export type StepState = 'done' | 'active' | 'todo';

/** A slim guided-step strip for the Impact tab: Load → Pick APIs → Query/upload → Results. */
export default function Steps({ steps }: { steps: { label: string; state: StepState }[] }) {
  return (
    <div className="steps">
      {steps.map((s, i) => (
        <Fragment key={s.label}>
          {i > 0 && <div className={'step-line' + (s.state !== 'todo' ? ' on' : '')} />}
          <div className={'step ' + s.state}>
            <span className="step-dot">{s.state === 'done' ? '✓' : i + 1}</span>
            <span className="step-label">{s.label}</span>
          </div>
        </Fragment>
      ))}
    </div>
  );
}
