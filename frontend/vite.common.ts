import { fileURLToPath } from 'node:url';
import type { ProxyOptions } from 'vite';

/** What both front ends share: the backend they talk to and the hosts they answer for. */
export const backend = process.env.BACKEND_URL || 'http://localhost:8080';

/** One directory for the whole site: the link app at its root, the ticket app in tickets/. */
export const dist = process.env.PLINK_DIST || fileURLToPath(new URL('./dist', import.meta.url));

export const shared = fileURLToPath(new URL('./shared/src', import.meta.url));

export const allowedHosts = ['lyuni.ddak.app', 'plink.ddak.app', 'passlink-stage.ddak.app'];

// xfwd: the backend counts failed sign-ins and link attempts per client, and behind this
// proxy every request would otherwise come from 127.0.0.1 - one client for the world.
export const backendProxy: Record<string, ProxyOptions> = {
  '/oauth2': { target: backend, changeOrigin: true, xfwd: true },
  '/login/oauth2': { target: backend, changeOrigin: true, xfwd: true },
  '/api': { target: backend, changeOrigin: true, xfwd: true },
  // ws: the live channel is an upgrade, and the dev server has to carry it through
  // rather than answering with index.html.
  '/ws': { target: backend, changeOrigin: true, ws: true, xfwd: true },
};
