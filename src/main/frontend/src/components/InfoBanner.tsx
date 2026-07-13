import type { ReactNode } from 'react';

/** A neutral information banner with a proper info icon — for plain-language notes to any audience. */
export default function InfoBanner({ children }: { children: ReactNode }) {
  return (
    <div className="info-banner">
      <svg className="info-banner-ic" viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
        <circle cx="10" cy="10" r="9" fill="none" stroke="currentColor" strokeWidth="1.6" />
        <circle cx="10" cy="6" r="1.15" fill="currentColor" />
        <rect x="9" y="8.6" width="2" height="6" rx="1" fill="currentColor" />
      </svg>
      <div>{children}</div>
    </div>
  );
}
