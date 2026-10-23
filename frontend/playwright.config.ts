import { defineConfig, devices } from '@playwright/test'

/**
 * The layout check (UI-07, docs/plans/phase-7.md §7.2): every screen at phone width (375 px) and at desktop width, in
 * Chromium only, against the Vite dev server with the API answered from fixtures (`e2e/mockApi.ts`). Run with
 * `npm run test:layout`, beside `npm test`.
 */
const PORT = 5174

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    ...devices['Desktop Chrome'],
  },
  projects: [
    { name: 'phone', use: { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
    { name: 'desktop', use: { viewport: { width: 1280, height: 900 } } },
  ],
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
})
