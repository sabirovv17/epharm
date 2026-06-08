// Playwright config — E2E тесты против реального backend + frontend.
//
// Pre-req: docker compose up + ./gradlew bootRun должны быть запущены.
// `webServer` блок поднимает frontend dev-server автоматически.
// Backend (Spring Boot) поднимается вручную из-за gradle / JDK зависимости —
// в CI его будут запускать отдельным step'ом.

import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  // Сброс dev-БД к seed-базису перед прогоном → детерминированное состояние.
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false, // мутации сидируют общий backend — лучше последовательно
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1, // backend single-instance — параллелизм ломает state
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },

  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    // Чистим localStorage между тестами в fixture (см. e2e/fixtures.ts).
    storageState: undefined,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
