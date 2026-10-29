/**
 * The live channel.
 *
 * <p>One socket per screen, reconnecting with a backing-off delay, and every message is
 * a snapshot rather than a delta: a phone that missed three events while its screen was
 * off is correct as soon as the next one arrives, with nothing to replay.
 *
 * <p>Polling stays in place behind this at a slower interval. A network that blocks the
 * upgrade - a captive portal, a proxy that does not forward it - should cost freshness,
 * never function.
 */
export type LiveMessage = { type: string } & Record<string, unknown>;

export interface LiveChannel {
  close(): void;
}

/** Whether the channel is carrying messages now, or waiting to reconnect. */
export type LiveState = 'connecting' | 'open' | 'retrying';

function socketUrl(path: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${path}`;
}

/**
 * One socket per path, shared by whoever is listening. The ticket screen and the crowd
 * panel watch the same channel, and two connections per phone would be two of
 * everything - handshakes, snapshots, retries - for the same messages.
 */
interface Shared {
  channel: LiveChannel;
  listeners: Set<(m: LiveMessage) => void>;
  watchers: Set<(state: LiveState) => void>;
  state: LiveState;
}
const shared = new Map<string, Shared>();

export function openLive(path: string, onMessage: (message: LiveMessage) => void,
    onState?: (state: LiveState) => void): LiveChannel {
  let entry = shared.get(path);
  if (!entry) {
    const created: Shared = { channel: { close() {} }, listeners: new Set(), watchers: new Set(), state: 'connecting' };
    created.channel = connectLive(path,
      message => created.listeners.forEach(listener => listener(message)),
      state => { created.state = state; created.watchers.forEach(watch => watch(state)); });
    shared.set(path, created);
    entry = created;
  }
  const current = entry;
  current.listeners.add(onMessage);
  if (onState) { current.watchers.add(onState); onState(current.state); }
  return { close() {
    current.listeners.delete(onMessage);
    if (onState) current.watchers.delete(onState);
    if (current.listeners.size === 0) { shared.delete(path); current.channel.close(); }
  } };
}

function connectLive(path: string, onMessage: (message: LiveMessage) => void,
    onState: (state: LiveState) => void): LiveChannel {
  let socket: WebSocket | null = null;
  let closed = false;
  let attempt = 0;
  let timer = 0;

  function connect() {
    if (closed) return;
    try {
      socket = new WebSocket(socketUrl(path));
    } catch {
      retry();
      return;
    }
    socket.onopen = () => { attempt = 0; onState('open'); };
    socket.onmessage = event => {
      try { onMessage(JSON.parse(String(event.data)) as LiveMessage); }
      catch { /* a message we cannot read is not worth tearing the socket down for */ }
    };
    socket.onclose = () => { socket = null; if (!closed) onState('retrying'); retry(); };
    socket.onerror = () => { socket?.close(); };
  }

  function retry() {
    if (closed) return;
    // 1s, 2s, 4s … capped: a proxy that refuses the upgrade must not become a spin.
    const delay = Math.min(30000, 1000 * 2 ** Math.min(attempt, 5));
    attempt += 1;
    timer = window.setTimeout(connect, delay);
  }

  // Coming back to the screen reconnects at once rather than waiting out the backoff.
  const onVisible = () => {
    if (document.visibilityState !== 'visible' || closed) return;
    if (!socket || socket.readyState > WebSocket.OPEN) { attempt = 0; window.clearTimeout(timer); connect(); }
  };
  document.addEventListener('visibilitychange', onVisible);

  connect();

  return {
    close() {
      closed = true;
      window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
      socket?.close();
    },
  };
}
