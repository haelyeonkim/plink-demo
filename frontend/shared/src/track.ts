import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * A small beacon: which screens are opened, and what broke in the browser.
 *
 * <p>Only the path is sent, never the query string or the hash, and any segment that
 * looks like a token - a holder's ticket, a gate's setup link, a recipient's link - is
 * replaced with ":token" first, so the log can be read without handing out a way in.
 */
export type TrackedApp = 'link' | 'ticket';

const ENDPOINT = '/api/log/client';
const STACK_LIMIT = 4000;
const REPEAT_MS = 1000;
/** Segments after these are always someone's key, however short. */
const SECRET_AFTER = new Set(['x', 'booth', 'gate']);

export function scrubPath(pathname: string): string {
  const parts = pathname.split('/');
  return parts.map((part, index) => {
    if (/^[A-Za-z0-9_-]{20,}$/.test(part)) return ':token';
    if (part && index > 0 && SECRET_AFTER.has(parts[index - 1])) return ':token';
    return part;
  }).join('/') || '/';
}

function send(payload: Record<string, unknown>) {
  const body = JSON.stringify(payload);
  try {
    if (typeof navigator.sendBeacon === 'function'
      && navigator.sendBeacon(ENDPOINT, new Blob([body], { type: 'application/json' }))) return;
  } catch { /* fall through to fetch */ }
  try {
    void fetch(ENDPOINT, {
      method: 'POST', body, keepalive: true, credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    }).catch(() => undefined);
  } catch { /* a beacon never gets in the way of the page */ }
}

let lastView = { path: '', at: 0 };
let lastError = { message: '', at: 0 };
let installedFor: TrackedApp | null = null;

/** One page view; the same path twice within a second (a re-render, StrictMode) is one. */
export function trackView(app: TrackedApp, pathname = window.location.pathname) {
  const path = scrubPath(pathname);
  const now = Date.now();
  if (lastView.path === path && now - lastView.at < REPEAT_MS) return;
  lastView = { path, at: now };
  send({ kind: 'VIEW', app, path });
}

export function trackError(app: TrackedApp, message: string, stack?: string) {
  const now = Date.now();
  if (lastError.message === message && now - lastError.at < REPEAT_MS) return;
  lastError = { message, at: now };
  send({
    kind: 'ERROR', app, path: scrubPath(window.location.pathname),
    message: message.slice(0, 1000), stack: stack ? stack.slice(0, STACK_LIMIT) : null,
  });
}

function describe(reason: unknown): { message: string; stack?: string } {
  if (reason instanceof Error) return { message: reason.message || reason.name, stack: reason.stack };
  if (typeof reason === 'string') return { message: reason };
  try { return { message: JSON.stringify(reason) ?? String(reason) }; }
  catch { return { message: String(reason) }; }
}

/** Errors the page did not catch. Installed once per page, whatever calls it again. */
export function installTracking(app: TrackedApp) {
  if (installedFor) return;
  installedFor = app;
  window.addEventListener('error', event => {
    const { message, stack } = event.error != null ? describe(event.error) : { message: event.message, stack: undefined };
    trackError(app, message || '알 수 없는 오류', stack);
  });
  window.addEventListener('unhandledrejection', event => {
    const { message, stack } = describe(event.reason);
    trackError(app, message || '처리되지 않은 거부', stack);
  });
}

/** Placed inside the router: installs the error beacon and reports each screen opened. */
export function Tracker({ app }: { app: TrackedApp }) {
  const { pathname } = useLocation();
  useEffect(() => { installTracking(app); }, [app]);
  useEffect(() => { trackView(app, pathname); }, [app, pathname]);
  return null;
}
