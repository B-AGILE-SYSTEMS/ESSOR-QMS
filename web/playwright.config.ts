import { defineConfig } from '@playwright/test';

const PORT = 4300;

/** E2E contre une API simulée (e2e/mock-api.ts) : aucun back-end requis. */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    viewport: { width: 1440, height: 900 },
    locale: 'fr-FR',
    timezoneId: 'Africa/Casablanca',
    // Chrome du poste (CHROME_BIN) ou Chromium de Playwright (`npx playwright install chromium`).
    launchOptions: process.env['CHROME_BIN'] ? { executablePath: process.env['CHROME_BIN'] } : {},
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npx ng serve --port ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env['CI'],
    timeout: 120_000,
  },
});
