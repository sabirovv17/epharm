import { test, expect } from './fixtures'

// Local verification may opt into installed Chrome when Playwright's versioned
// headless shell is absent; CI keeps the project-configured browser.
test.use(process.env.EPHARM_USE_SYSTEM_CHROME === '1' ? { channel: 'chrome' } : {})

const products = [
  {
    id: 'trigger-product',
    name: 'Длинное название товара-триггера',
    brand: 'Бренд',
    vendor: 'Производитель',
    mnn: 'МНН',
    price: 1200,
  },
  {
    id: 'offer-product',
    name: 'Длинное название рекомендуемого товара',
    brand: 'Бренд',
    vendor: 'Производитель',
    mnn: 'МНН',
    price: 1500,
  },
]

const rules = [
  {
    id: 'responsive-rule',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'trigger-product' },
    recommend: 'offer-product',
    bonus: 200,
    script: '',
    advantages: [],
    abTest: null,
    card: null,
    pharmacies: 2,
    impressions: 10,
    accepts: 2,
    convRate: 20,
    revenue: 3000,
    payout: 400,
    createdBy: 'artifact-user',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  },
]

async function openRules(page: import('@playwright/test').Page) {
  await page.route('**/api/admin/rules**', (route) => route.fulfill({ json: rules }))
  await page.route('**/api/admin/catalog/products**', (route) => route.fulfill({ json: products }))
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill('artifact@example.invalid')
  await page.getByLabel(/Пароль/i).fill('artifact-test-only')
  await page.getByRole('button', { name: /^Войти$/ }).click()
  await expect(page).toHaveURL(/\/rules$/)
  await expect(page.getByTestId('rule-row-responsive-rule')).toBeVisible()
}

async function expectNoDocumentOverflow(page: import('@playwright/test').Page) {
  const size = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
  }))
  expect(size.document).toBeLessThanOrEqual(size.viewport + 1)
}

test('HQ shell and Rules remain contained on an ordinary laptop viewport', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1094, height: 856 })
  await openRules(page)
  await expectNoDocumentOverflow(page)

  const row = page.getByTestId('rule-row-responsive-rule')
  await expect(row.getByText('Длинное название товара-триггера')).toBeVisible()
  await expect(row.getByText('20.0%')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('hq-rules-laptop.png'), fullPage: true })
})

test('390px HQ uses a closing drawer and resets it after navigation', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openRules(page)
  await expectNoDocumentOverflow(page)

  await page.getByRole('button', { name: 'Открыть основную навигацию' }).click()
  const navigation = page.locator('aside[aria-label="Основная навигация"]')
  await expect(navigation).toHaveCSS('visibility', 'visible')
  await page.getByRole('button', { name: 'Аптеки и товары' }).click()
  await page.getByRole('button', { name: 'Сеть аптек' }).click()
  await expect(page).toHaveURL(/\/pharmacies$/)
  await expect(navigation).toHaveCSS('visibility', 'hidden')
  await expectNoDocumentOverflow(page)
  await page.screenshot({ path: testInfo.outputPath('hq-pharmacies-mobile.png'), fullPage: true })
})
