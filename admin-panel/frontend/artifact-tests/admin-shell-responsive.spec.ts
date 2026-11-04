import { test, expect } from './fixtures'
import type { Locator, Page } from '@playwright/test'

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

test('HQ shell and Rules remain contained on an ordinary laptop viewport', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1094, height: 856 })
  await openRules(page)
  await expectNoDocumentOverflow(page)

  const row = page.getByTestId('rule-row-responsive-rule')
  await expect(row.getByText('Длинное название товара-триггера')).toBeVisible()
  await expect(row.getByText('20.0%')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('hq-rules-laptop.png'), fullPage: true })
})

test('390px HQ uses a closing drawer and resets it after navigation', async ({
  page,
}, testInfo) => {
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

test('mobile navigation contains keyboard focus, makes the workspace inert and restores its opener on Escape', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openRules(page)
  const opener = page.getByRole('button', { name: 'Открыть основную навигацию', exact: true })
  await opener.click()
  const navigation = page.locator('aside[aria-label="Основная навигация"]')
  const close = navigation.getByRole('button', { name: 'Закрыть навигацию', exact: true })
  await expect(close).toBeFocused()
  await expect(navigation).toHaveAttribute('role', 'dialog')
  await expect(navigation).toHaveAttribute('aria-modal', 'true')
  expect(await page.locator('main').evaluate((element) => !!element.closest('[inert]'))).toBe(true)
  // Inert blocks programmatic focus too, not only the ordinary Tab sequence.
  await page
    .locator('main button')
    .first()
    .evaluate((element: HTMLButtonElement) => element.focus())
  await expect(close).toBeFocused()
  const buttonCount = await navigation.getByRole('button').count()
  for (let index = 0; index < buttonCount + 2; index++) {
    await page.keyboard.press('Tab')
    expect(await navigation.evaluate((element) => element.contains(document.activeElement))).toBe(
      true,
    )
  }
  await close.focus()
  await page.keyboard.press('Shift+Tab')
  expect(await navigation.evaluate((element) => element.contains(document.activeElement))).toBe(
    true,
  )
  await expect(close).not.toBeFocused()
  await page.keyboard.press('Escape')
  await expect(navigation).toHaveCSS('visibility', 'hidden')
  await expect(opener).toBeFocused()
  expect(await page.locator('main').evaluate((element) => !!element.closest('[inert]'))).toBe(false)
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Открыть поиск', exact: true })).toBeFocused()
})

async function seedHeadquarters(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'responsive-hq',
        email: 'responsive@example.invalid',
        name: 'Тестовый HQ',
        role: 'HQ_HEAD',
        company: 'Synthetic',
      }),
    )
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-responsive-token',
        refreshToken: 'synthetic-responsive-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
      }),
    )
  })
}

async function expectLastColumnReachable(page: Page, region: Locator, lastColumn: Locator) {
  await expect(region).toBeVisible()
  await expect
    .poll(() => region.evaluate((element) => element.scrollWidth - element.clientWidth))
    .toBeGreaterThan(1)
  await region.evaluate((element) => {
    element.scrollLeft = element.scrollWidth
  })
  await expect.poll(() => region.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0)
  await expect
    .poll(async () => {
      const viewport = await region.boundingBox()
      const column = await lastColumn.boundingBox()
      return (
        !!viewport &&
        !!column &&
        column.x >= viewport.x - 1 &&
        column.x + column.width <= viewport.x + viewport.width + 1 &&
        column.x + column.width <= page.viewportSize()!.width + 1
      )
    })
    .toBe(true)
  await expectNoDocumentOverflow(page)
}

for (const width of [1094, 1440]) {
  test(`pharmacy table keeps its last column reachable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await seedHeadquarters(page)
    await page.route('**/api/admin/pharmacies', (route) =>
      route.fulfill({
        json: [
          {
            id: 'responsive-pharmacy',
            name: 'Тестовая аптека',
            chainId: 'synthetic-chain',
            chainName: 'Тестовая сеть',
            city: 'Тестовый город',
            district: '',
            addr: 'Тестовая улица, 1',
            group: 'pilot',
            pharmacists: 3,
            receipts30d: 10,
            gmv30d: 10000,
            liftPct: 12.5,
            rulesAccepted: 2,
            active: true,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ],
      }),
    )
    await page.goto('/pharmacies')
    const region = page.getByRole('region', { name: 'Таблица аптек', exact: true })
    await expectLastColumnReachable(
      page,
      region,
      region.getByRole('columnheader', { name: 'Lift', exact: true }),
    )
    await expect(region.getByRole('cell', { name: '+12.5%', exact: true })).toBeVisible()
  })
}

for (const width of [390, 768]) {
  test(`cash-register table keeps its last action reachable at ${width}px without a mutation`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 })
    await seedHeadquarters(page)
    await page.route('**/api/admin/fulfillment/**', (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/status'))
        return route.fulfill({
          json: {
            enabled: false,
            deviceRegistrationEnabled: false,
            legacyPosmKeyEnabled: false,
          },
        })
      if (path.endsWith('/orders'))
        return route.fulfill({ json: { items: [], total: 0, limit: 50, offset: 0 } })
      if (path.endsWith('/devices'))
        return route.fulfill({
          json: [
            {
              id: 'responsive-device',
              deviceId: 'SYNTHETIC-CASH-REGISTER',
              pharmacyId: 'responsive-pharmacy',
              pharmacyName: 'Тестовая аптека',
              active: true,
              secretHint: '0000',
              registeredAt: '2026-01-01T00:00:00Z',
              lastSeenAt: '2026-11-01T00:00:00Z',
              revokedAt: null,
            },
          ],
        })
      return route.fallback()
    })
    await page.goto('/fulfillment')
    await page.getByRole('button', { name: 'Кассы', exact: true }).click()
    const region = page.getByRole('region', { name: 'Таблица касс', exact: true })
    const revoke = region.getByRole('button', { name: 'Отозвать', exact: true })
    await expectLastColumnReachable(page, region, revoke)
    await expect(revoke).toBeEnabled()
  })
}

test('mobile merch cards preserve the native task drawer and learner link', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await seedHeadquarters(page)
  const task = {
    id: 'responsive-task',
    branchId: 'responsive-branch',
    title: 'Тестовый фотоотчёт',
    status: 'ready',
    priority: 'normal',
    taskType: 'photo_report',
    dueAt: null,
    sourceSystem: 'EPHARM',
    version: 1,
    rewardAmountKzt: 0,
    reward: null,
    itemCount: 0,
    pendingReviewCount: 0,
    detailsIncluded: true,
    items: [],
  }
  await page.route('**/api/admin/access/me', (route) =>
    route.fulfill({
      json: {
        managed: true,
        version: 1,
        grants: [],
        roleCeiling: 'HQ_HEAD',
        canViewMerchTasks: true,
        canCreateMerchTasks: true,
        canExecuteMerchTasks: false,
        canReviewMerchTasks: false,
        canExportMerchTasks: false,
        canCommentMerchTasks: false,
        canCancelMerchTasks: false,
      },
    }),
  )
  await page.route('**/api/admin/merch/tasks**', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const path = new URL(route.request().url()).pathname
    if (path === '/api/admin/merch/tasks')
      return route.fulfill({
        json: {
          items: [task],
          limit: 20,
          offset: 0,
          hasMore: false,
        },
      })
    if (path.endsWith('/branches'))
      return route.fulfill({
        json: [
          {
            id: 'responsive-branch',
            name: 'Тестовая аптека',
            city: 'Тестовый город',
            address: 'Тестовая улица, 1',
            active: true,
            createEligible: true,
            canConfigureReward: false,
          },
        ],
      })
    if (path.endsWith('/responsive-task')) return route.fulfill({ json: task })
    return route.fallback()
  })
  await page.goto('/merch/tasks')
  const list = page.getByRole('list', { name: 'Задания', exact: true })
  await expect(list.getByRole('listitem')).toContainText('Тестовый фотоотчёт')
  await expect(page.getByRole('region', { name: 'Таблица заданий' })).toHaveCount(0)
  const opener = list.getByRole('button', { name: 'Открыть задание', exact: true })
  await opener.click()
  const drawer = page.getByRole('dialog', { name: 'Задание мерчендайзинга', exact: true })
  await expect(drawer.getByRole('region', { name: 'Открытое задание', exact: true })).toBeVisible()
  await expect(drawer.getByRole('link', { name: 'Открыть в личном кабинете' })).toHaveAttribute(
    'href',
    '/learn/merch/tasks/responsive-task',
  )
  await expect
    .poll(async () => {
      const box = await drawer.boundingBox()
      return (
        !!box &&
        Math.abs(box.x) <= 1 &&
        Math.abs(box.y) <= 1 &&
        Math.abs(box.width - 390) <= 1 &&
        Math.abs(box.height - 844) <= 1
      )
    })
    .toBe(true)
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(opener).toBeFocused()
  await expectNoDocumentOverflow(page)
})
