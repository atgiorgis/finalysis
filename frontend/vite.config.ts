import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Requests to /api are forwarded to the Spring Boot backend during development,
// so the frontend can call relative URLs without any CORS setup.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
