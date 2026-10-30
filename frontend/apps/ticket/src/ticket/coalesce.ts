import { useCallback, useEffect, useRef } from 'react';

/**
 * Turns a burst of "something changed" into one call. The first nudge starts a short
 * wait and any more during it ride along, so ten lines said in a second cost one reload,
 * not ten. {@code spread} adds a random part to the wait: every phone in the room hears
 * the same message at once, and they should not all ask the server in the same instant.
 */
export function useCoalesced(run: () => void, delay = 400, spread = 0): () => void {
  const latest = useRef(run);
  useEffect(() => { latest.current = run; }, [run]);
  const timer = useRef(0);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return useCallback(() => {
    if (timer.current) return;
    timer.current = window.setTimeout(() => {
      timer.current = 0;
      latest.current();
    }, delay + Math.random() * spread);
  }, [delay, spread]);
}

/** A line's counts after someone pressed on it, sent to everyone as CHAT_LIKE. */
export interface ChatCounts { messageId: number; likes: number; dislikes: number }

/** The counts in a CHAT_LIKE message, or null for one without them (an older server). */
export function chatCounts(message: Record<string, unknown>): ChatCounts | null {
  const { messageId, likes, dislikes } = message;
  return typeof messageId === 'number' && typeof likes === 'number' && typeof dislikes === 'number'
    ? { messageId, likes, dislikes } : null;
}
