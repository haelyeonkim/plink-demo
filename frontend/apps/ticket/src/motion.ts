import { useEffect, useState } from 'react';

/**
 * Movement, and who has asked not to see it.
 *
 * <p>The animations here carry meaning - a code being spent, a lock opening, a line
 * being written to the ledger - so none of them may be the only way to learn the result.
 * Where motion is refused the same words still arrive, just at once.
 */
export function reducedMotion(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
}

/**
 * Writes text out a character at a time, the way a ledger line is appended rather than
 * simply appearing. Reduced motion gets the finished line.
 */
export function useTyped(text: string, speed = 24): string {
  const [shown, setShown] = useState(text);
  useEffect(() => {
    if (reducedMotion()) { setShown(text); return; }
    setShown('');
    let cut = 0;
    const timer = window.setInterval(() => {
      cut += 1;
      setShown(text.slice(0, cut));
      if (cut >= text.length) window.clearInterval(timer);
    }, speed);
    return () => window.clearInterval(timer);
  }, [text, speed]);
  return shown;
}

/** The local clock, as a ledger writes it: 12:03:41. */
export function clockText(at: string | number = Date.now()): string {
  const when = new Date(at);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(when.getHours())}:${pad(when.getMinutes())}:${pad(when.getSeconds())}`;
}
