import { expect, test, type Page, type Route } from '@playwright/test'

const access = 'synthetic_staff_qr_0000000000000000000000000000'
const session = 'synthetic_staff_session_000000000000000000000000'
const sessionKey = 'epharm.merch.staff-session.v1'
const scope = { sessionId: 'synthetic-session', codeId: 'synthetic-code', organizationId: 'synthetic-org',
  placementMonth: '2099-01-01', branchIds: ['synthetic-branch'], deadlineAt: null,
  sessionExpiresAt: '2099-02-01T00:00:00Z', readOnly: false }
const workspace = { month: scope.placementMonth, organization: { id: scope.organizationId, name: 'Синтетическая сеть M13' },
  deadlineAt: null, readOnly: false, branches: [{ id: 'synthetic-branch', code: '01', name: 'Синтетическая аптека',
    city: 'Демо-город', address: 'Демо-адрес', requiredCount: 1, photoCount: 0, visitId: null,
    status: null, reason: null, submittedAt: null, canStart: true }], tasks: [], limit: 50, offset: 0, hasMore: false }

async function isolate(page: Page, baseURL: string, api: (route: Route) => Promise<void>) {
  const blocked: string[] = []
  const origin = new URL(baseURL).origin
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.origin !== origin || url.pathname.startsWith('/src/') || url.pathname.startsWith('/@')) {
      blocked.push(url.pathname); return route.abort('blockedbyclient')
    }
    if (url.pathname.startsWith('/api/')) return api(route)
    return route.continue()
  })
  return blocked
}

test('built public merch role chooser keeps staff and producer on the current origin without API calls', async ({ page, baseURL }, testInfo) => {
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    calls.push(new URL(route.request().url()).pathname)
    return route.abort('blockedbyclient')
  })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.goto('/merch/portal')
  await expect(page.getByRole('heading', { name: 'Выберите рабочую область' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Кабинет производителя' })).toHaveAttribute('href', '/producer/login')
  await expect(page.getByRole('link', { name: 'Вход сотрудника' })).toHaveAttribute('href', '/staff')
  await expect(page.getByRole('link', { name: 'Вход HQ' })).toHaveAttribute('href', 'https://epharm.inkar.kz/login')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  expect(calls).toEqual([]); expect(blocked).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('merch-role-chooser-390.png'), fullPage: true })
})

test('built old staff QR exchanges only the staff realm; reload revalidates, and revoke clears the limited session', async ({ page, baseURL }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 900 })
  let revoked = false
  let workspaceReads = 0
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    calls.push(path)
    expect(page.url()).not.toContain(access)
    expect(request.headers().authorization).toBeUndefined()
    expect(request.headers().referer).toBeUndefined()
    if (path === '/api/staff/auth/qr' && request.method() === 'POST') {
      expect(request.headers()['x-staff-session']).toBeUndefined()
      expect(request.postDataJSON()).toEqual({ access })
      return route.fulfill({ json: { token: session, expiresAt: scope.sessionExpiresAt, scope } })
    }
    expect(request.headers()['x-staff-session']).toBe(session)
    if (path === '/api/staff/auth/me') return route.fulfill({ status: revoked ? 401 : 200, json: revoked ? { code: 'UNAUTHORIZED' } : scope })
    expect(path).toBe('/api/staff/workspace')
    expect(request.method()).toBe('GET')
    workspaceReads++
    return route.fulfill({ json: workspace })
  })
  await page.goto(`/merch/staff?access=${access}`)
  await expect(page.getByRole('heading', { name: workspace.organization.name })).toBeVisible()
  expect(new URL(page.url()).pathname + new URL(page.url()).search).toBe('/staff')
  expect(await page.evaluate((key) => ({ local: localStorage.length, keys: Object.keys(sessionStorage), token: sessionStorage.getItem(key) }), sessionKey))
    .toEqual({ local: 0, keys: [sessionKey], token: session })
  await page.reload()
  await expect(page.getByRole('heading', { name: workspace.organization.name })).toBeVisible()
  expect(calls.filter((path) => path.endsWith('/qr'))).toHaveLength(1)
  await page.screenshot({ path: testInfo.outputPath('staff-workspace-390.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  const readsBeforeRevoke = workspaceReads
  revoked = true
  await page.reload()
  await expect(page.getByRole('alert')).toContainText(/Доступ завершён|Сеанс завершён/)
  expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
  expect(workspaceReads).toBe(readsBeforeRevoke)
  expect(blocked).toEqual([])
})

for (const [label, suffix, notice] of [
  ['mixed authority', `?task=${session}#access=${access}`, 'Ссылка недействительна'],
  ['drained old claim', `#claim=synthetic_claim_000000&grant=${access}`, 'Подтверждение задания устарело'],
] as const) {
  test(`built staff entry consumes ${label} before any API or telemetry request`, async ({ page, baseURL }) => {
    const calls: string[] = []
    const blocked = await isolate(page, baseURL!, async (route) => {
      calls.push(new URL(route.request().url()).pathname)
      return route.abort('blockedbyclient')
    })
    await page.goto(`/staff${suffix}`)
    await expect(page.getByRole('heading', { name: notice })).toBeVisible()
    expect(new URL(page.url()).search + new URL(page.url()).hash).toBe('')
    expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
    expect(calls).toEqual([])
    expect(blocked).toEqual([])
  })
}

test('built administrator login with only staff-access view lands in codes without ordinary task or inventory APIs', async ({ page, baseURL }) => {
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    calls.push(path)
    if (path === '/api/admin/auth/login' && request.method() === 'POST') return route.fulfill({ json: {
      user: { id: 'synthetic-staff-admin', name: 'Синтетический HQ', email: 'staff-admin@example.invalid', role: 'HQ_HEAD', company: 'DEMO' },
      tokens: { accessToken: 'synthetic-staff-admin-token', refreshToken: 'synthetic-staff-admin-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' },
    } })
    expect(request.headers().authorization).toBe('Bearer synthetic-staff-admin-token')
    if (path === '/api/admin/access/me') return route.fulfill({ json: {
      managed: true, version: 1, roleCeiling: 'HQ_HEAD', canViewAccounts: false, canManageAccounts: false,
      canViewMerchStaffAccess: true, canViewMerchTasks: false, canViewMerchInventory: false,
      grants: [{ module: 'merchandising', subsection: 'staff_access', action: 'view',
        allPharmacies: false, pharmacyIds: [], allMerchBranches: true, merchBranchIds: [] }],
    } })
    expect(path).toBe('/api/admin/merch/staff-access/options')
    return route.fulfill({ json: { organizations: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill('staff-admin@example.invalid')
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-password')
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page).toHaveURL(/\/merch\/staff-access$/)
  await expect(page.getByRole('heading', { name: 'Коды доступа сотрудников' })).toBeVisible()
  expect(calls).toContain('/api/admin/access/me')
  expect(calls).toContain('/api/admin/merch/staff-access/options')
  expect(calls.every((path) => ['/api/admin/auth/login', '/api/admin/access/me', '/api/admin/merch/staff-access/options'].includes(path))).toBe(true)
  expect(blocked).toEqual([])
})
