import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/health': {
        target: 'http://localhost:3000',
      },
      '/api': {
        target: 'http://localhost:3000',
      },
      '/control': {
        target: 'ws://localhost:3000',
        ws: true,
      },
      '/audio': {
        target: 'ws://localhost:3000',
        ws: true,
      },
      '/recordings': {
        target: 'http://localhost:3000',
      },
      '/photos': {
        target: 'http://localhost:3000',
      },
      '/updates': {
        target: 'http://localhost:3000',
      },
      '/vendor': {
        target: 'http://localhost:3000',
      },
    },
  },
})
