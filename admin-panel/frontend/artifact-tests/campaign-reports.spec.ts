import { test, expect } from '@playwright/test'

test('built report page downloads campaign and explicitly selected producer XLSX with the selected Almaty dates', async ({
  page,
  baseURL,
}, testInfo) => {
  const unexpected: string[] = []
  const exportRequests: URL[] = []
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'report-test',
        email: 'report@example.invalid',
        name: 'Тест HQ',
        role: 'HQ_HEAD',
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
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (
      url.origin !== new URL(baseURL!).origin ||
      url.pathname.startsWith('/src/') ||
      url.pathname.startsWith('/@')
    ) {
      unexpected.push(request.url())
      return route.abort()
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (request.method() === 'GET' && url.pathname === '/api/admin/access/me')
      return route.fulfill({
        json: {
          managed: true,
          version: 1,
          grants: [],
          roleCeiling: 'HQ_HEAD',
          canViewAccounts: false,
          canManageAccounts: false,
          canViewMerchInventory: false,
          canExportCampaignReports: true,
          canManageCampaignProducer: false,
        },
      })
    if (request.method() === 'GET' && url.pathname === '/api/admin/reports/producers')
      return route.fulfill({
        json: {
          producers: [
            {
              id: 'demo-producer',
              name: 'Демо-производитель',
              active: true,
              profileComplete: true,
              activeBindingCount: 1,
            },
          ],
          unboundCampaigns: [
            { id: 'unbound-demo', title: 'Демо без подтверждённого производителя' },
          ],
          notice: 'Бренд не является доказательством связи.',
        },
      })
    if (request.method() === 'GET' && url.pathname === '/api/admin/promo') {
      return route.fulfill({
        json: [
          {
            id: 'demo-report',
            title: 'DEMO · Тестовая кампания',
            brand: 'DEMO',
            status: 'archived',
          },
        ],
      })
    }
    if (
      request.method() === 'GET' &&
      [
        '/api/admin/reports/campaigns/demo-report/xlsx',
        '/api/admin/reports/producers/demo-producer/xlsx',
      ].includes(url.pathname)
    ) {
      expect(request.headers().authorization).toBe('Bearer synthetic-token')
      exportRequests.push(url)
      return route.fulfill({
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        headers: { 'Cache-Control': 'no-store' },
        body: 'SYNTHETIC XLSX TRANSPORT FIXTURE',
      })
    }
    unexpected.push(`${request.method()} ${url.pathname}`)
    return route.abort()
  })
  await page.goto('/dashboard/reports')
  await expect(page.getByRole('heading', { name: 'Отчёты производителю' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Excel: кампания' })).toBeDisabled()
  await expect(page.getByRole('button', { name: /PDF/ })).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Кампания' }).selectOption('demo-report')
  await page.getByLabel('Начало периода', { exact: true }).fill('2026-09-01')
  await page.getByLabel('Окончание периода', { exact: true }).fill('2026-09-30')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Excel: кампания' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('campaign-report-2026-09-01-2026-09-30.xlsx')
  expect(exportRequests).toHaveLength(1)
  expect(exportRequests[0].searchParams.get('from')).toBe('2026-09-01')
  expect(exportRequests[0].searchParams.get('to')).toBe('2026-09-30')
  const producer = page.getByRole('combobox', { name: 'Производитель отчёта' })
  await expect(producer).toHaveValue('')
  await expect(page.getByRole('button', { name: 'Excel: производитель' })).toBeDisabled()
  await producer.selectOption('demo-producer')
  await page.getByLabel('Начало сводного периода', { exact: true }).fill('2026-09-01')
  await page.getByLabel('Окончание сводного периода', { exact: true }).fill('2026-09-30')
  await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Excel: производитель' }).click(),
  ])
  expect(exportRequests).toHaveLength(2)
  expect(exportRequests[1].searchParams.get('from')).toBe('2026-09-01')
  expect(exportRequests[1].searchParams.get('to')).toBe('2026-09-30')
  await page.getByText('Без подтверждённого производителя: 1').click()
  await expect(page.getByText('Демо без подтверждённого производителя')).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('campaign-producer-xlsx-reports.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(unexpected).toEqual([])
})

test('built direct report route denies a brand manager without issuing report requests', async ({
  page,
  baseURL,
}) => {
  const apiRequests: string[] = []
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'brand-test',
        email: 'brand@example.invalid',
        name: 'Тест бренда',
        role: 'BRAND_MANAGER',
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
  })
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (
      url.origin === new URL(baseURL!).origin &&
      route.request().method() === 'GET' &&
      url.pathname === '/api/admin/access/me'
    )
      return route.fulfill({
        json: {
          managed: false,
          version: null,
          grants: [],
          roleCeiling: 'BRAND_MANAGER',
          canViewAccounts: false,
          canManageAccounts: false,
          canViewMerchInventory: false,
        },
      })
    if (url.origin !== new URL(baseURL!).origin || url.pathname.startsWith('/api/')) {
      apiRequests.push(url.pathname)
      return route.abort()
    }
    return route.continue()
  })
  await page.goto('/dashboard/reports')
  await expect(page.getByText('Нет доступа к отчётам производителя')).toBeVisible()
  expect(apiRequests).toEqual([])
})

test('HQ creates a business manufacturer then explicitly binds and retracts campaign provenance with CAS; no user or automatic binding is created', async ({
  page,
  baseURL,
}, testInfo) => {
  const blocked: string[] = []
  const writes: { method: string; path: string; body: Record<string, unknown> }[] = []
  let created = false
  let revision = 0
  let bindings: Record<string, unknown>[] = []
  await page.setViewportSize({ width: 1600, height: 1100 })
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'hq-demo',
        email: 'hq@example.test',
        name: 'Демо HQ',
        role: 'HQ_HEAD',
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
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    expect(request.headers().authorization).toBe('Bearer synthetic-token')
    if (request.method() !== 'GET') {
      const body = request.postDataJSON()
      writes.push({ method: request.method(), path, body })
      if (path === '/api/admin/promo/producers') {
        expect(body).toEqual({
          requestId: expect.stringMatching(/^[a-f0-9-]{36}$/),
          name: 'Демо Компания',
          reference: 'DEMO-company-reference',
        })
        created = true
        return route.fulfill({
          json: { id: 'producer-demo', name: 'Демо Компания', profileComplete: true },
        })
      }
      if (path === '/api/admin/promo/campaign-demo/producer' && request.method() === 'PUT') {
        expect(body).toEqual({
          expectedRevision: 0,
          producerId: 'producer-demo',
          wholeHistory: true,
          from: null,
          to: null,
          reference: 'DEMO-binding-reference',
        })
        revision = 1
        bindings = [
          {
            id: 'binding-demo',
            producerId: 'producer-demo',
            producerName: 'Демо Компания',
            revision: 1,
            wholeHistory: true,
            from: null,
            to: null,
            verifiedAt: '2026-10-29T00:00:00Z',
            retractionRevision: null,
            retractedAt: null,
          },
        ]
        return route.fulfill({ json: { promoId: 'campaign-demo', revision, bindings } })
      }
      if (path.endsWith('/producer/retractions')) {
        expect(body).toEqual({
          expectedRevision: 1,
          bindingId: 'binding-demo',
          reference: 'DEMO-retraction-reference',
        })
        revision = 2
        bindings = [{ ...bindings[0], retractionRevision: 2, retractedAt: '2026-10-29T01:00:00Z' }]
        return route.fulfill({ json: { promoId: 'campaign-demo', revision, bindings } })
      }
    }
    if (path === '/api/admin/access/me')
      return route.fulfill({
        json: {
          managed: true,
          roleCeiling: 'HQ_HEAD',
          version: 1,
          grants: [],
          canViewAccounts: false,
          canManageAccounts: false,
          canExportCampaignReports: true,
          canManageCampaignProducer: true,
        },
      })
    if (path === '/api/admin/promo')
      return route.fulfill({
        json: [
          { id: 'campaign-demo', title: 'Демо Кампания', brand: 'Демо бренд', status: 'active' },
        ],
      })
    if (path === '/api/admin/reports/producers')
      return route.fulfill({
        json: {
          producers: created
            ? [
                {
                  id: 'producer-demo',
                  name: 'Демо Компания',
                  active: true,
                  profileComplete: true,
                  activeBindingCount: revision === 1 ? 1 : 0,
                },
              ]
            : [],
          unboundCampaigns: revision === 1 ? [] : [{ id: 'campaign-demo', title: 'Демо Кампания' }],
          notice: 'Только явные связи.',
        },
      })
    if (path === '/api/admin/promo/campaign-demo/producer')
      return route.fulfill({ json: { promoId: 'campaign-demo', revision, bindings } })
    blocked.push(path)
    return route.abort()
  })
  await page.goto('/dashboard/reports')
  await page.getByRole('combobox', { name: 'Кампания' }).selectOption('campaign-demo')
  await page.getByRole('button', { name: 'Добавить производителя для акций' }).click()
  await page.getByLabel('Название производителя для акций').fill('Демо Компания')
  await page.getByLabel('Основание нового производителя').fill('DEMO-company-reference')
  await page.getByRole('checkbox', { name: /Я проверил организацию/ }).check()
  await page.getByRole('button', { name: 'Добавить производителя', exact: true }).click()
  await expect(page.getByRole('combobox', { name: 'Производитель отчёта' })).toHaveValue(
    'producer-demo',
  )
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: 'Подтвердить связь с производителем' }).click()
  await expect(page.getByRole('combobox', { name: 'Производитель связи кампании' })).toHaveValue('')
  await expect(page.getByRole('button', { name: 'Подтвердить изменение связи' })).toBeDisabled()
  await page
    .getByRole('combobox', { name: 'Производитель связи кампании' })
    .selectOption('producer-demo')
  await page.getByRole('checkbox', { name: 'Явно подтвердить всю историю кампании' }).check()
  await page.getByLabel('Основание связи кампании').fill('DEMO-binding-reference')
  await page.getByRole('checkbox', { name: /Я проверил производителя/ }).check()
  await page.getByRole('button', { name: 'Подтвердить изменение связи' }).click()
  await expect(page.getByText('Вся история кампании · Действует')).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('campaign-producer-verified-binding.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: 'Отозвать связь' }).click()
  await page.getByLabel('Основание связи кампании').fill('DEMO-retraction-reference')
  await page.getByRole('checkbox', { name: /Я проверил производителя/ }).check()
  await page.getByRole('button', { name: 'Подтвердить изменение связи' }).click()
  await expect(page.getByText('Вся история кампании · Отозвана')).toBeVisible()
  expect(writes).toHaveLength(3)
  expect(
    await page.evaluate(() =>
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ),
  ).not.toMatch(/DEMO-(?:company|binding|retraction)-reference/)
  expect(blocked).toEqual([])
})
