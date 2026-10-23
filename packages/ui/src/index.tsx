import type { ButtonHTMLAttributes, PropsWithChildren, ReactNode } from 'react';
export { ClanDirectory } from './clan-directory.js';

export function ProductShell({ eyebrow, title, nav, children }: PropsWithChildren<{ eyebrow: string; title: string; nav: ReactNode }>) {
  return <div className="product-shell">
    <aside className="product-sidebar">
      <div className="brand-mark" aria-hidden="true">A</div>
      <div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1></div>
      <nav aria-label="Основная навигация">{nav}</nav>
    </aside>
    <main className="product-main">{children}</main>
  </div>;
}

export function StatusPanel({ tone, title, children }: PropsWithChildren<{ tone: 'neutral' | 'warning' | 'success'; title: string }>) {
  return <section className={`status-panel status-panel--${tone}`} aria-live="polite">
    <span className="status-dot" aria-hidden="true" />
    <div><h2>{title}</h2><div className="status-copy">{children}</div></div>
  </section>;
}

export function Button(props: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button className="button" {...props} />;
}
