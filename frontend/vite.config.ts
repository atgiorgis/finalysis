import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * Corgi's avatar image, if one has been placed in public/ (corgi-avatar.png, .jpg or .svg): CorgiAvatar shows it,
 * cropped to a circle, instead of its own drawing. Checked when Vite starts, so no page probes for a missing file.
 */
const corgiAvatar = ['png', 'jpg', 'svg']
  .map((ext) => `corgi-avatar.${ext}`)
  .find((name) => existsSync(fileURLToPath(new URL(`./public/${name}`, import.meta.url))))

// Requests to /api are forwarded to the Spring Boot backend during development (bound to 127.0.0.1, UI-08),
// so the frontend can call relative URLs without any CORS setup.
export default defineConfig({
  plugins: [react()],
  define: {
    __CORGI_AVATAR__: JSON.stringify(corgiAvatar ? `/${corgiAvatar}` : null),
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    // The layout check (e2e/, Playwright) runs on its own: npm run test:layout.
    include: ['src/**/*.test.{ts,tsx}'],
    // Every run also writes its results, failed tests named, to test-reports/vitest.json (git-ignored; Playwright
    // empties test-results/ on each layout run), so a failure that doesn't repeat can still be matched later.
    reporters: ['default', ['json', { outputFile: 'test-reports/vitest.json' }]],
  },
})
