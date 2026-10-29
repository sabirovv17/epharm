import { expect, test, type Page } from '@playwright/test'

async function fixture(page: Page, baseURL: string, allowed: boolean, role = 'HQ_HEAD') {
  const writes: unknown[] = []
  const blocked: string[] = []
  const branch = {
    id: 'demo-branch-01',
    organizationId: 'demo-org',
    organizationName: 'Демо-сеть',
    code: '01',
    name: 'Демо-аптека 01',
    address: 'Демо-адрес, 1',
    city: 'Демо-город',
    division: null,
    active: true,
    epharmPharmacyId: null,
    source: { system: 'crm', origin: 'demo', id: 'demo-branch-01' },
  }
  await page.addInitScript(
    ({ role }) => {
      localStorage.setItem(
        'epharm.auth.user',
        JSON.stringify({
          id: 'demo-navigation-user',
          email: 'demo@example.test',
          name: 'Демо HQ',
          role,
          company: 'DEMO',
        }),
      )
      localStorage.setItem(
        'epharm.auth.tokens',
        JSON.stringify({
          accessToken: 'synthetic-token',
          refreshToken: 'synthetic-refresh',
          accessTokenExpiresAt: '2099-01-01T00:00:00Z',
          refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
        }),
      )
    },
    { role },
  )
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (
      url.origin !== new URL(baseURL).origin ||
      url.pathname.startsWith('/src/') ||
      url.pathname.startsWith('/@')
    ) {
      blocked.push(request.url())
      return route.abort()
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (url.pathname === '/api/admin/access/me')
      return route.fulfill({
        json: {
          managed: true,
          version: 1,
          grants: [],
          roleCeiling: role,
          canViewAccounts: false,
          canManageAccounts: false,
          canViewMerchInventory: allowed,
        },
      })
    if (url.pathname === '/api/admin/merch/branches') {
      expect(allowed).toBe(true)
      return route.fulfill({ json: { items: [branch], total: 1, limit: 50, offset: 0 } })
    }
    if (url.pathname.endsWith('/inventory'))
      return route.fulfill({
        json: {
          branch,
          containers: [
            {
              id: 'demo-container',
              code: 'ST-01',
              name: 'Прикассовый стеллаж',
              type: 'shelf',
              category: null,
              position: 1,
              active: true,
            },
          ],
          shelves: [],
        },
      })
    if (url.pathname.endsWith('/availability') && request.method() === 'POST') {
      writes.push(request.postDataJSON())
      return route.fulfill({
        json: {
          branchId: branch.id,
          action: 'block',
          fromMonth: '2026-10-01',
          toMonth: '2026-10-01',
          targetCount: 1,
          changedCount: 1,
          protectedCount: 0,
          unchangedCount: 0,
          auditId: 'demo-audit',
        },
      })
    }
    if (url.pathname.endsWith('/availability')) {
      const months = Array.from({ length: 6 }, (_, i) =>
        `2026-${String(10 + i > 12 ? i - 2 : 10 + i).padStart(2, '0')}-01`.replace(
          /^2026-(0[1-3])/,
          '2027-$1',
        ),
      )
      return route.fulfill({
        json: {
          branch,
          startMonth: months[0],
          monthCount: 6,
          canManage: true,
          canViewPrices: false,
          months: months.map((month) => ({
            month,
            availableCount: 1,
            blockedCount: 0,
            heldCount: 0,
            bookedCount: 1,
          })),
          shelves: [
            {
              id: 'demo-free',
              code: 'ST-01-A',
              category: 'Прикассовая зона',
              width: 40,
              price: null,
              active: true,
              months: months.map((month) => ({
                month,
                inventoryId: null,
                status: 'available',
                reason: null,
                note: null,
                canEdit: true,
                placement: null,
              })),
            },
            {
              id: 'demo-booked',
              code: 'ST-01-B',
              category: 'Витрина',
              width: 60,
              price: null,
              active: true,
              months: months.map((month) => ({
                month,
                inventoryId: 'demo-inventory',
                status: 'booked',
                reason: null,
                note: null,
                canEdit: false,
                placement: {
                  status: 'booked',
                  portion: 'whole',
                  portionLabel: 'Целиком',
                  heldUntil: null,
                  hasAdminAssignment: true,
                  items: [
                    {
                      id: 'demo-placement',
                      requestId: 'demo-request',
                      portion: 'whole',
                      advertiserName: 'Демо-производитель',
                      origin: 'demo',
                      canRelease: false,
                    },
                  ],
                },
              })),
            },
          ],
        },
      })
    }
    return route.fulfill({
      status: 404,
      json: { code: 'NOT_FOUND', message: 'Synthetic unavailable route' },
    })
  })
  return { writes, blocked }
}

test('built unified navigation and native calendar preserve protected cells and explicit mutation', async ({
  page,
  baseURL,
}, testInfo) => {
  const { writes, blocked } = await fixture(page, baseURL!, true)
  await page.setViewportSize({ width: 1600, height: 1100 })
  await page.goto('/merch/inventory')
  const sidebar = page.getByRole('complementary', { name: 'Основная навигация' })
  for (const name of [
    'Аналитика',
    'Мотивация фармацевтов',
    'Обучение',
    'Мерчендайзинг',
    'Аптеки и товары',
    'Фармацевты',
    'Управление',
  ])
    await expect(sidebar.getByRole('button', { name, exact: true })).toBeVisible()
  await expect(sidebar.getByRole('button', { name: /Кабинет производителя/ })).toBeDisabled()
  await page.getByRole('combobox', { name: 'Аптека мерча' }).selectOption('demo-branch-01')
  await expect(page.getByLabel('Выбрать полку ST-01-B')).toBeDisabled()
  await expect(page.getByText('Цены недоступны с вашими правами.')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('unified-merch-calendar.png'), fullPage: true })
  await page.getByLabel('Выбрать полку ST-01-A').check()
  await page.getByRole('button', { name: 'Проверить изменение' }).click()
  expect(writes).toEqual([])
  await page.getByRole('button', { name: 'Подтвердить', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Изменено: 1' })).toBeVisible()
  expect(writes).toEqual([
    {
      shelfIds: ['demo-free'],
      fromMonth: '2026-10-01',
      toMonth: '2026-10-01',
      action: 'block',
      reason: 'repair',
      note: null,
    },
  ])
  expect(blocked).toEqual([])
})

test('built new scope stays denied for a legacy role without server capability', async ({
  page,
  baseURL,
}) => {
  const { writes, blocked } = await fixture(page, baseURL!, false, 'REGIONAL_MANAGER')
  await page.goto('/merch/inventory')
  await expect(page.getByRole('heading', { name: 'Мерчендайзинг недоступен' })).toBeVisible()
  const sidebar = page.getByRole('complementary', { name: 'Основная навигация' })
  await expect(sidebar.getByRole('button', { name: 'Мерчендайзинг', exact: true })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: 'Аптека мерча' })).toHaveCount(0)
  expect(writes).toEqual([])
  expect(blocked).toEqual([])
})
