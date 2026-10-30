import { Link } from 'react-router-dom';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMenuFocus } from '../menuFocus';

export interface ConsoleNavEntry<K extends string> {
  key: K; label: string; icon: string;
  badge?: string; badgeLabel?: string; warn?: boolean;
}

/**
 * One item's own menu, down the left of a console: the event in the ticket console, the
 * link in the link console. On a narrow screen it folds into one line naming the screen
 * in view, which opens the whole menu, so the screen's content starts near the top.
 */
export default function ConsoleNav<K extends string>({ label, back, card, groups, current }: {
  label: string;
  back: { to: string; label: string };
  card: ReactNode;
  groups: Array<[string, ConsoleNavEntry<K>[]]>;
  current: K;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const here = groups.flatMap(([, entries]) => entries).find(entry => entry.key === current);
  useEffect(() => { setOpen(false); }, [current]);
  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); };
    window.addEventListener('pointerdown', onDown);
    return () => window.removeEventListener('pointerdown', onDown);
  }, [open]);
  useMenuFocus(open, toggle, menu, () => setOpen(false));
  return (
    <nav className="event-nav" aria-label={label} ref={box}>
      <Link className="event-nav-back" to={back.to}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 18l-6-6 6-6" /></svg>
        {back.label}
      </Link>
      <div className="event-nav-card">{card}</div>
      <button ref={toggle} type="button" className="event-nav-toggle" aria-expanded={open} onClick={() => setOpen(value => !value)}>
        {here && <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={here.icon} /></svg>}
        <span>{here?.label ?? '메뉴'}</span>
        <svg className="event-nav-chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
      <div ref={menu} className={`event-nav-menu${open ? ' open' : ''}`}>
      {groups.map(([title, entries]) => (
        <div className="event-nav-group" key={title || 'top'}>
          {title && <span className="event-nav-label">{title}</span>}
          {entries.map(entry => (
            <Link key={entry.key} to={`?tab=${entry.key}`}
              className={current === entry.key ? 'on' : ''} aria-current={current === entry.key ? 'page' : undefined}>
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"
                strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={entry.icon} /></svg>
              <span className="event-nav-text">{entry.label}</span>
              {entry.badge && (
                <span className={`event-nav-badge${entry.warn ? ' warn' : ''}`} aria-label={entry.badgeLabel}>
                  {entry.badge}</span>
              )}
            </Link>
          ))}
        </div>
      ))}
      </div>
    </nav>
  );
}

/** Stroke drawings for menu entries, shared so both consoles read alike. */
export const NAV_ICONS = {
  overview: 'M4 13h6V4H4zM14 20h6v-9h-6zM14 4v4h6V4zM4 20h6v-4H4z',
  list: 'M4 6h16M4 12h16M4 18h10',
  add: 'M12 5v14M5 12h14',
  movements: 'M7 7h11l-3-3M17 17H6l3 3',
  gates: 'M5 4h14v16H5zM9 9h6v6H9z',
  coupons: 'M4 8a2 2 0 0 0 0 4v4h16v-4a2 2 0 0 1 0-4V4H4zM12 4v12',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M14 4v6M8 14v6',
  views: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  chat: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z',
  ads: 'M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM16 8a5 5 0 0 1 0 8',
} as const;
