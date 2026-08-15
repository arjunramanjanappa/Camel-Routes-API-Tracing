/** @type {import('tailwindcss').Config} */
export default {
  // Dark mode follows the app's existing switch: document.documentElement.dataset.theme = 'dark'.
  darkMode: ['class', '[data-theme="dark"]'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  // Preflight (Tailwind's CSS reset) is OFF so Tailwind can coexist with the legacy styles.css during the
  // incremental UI migration without wiping the current look. Re-enable once styles.css is fully retired.
  corePlugins: { preflight: false },
  theme: {
    extend: {
      // Colours are driven by CSS variables (see theme.css) as "R G B" triplets, so /opacity modifiers work
      // and light/dark + per-app accent switch purely in CSS.
      colors: {
        bg: 'rgb(var(--tg-bg) / <alpha-value>)',
        surface: 'rgb(var(--tg-surface) / <alpha-value>)',
        'surface-2': 'rgb(var(--tg-surface-2) / <alpha-value>)',
        ink: 'rgb(var(--tg-ink) / <alpha-value>)',
        'ink-2': 'rgb(var(--tg-ink-2) / <alpha-value>)',
        muted: 'rgb(var(--tg-muted) / <alpha-value>)',
        faint: 'rgb(var(--tg-faint) / <alpha-value>)',
        hair: 'rgb(var(--tg-hair) / <alpha-value>)',
        'hair-2': 'rgb(var(--tg-hair-2) / <alpha-value>)',
        accent: 'rgb(var(--tg-accent) / <alpha-value>)',
        'accent-soft': 'rgb(var(--tg-accent-soft) / <alpha-value>)',
        'accent-ink': 'rgb(var(--tg-accent-ink) / <alpha-value>)',
        ok: 'rgb(var(--tg-ok) / <alpha-value>)',
        'ok-soft': 'rgb(var(--tg-ok-soft) / <alpha-value>)',
        warn: 'rgb(var(--tg-warn) / <alpha-value>)',
        'warn-soft': 'rgb(var(--tg-warn-soft) / <alpha-value>)',
        crit: 'rgb(var(--tg-crit) / <alpha-value>)',
        'crit-soft': 'rgb(var(--tg-crit-soft) / <alpha-value>)',
        info: 'rgb(var(--tg-info) / <alpha-value>)',
        'info-soft': 'rgb(var(--tg-info-soft) / <alpha-value>)',
        bc: 'rgb(var(--tg-bc) / <alpha-value>)',
        'bc-soft': 'rgb(var(--tg-bc-soft) / <alpha-value>)',
        navy: 'rgb(var(--tg-navy) / <alpha-value>)',
      },
      fontFamily: {
        mono: ['ui-monospace', 'Cascadia Code', 'SF Mono', 'Consolas', 'monospace'],
      },
      boxShadow: {
        raise: '0 1px 2px rgb(15 23 42 / 0.06), 0 1px 3px rgb(15 23 42 / 0.05)',
        pop: '0 20px 45px rgb(15 23 42 / 0.14)',
      },
    },
  },
  plugins: [],
};
