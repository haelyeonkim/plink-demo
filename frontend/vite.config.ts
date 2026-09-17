import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backend = process.env.BACKEND_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    strictPort: true,
    allowedHosts: ['lyuni.ddak.app', 'plink.ddak.app'],
    proxy: {
      '/oauth2': { target: backend, changeOrigin: true },
      '/login/oauth2': { target: backend, changeOrigin: true },
      '/api': {
        target: backend,
        changeOrigin: true,
      },
      // ws: the live channel is an upgrade, and the dev server has to carry it through
      // rather than answering with index.html.
      '/ws': {
        target: backend,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  appType: 'spa',
})
