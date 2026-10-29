import { test as base, expect } from '@playwright/test'

const pharmacist = {
  id: 'artifact-staff', name: 'Тестовый сотрудник', iin: '001201000001',
  phone: '+77010000000', pharmacyId: 'artifact-pharmacy', pharmacyName: 'Тестовая аптека',
  city: 'Алматы', tier: 'Silver', status: 'active', hasPassword: true,
  receipts30d: 0, rulesAccepted30d: 0, earned30d: 0, balance: 0,
  coursesDone: 0, coursesTotal: 0, joinedAt: '2026-10-21',
  createdAt: '2026-10-21T00:00:00Z', updatedAt: '2026-10-21T00:00:00Z',
  password: 'SyntheticPasswordMustNotBeRendered!',
  passwordHash: '$2b$12$syntheticHashMustNotBeRendered',
}
const rawDiagnostic = 'unknown raw payload <script>unsafe()</script>'
const connectedDevices = ['authenticated', 'database_unavailable', rawDiagnostic].map((staffAuthStatus, index) => ({
  deviceId: `artifact-cash-${index}`, pharmacyId: 'artifact-pharmacy',
  pharmacyName: 'Тестовая аптека', pharmacyAddress: 'Тестовый адрес',
  pharmacyCity: 'Алматы', pharmacyStreetAddress: 'Тестовый адрес',
  monitorCount: 2, hasClientScreen: true, appVersion: '1.0.71.0',
  lastSeen: '2026-10-21T10:00:00Z', staffAuthStatus,
}))
const getFixtures = new Map<string, unknown>([
  ['/api/admin/access/me', { managed: false, version: null, grants: [], roleCeiling: 'HQ_HEAD', canViewAccounts: false, canManageAccounts: false, canViewMerchInventory: false }],
  ['/api/admin/pharmacists', [pharmacist]],
  ['/api/admin/pharmacies', []],
  ['/api/admin/training/preferences', []],
  ['/api/admin/training/dashboard', { capabilities: { canManagePreferences: true, canManageAssignments: true } }],
  ['/api/admin/screens/connected', { total: 3, devices: connectedDevices }],
  ['/api/admin/screens/connected/summary', { total: 3, observedAt: '2026-10-21T10:00:00Z' }],
  ['/api/admin/screens/coverage', { activePharmacies: 1, provisionedPharmacies: 1,
    onlinePharmacies: 1, onlineRegisters: 3, unprovisionedPharmacies: [], observedAt: '2026-10-21T10:00:00Z' }],
  ['/api/admin/screens/broadcast/profiles', [
    { id: 'pl_broadcast', name: 'Общий эфир', defaultProfile: true, assignedPharmacies: 0 },
    { id: 'pl_broadcast_targeted', name: 'Индивидуальный эфир', defaultProfile: false, assignedPharmacies: 0 },
  ]],
  ['/api/admin/screens/broadcast/profiles/pl_broadcast', {
    id: 'pl_broadcast', name: 'Общий эфир', defaultProfile: true, assignedPharmacyIds: [], slides: [],
  }],
])

const test = base.extend<{ blockedRequests: string[] }>({
  blockedRequests: [async ({ page, baseURL }, use) => {
    const blockedRequests: string[] = []
    const origin = new URL(baseURL!).origin
    await page.addInitScript(() => {
      localStorage.setItem('epharm.auth.user', JSON.stringify({ id: 'artifact-hq', email: 'hq@example.invalid', name: 'Тест HQ', role: 'HQ_HEAD', company: 'Тест' }))
      localStorage.setItem('epharm.auth.tokens', JSON.stringify({ accessToken: 'synthetic-token', refreshToken: 'synthetic-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' }))
    })
    await page.route('**/*', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.origin !== origin || url.pathname.startsWith('/src/') || url.pathname.startsWith('/@')) {
        blockedRequests.push(`${request.method()} ${request.url()}`)
        return route.abort('blockedbyclient')
      }
      if (!url.pathname.startsWith('/api/')) return route.continue()
      if (request.method() === 'GET' && getFixtures.has(url.pathname)) {
        return route.fulfill({ json: getFixtures.get(url.pathname) })
      }
      blockedRequests.push(`${request.method()} ${request.url()}`)
      return route.abort('blockedbyclient')
    })
    await use(blockedRequests)
    expect(blockedRequests, 'Unknown API calls, writes, external assets and source modules remain denied').toEqual([])
  }, { auto: true }],
})

test('built pharmacist registry shows IIN without exposing credentials', async ({ page }) => {
  await page.goto('/pharmacists')
  const table = page.getByTestId('pharmacists-table')
  await expect(table.getByRole('columnheader', { name: 'ИИН', exact: true })).toBeVisible()
  await expect(table.getByRole('cell', { name: '001201000001', exact: true })).toBeVisible()
  await expect(table.getByRole('button', { name: 'Сменить пароль', exact: true })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: /пароль/i })).toHaveCount(0)
  expect(await page.locator('body').textContent()).not.toContain(pharmacist.password)
  expect(await page.locator('body').textContent()).not.toContain(pharmacist.passwordHash)
  await page.getByPlaceholder('Поиск по имени, аптеке, ИИН, телефону').fill('001201000001')
  await expect(page.getByTestId('pharmacist-row-artifact-staff')).toBeVisible()
  await page.screenshot({ path: 'test-results/artifact/staff-registry.png', fullPage: true })
})

test('built connected screens explain RPS status without raw diagnostics', async ({ page }) => {
  await page.goto('/screens')
  const toggle = page.getByTestId('connected-registers-toggle')
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.click()
  await expect(page.getByTestId('connected-artifact-cash-0')).toContainText('Вход РПС: вход подтверждён')
  await expect(page.getByTestId('connected-artifact-cash-1')).toContainText('Вход РПС: нет доступа к базе Стандарт‑Н')
  await expect(page.getByTestId('connected-artifact-cash-2')).toContainText('Вход РПС: нет диагностики (обновите POSM)')
  expect(await page.locator('body').textContent()).not.toContain(rawDiagnostic)
  await expect(page.getByTestId('connected-export-excel')).toBeEnabled()
  await page.screenshot({ path: 'test-results/artifact/staff-diagnostics.png', fullPage: true })
})
