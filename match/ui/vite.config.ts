import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 80,
    proxy: {
      '/ws': {
        target: 'ws://localhost:8081',
        ws: true,
      },
      '/order': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/admin': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 80,
    allowedHosts: ['trade.initex.io', 'localhost'],
    proxy: {
      '/ws': {
        target: 'ws://localhost:8081',
        ws: true,
      },
      '/order': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/admin': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
