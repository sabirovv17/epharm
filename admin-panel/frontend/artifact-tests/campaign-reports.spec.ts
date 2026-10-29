import { test, expect } from '@playwright/test'

test('built report page downloads a private PDF with the selected Almaty dates', async ({ page, baseURL }) => {
  const unexpected: string[] = []
  const pdfRequests: URL[] = []
  await page.addInitScript(() => {
    localStorage.setItem('epharm.auth.user', JSON.stringify({ id: 'report-test', email: 'report@example.invalid', name: 'Тест HQ', role: 'HQ_HEAD', company: 'DEMO' }))
    localStorage.setItem('epharm.auth.tokens', JSON.stringify({ accessToken: 'synthetic-token', refreshToken: 'synthetic-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' }))
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== new URL(baseURL!).origin || url.pathname.startsWith('/src/') || url.pathname.startsWith('/@')) {
      unexpected.push(request.url()); return route.abort()
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (request.method() === 'GET' && url.pathname === '/api/admin/access/me') return route.fulfill({ json: { managed: false, version: null, grants: [], roleCeiling: 'HQ_HEAD', canViewAccounts: false, canManageAccounts: false, canViewMerchInventory: false } })
    if (request.method() === 'GET' && url.pathname === '/api/admin/promo') {
      return route.fulfill({ json: [{ id: 'demo-report', title: 'DEMO · Тестовая кампания', brand: 'DEMO', status: 'archived' }] })
    }
    if (request.method() === 'GET' && url.pathname === '/api/admin/reports/campaigns/demo-report/pdf') {
      expect(request.headers().authorization).toBe('Bearer synthetic-token')
      pdfRequests.push(url)
      return route.fulfill({ contentType: 'application/pdf', headers: { 'Cache-Control': 'no-store' }, body: '%PDF-1.4\n%SYNTHETIC TRANSPORT FIXTURE\n%%EOF' })
    }
    unexpected.push(`${request.method()} ${url.pathname}`)
    return route.abort()
  })
  await page.goto('/dashboard/reports')
  await expect(page.getByRole('heading', { name: 'Отчёты производителю' })).toBeVisible()
  await page.getByRole('button', { name: 'Скачать PDF' }).click()
  await expect(page.getByRole('alert')).toContainText('Выберите кампанию')
  await expect(page.getByRole('combobox', { name: 'Кампания' })).toBeFocused()
  await page.getByRole('combobox', { name: 'Кампания' }).selectOption('demo-report')
  await page.getByLabel('Начало периода', { exact: true }).fill('2026-09-01')
  await page.getByLabel('Окончание периода', { exact: true }).fill('2026-09-30')
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Скачать PDF' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('campaign-report-2026-09-01-2026-09-30.pdf')
  expect(pdfRequests).toHaveLength(1)
  expect(pdfRequests[0].searchParams.get('from')).toBe('2026-09-01')
  expect(pdfRequests[0].searchParams.get('to')).toBe('2026-09-30')
  await expect(page.getByRole('status')).toContainText('PDF сформирован')
  await page.screenshot({ path: 'test-results/artifact/campaign-reports.png', fullPage: true })
  expect(unexpected).toEqual([])
})

test('built direct report route denies a brand manager without issuing report requests', async ({ page, baseURL }) => {
  const apiRequests: string[] = []
  await page.addInitScript(() => {
    localStorage.setItem('epharm.auth.user', JSON.stringify({ id: 'brand-test', email: 'brand@example.invalid', name: 'Тест бренда', role: 'BRAND_MANAGER', company: 'DEMO' }))
    localStorage.setItem('epharm.auth.tokens', JSON.stringify({ accessToken: 'synthetic-token', refreshToken: 'synthetic-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' }))
  })
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.origin === new URL(baseURL!).origin && route.request().method() === 'GET' && url.pathname === '/api/admin/access/me') return route.fulfill({ json: { managed: false, version: null, grants: [], roleCeiling: 'BRAND_MANAGER', canViewAccounts: false, canManageAccounts: false, canViewMerchInventory: false } })
    if (url.origin !== new URL(baseURL!).origin || url.pathname.startsWith('/api/')) {
      apiRequests.push(url.pathname); return route.abort()
    }
    return route.continue()
  })
  await page.goto('/dashboard/reports')
  await expect(page.getByText('Нет доступа к отчётам производителя')).toBeVisible()
  expect(apiRequests).toEqual([])
})
