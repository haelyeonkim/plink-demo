import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type MouseEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/* What an item means, behind a small "i" beside its heading or label instead of a line of
   grey text under it. A mouse shows it on hover, the keyboard on focus, a finger by a tap;
   Escape or a press anywhere else puts it away. The bubble is drawn on the page itself, so
   a table, a card or a side panel that clips its overflow does not cut it off. */

// Only one is open at a time: opening one closes whichever was open before.
let closeCurrent: (() => void) | null = null;

type Place = { top: number; left: number; arrow: number; below: boolean };

const GAP = 8;
const EDGE = 8;

export default function InfoTip({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  const id = useId();
  const button = useRef<HTMLButtonElement>(null);
  const bubble = useRef<HTMLDivElement>(null);
  const leaving = useRef<number | undefined>(undefined);
  const [open, setOpen] = useState(false);
  // Opened by a press, it stays until pressed again or dismissed; opened by hover or
  // focus, it goes when the pointer or the focus does.
  const [pinned, setPinned] = useState(false);
  const [place, setPlace] = useState<Place | null>(null);

  const close = useCallback(() => {
    window.clearTimeout(leaving.current);
    setOpen(false);
    setPinned(false);
    setPlace(null);
  }, []);

  const show = useCallback((pin: boolean) => {
    window.clearTimeout(leaving.current);
    if (closeCurrent && closeCurrent !== close) closeCurrent();
    closeCurrent = close;
    setOpen(true);
    if (pin) setPinned(true);
  }, [close]);

  const leaveSoon = () => {
    if (pinned) return;
    window.clearTimeout(leaving.current);
    leaving.current = window.setTimeout(close, 120);
  };

  const measure = useCallback(() => {
    const anchor = button.current?.getBoundingClientRect();
    const tip = bubble.current;
    if (!anchor || !tip) return;
    const width = tip.offsetWidth;
    const height = tip.offsetHeight;
    const viewport = document.documentElement.clientWidth;
    const centre = anchor.left + anchor.width / 2;
    const left = Math.max(EDGE, Math.min(centre - width / 2, viewport - width - EDGE));
    const above = anchor.top - GAP - height;
    const below = above < EDGE;
    setPlace({
      top: below ? anchor.bottom + GAP : above,
      left,
      arrow: Math.max(12, Math.min(centre - left, width - 12)),
      below,
    });
  }, []);

  useLayoutEffect(() => {
    if (open) measure();
  }, [open, measure]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    const onPress = (event: PointerEvent) => {
      const target = event.target as Node;
      if (button.current?.contains(target) || bubble.current?.contains(target)) return;
      close();
    };
    window.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onPress, true);
    window.addEventListener('scroll', measure, true);
    window.addEventListener('resize', measure);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onPress, true);
      window.removeEventListener('scroll', measure, true);
      window.removeEventListener('resize', measure);
    };
  }, [open, close, measure]);

  useEffect(() => () => {
    window.clearTimeout(leaving.current);
    if (closeCurrent === close) closeCurrent = null;
  }, [close]);

  // Inside a <label> a press would otherwise tick its box or focus its field, and inside
  // a <summary> it would fold the section.
  const onClick = (event: MouseEvent) => {
    event.preventDefault();
    event.stopPropagation();
    if (open && pinned) close();
    else show(true);
  };

  return (
    <>
      <button
        ref={button}
        type="button"
        className={className ? `info-tip ${className}` : 'info-tip'}
        aria-label={`${label} 설명`}
        aria-describedby={open ? id : undefined}
        aria-expanded={open}
        onClick={onClick}
        onPointerEnter={(event) => { if (event.pointerType === 'mouse') show(false); }}
        onPointerLeave={(event) => { if (event.pointerType === 'mouse') leaveSoon(); }}
        onFocus={() => show(false)}
        onBlur={() => { if (!pinned) close(); }}
      >
        <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
          <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <circle cx="8" cy="4.9" r="0.95" fill="currentColor" />
          <path d="M8 7.2v4.6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>
      {open && createPortal(
        <div
          ref={bubble}
          id={id}
          role="tooltip"
          className={`info-tip-bubble${place?.below ? ' below' : ''}`}
          style={place
            ? { top: place.top, left: place.left, '--arrow': `${place.arrow}px` } as CSSProperties
            : { top: 0, left: 0, visibility: 'hidden' }}
          onPointerEnter={() => window.clearTimeout(leaving.current)}
          onPointerLeave={(event) => { if (event.pointerType === 'mouse') leaveSoon(); }}
        >
          {children}
        </div>,
        document.body,
      )}
    </>
  );
}
