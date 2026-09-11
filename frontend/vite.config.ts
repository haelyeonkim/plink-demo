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
    },
  },
  appType: 'spa',
})
