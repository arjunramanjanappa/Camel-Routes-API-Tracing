import { useState, type ReactNode } from 'react';

/** A panel-styled header that expands/collapses its children. Collapsed by default,
 *  used to tuck away the Impact tab's advanced/optional sections. */
export default function Collapsible({ title, hint, defaultOpen = false, children }: {
  title: string;
  hint?: string;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <>
      <button type="button" className="panel collapse-head" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="collapse-caret">{open ? '▾' : '▸'}</span>
        <span className="collapse-title">{title}</span>
        {hint && <span className="muted">{hint}</span>}
      </button>
      {open && children}
    </>
  );
}
