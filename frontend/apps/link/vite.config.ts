import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, sep } from 'node:path';
import { defineConfig, type Connect, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { allowedHosts, backendProxy, dist, shared } from '../../vite.common.ts';
import { frontDoorOf, withFrontDoorHead } from '../../shared/src/frontDoor.ts';

const ticketDev = `http://localhost:${process.env.TICKET_DEV_PORT || 3010}`;
const TICKETS = /^\/tickets(?:[/?#]|$)/;

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json',
  '.woff2': 'font/woff2', '.map': 'application/json',
};

/**
 * The link app is the site's front door. In a preview (stage, production) it serves the
 * ticket app's build from dist/tickets, and answers any /tickets page with that app's
 * index.html rather than its own.
 */
function ticketApp(): Plugin {
  return {
    name: 'plink-ticket-app',
    configurePreviewServer(server) {
      const root = join(dist, 'tickets');
      server.middlewares.use((req, res, next) => {
        const url = req.url || '/';
        if (!TICKETS.test(url)) return next();
        let path: string;
        try { path = decodeURIComponent(url.split(/[?#]/)[0]).replace(/^\/tickets\/?/, ''); }
        catch { return next(); }
        let file = normalize(join(root, path));
        if (file !== root && !file.startsWith(root + sep)) return next();
        const asset = existsSync(file) && statSync(file).isFile();
        if (!asset) {
          // A missing script or image is a 404, not a page.
          if (path.startsWith('assets/')) { res.statusCode = 404; return res.end(); }
          file = join(root, 'index.html');
          // Between a build emptying dist and writing the ticket app back, there is no page
          // to send. Say so rather than let a failed read take the whole server down.
          if (!existsSync(file)) { res.statusCode = 503; res.setHeader('Retry-After', '5'); return res.end(); }
        }
        res.setHeader('Content-Type', TYPES[extname(file)] || 'application/octet-stream');
        res.setHeader('Cache-Control', asset && path.startsWith('assets/')
          ? 'public, max-age=31536000, immutable' : 'no-cache');
        const stream = createReadStream(file);
        stream.on('error', () => {
          if (!res.headersSent) res.statusCode = 503;
          res.end();
        });
        stream.pipe(res);
      });
    },
  };
}

/**
 * The site root opens the ticket tool's front door for now. Answered by the server, in
 * development and in a preview alike, so the browser goes straight there.
 * The query string travels along; nothing else is redirected.
 */
function rootToTicketMain(): Plugin {
  const redirect: Connect.NextHandleFunction = (req, res, next) => {
    const url = req.url || '/';
    if ((req.method !== 'GET' && req.method !== 'HEAD') || !/^\/(?:\?|$)/.test(url)) return next();
    res.statusCode = 302;
    res.setHeader('Location', '/ticketmain' + url.slice(1));
    res.setHeader('Cache-Control', 'no-cache');
    res.end();
  };
  return {
    name: 'plink-root-to-ticket-main',
    configureServer(server) { server.middlewares.use(redirect); },
    configurePreviewServer(server) { server.middlewares.use(redirect); },
  };
}

/**
 * Each front door's title, description and Open Graph card, written into the page by the
 * server: a messenger drawing a shared address reads the HTML and never runs the app.
 */
function frontDoorHead(): Plugin {
  return {
    name: 'plink-front-door-head',
    // Development: the dev server's index.html, for the address that asked for it.
    transformIndexHtml(html, ctx) {
      return ctx.originalUrl ? withFrontDoorHead(html, ctx.originalUrl) : html;
    },
    // A preview (stage, production) serves the built page as is, so write it in here.
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url || '/';
        const page = join(dist, 'index.html');
        if ((req.method !== 'GET' && req.method !== 'HEAD') || !frontDoorOf(url) || !existsSync(page)) return next();
        res.setHeader('Content-Type', TYPES['.html']);
        res.setHeader('Cache-Control', 'no-cache');
        res.end(withFrontDoorHead(readFileSync(page, 'utf8'), url));
      });
    },
  };
}

export default defineConfig({
  root: import.meta.dirname,
  plugins: [react(), rootToTicketMain(), frontDoorHead(), ticketApp()],
  resolve: { alias: { '@shared': shared } },
  publicDir: '../../public',
  // This empties dist/, ticket app included: npm run build builds this app first.
  build: { outDir: dist, emptyOutDir: true },
  server: {
    port: 3000,
    strictPort: true,
    allowedHosts,
    proxy: {
      ...backendProxy,
      // The ticket app runs as its own dev server; ws carries its hot reload.
      // The ticket dev server lives under /tickets/ and answers /tickets itself with a 404.
      '^/tickets(?:[/?#]|$)': {
        target: ticketDev, changeOrigin: true, ws: true,
        rewrite: path => path.replace(/^\/tickets(?=$|[?#])/, '/tickets/'),
      },
    },
  },
  preview: { proxy: backendProxy },
  appType: 'spa',
});
