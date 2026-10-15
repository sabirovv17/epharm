import { defineConfig, devices } from '@playwright/test'

const suppliedBaseUrl = process.env.ADMIN_ARTIFACT_BASE_URL
const baseURL = suppliedBaseUrl || 'http://127.0.0.1:4174'
const parsedBaseUrl = new URL(baseURL)
if (!['http:', 'https:'].includes(parsedBaseUrl.protocol) || parsedBaseUrl.username || parsedBaseUrl.password) {
  throw new Error('ADMIN_ARTIFACT_BASE_URL must be an HTTP(S) URL without credentials')
}

export default defineConfig({
  testDir: './artifact-tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  timeout: 25_000,
  expect: { timeout: 5_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/artifact', open: 'never' }]],
  outputDir: 'test-results/artifact',
  use: {
    baseURL,
    serviceWorkers: 'block',
    storageState: { cookies: [], origins: [] },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium-artifact', use: { ...devices['Desktop Chrome'] } }],
  // No backend, global reset, or dev server: exercise exactly the built assets.
  webServer: suppliedBaseUrl ? undefined : {
    command: 'npm run preview -- --host 127.0.0.1 --port 4174 --strictPort',
    url: baseURL,
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
