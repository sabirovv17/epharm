import { expect, test, type Page, type Route } from '@playwright/test'

const access = 'synthetic_retired_staff_qr_00000000000000000000000'
const session = 'synthetic_retired_staff_session_000000000000000000'
const sessionKey = 'epharm.merch.staff-session.v1'

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

test('built public portal keeps producer local and sends pharmacist to the canonical personal cabinet', async ({ page, baseURL }) => {
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    calls.push(new URL(route.request().url()).pathname)
    return route.abort('blockedbyclient')
  })
  await page.setViewportSize({ width: 390, height: 900 })
  await page.goto('/merch/portal')
  await expect(page.getByRole('heading', { name: 'Выберите рабочую область' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Кабинет производителя' })).toHaveAttribute('href', '/producer/login')
  await expect(page.getByRole('link', { name: 'Личный кабинет фармацевта' })).toHaveAttribute('href', 'https://epharm.inkar.kz/learn')
  await expect(page.getByRole('link', { name: 'Вход HQ' })).toHaveAttribute('href', 'https://epharm.inkar.kz/login')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  expect(calls).toEqual([]); expect(blocked).toEqual([])
})

for (const [label, suffix, notice] of [
  ['plain entry', '', 'Вход по общему коду отключён'],
  ['old access QR', `?access=${access}`, 'Вход по общему коду отключён'],
  ['fragment access QR', `#access=${access}`, 'Вход по общему коду отключён'],
  ['mixed authority', `?task=${session}#access=${access}`, 'Ссылка недействительна'],
  ['drained old claim', `#claim=synthetic_claim_000000&grant=${access}`, 'Подтверждение задания устарело'],
] as const) {
  test(`built retired staff entry consumes ${label} without code/session API calls`, async ({ page, baseURL }) => {
    const calls: string[] = []
    const blocked = await isolate(page, baseURL!, async (route) => {
      calls.push(new URL(route.request().url()).pathname)
      return route.abort('blockedbyclient')
    })
    await page.addInitScript(({ key, token }) => {
      sessionStorage.setItem(key, token)
      sessionStorage.setItem('unrelated-personal-state', 'keep')
    }, { key: sessionKey, token: session })
    await page.goto(`/staff${suffix}`)
    await expect(page.getByRole('heading', { name: notice })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Личный кабинет фармацевта' })).toHaveAttribute('href', 'https://epharm.inkar.kz/learn')
    await expect(page.getByLabel('Код доступа', { exact: true })).toHaveCount(0)
    expect(new URL(page.url()).search + new URL(page.url()).hash).toBe('')
    expect(await page.evaluate(key => sessionStorage.getItem(key), sessionKey)).toBeNull()
    expect(await page.evaluate(() => sessionStorage.getItem('unrelated-personal-state'))).toBe('keep')
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Вход по общему коду отключён' })).toBeVisible()
    expect(calls).toEqual([]); expect(blocked).toEqual([])
  })
}

test('built legacy task QR still reaches its existing personal identity boundary', async ({ page, baseURL }) => {
  const task = 'synthetic_task_capability_00000000000000000000000'
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    const path = new URL(route.request().url()).pathname
    calls.push(path)
    expect(path).toBe('/api/public/merch/tasks/session')
    expect(route.request().headers()['x-merch-task-token']).toBe(task)
    expect(page.url()).not.toContain(task)
    return route.fulfill({ json: { dispatchId: 'synthetic-task', rewardAmountKzt: 0,
      identityRequired: true, readOnly: false, expiresAt: '2099-01-01T00:00:00Z' } })
  })
  await page.goto(`/merch/staff?task=1#task=${task}`)
  await expect(page.getByRole('heading', { name: 'Подтвердите исполнителя' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Войти и открыть задание' })).toHaveAttribute('href', '/learn/merch/tasks/synthetic-task')
  expect(new URL(page.url()).pathname).toBe('/tasks/staff')
  expect(calls).toEqual(['/api/public/merch/tasks/session']); expect(blocked).toEqual([])
})

test('built direct administrator code route is retired even for a previous code-view profile', async ({ page, baseURL }) => {
  const calls: string[] = []
  const blocked = await isolate(page, baseURL!, async (route) => {
    const request = route.request(); const path = new URL(request.url()).pathname
    calls.push(path)
    if (path === '/api/admin/auth/login' && request.method() === 'POST') return route.fulfill({ json: {
      user: { id: 'synthetic-staff-admin', name: 'Синтетический HQ', email: 'staff-admin@example.invalid', role: 'HQ_HEAD', company: 'DEMO' },
      tokens: { accessToken: 'synthetic-staff-admin-token', refreshToken: 'synthetic-staff-admin-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' },
    } })
    expect(path).toBe('/api/admin/access/me')
    return route.fulfill({ json: { managed: true, version: 1, roleCeiling: 'HQ_HEAD',
      canViewMerchStaffAccess: true, canViewMerchTasks: false, canViewMerchInventory: false,
      grants: [{ module: 'merchandising', subsection: 'staff_access', action: 'view',
        allPharmacies: false, pharmacyIds: [], allMerchBranches: true, merchBranchIds: [] }],
    } })
  })
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill('staff-admin@example.invalid')
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-password')
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page).toHaveURL(/\/merch\/staff-access$/)
  await expect(page.getByRole('heading', { name: 'Общие коды доступа отключены' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Коды доступа', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Создать код и QR', exact: true })).toHaveCount(0)
  expect(calls.every(path => ['/api/admin/auth/login', '/api/admin/access/me'].includes(path))).toBe(true)
  expect(blocked).toEqual([])
})
