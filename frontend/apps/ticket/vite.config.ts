import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { allowedHosts, backendProxy, dist, shared } from '../../vite.common.ts';

/**
 * The ticket app: the holder's ticket, the gate terminal and the organiser console, all
 * under /tickets. In development the link app's server on :3000 forwards /tickets here,
 * so the site is used through that one address.
 */
export default defineConfig({
  root: import.meta.dirname,
  base: '/tickets/',
  plugins: [react()],
  resolve: { alias: { '@shared': shared } },
  // Logo and icons are the site's, served by the link app at the root.
  publicDir: false,
  build: { outDir: `${dist}/tickets`, emptyOutDir: true },
  server: {
    port: Number(process.env.TICKET_DEV_PORT || 3001),
    strictPort: true,
    allowedHosts,
    proxy: backendProxy,
  },
  appType: 'spa',
});
