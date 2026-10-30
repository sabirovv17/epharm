import { expect, test } from '@playwright/test'

test('HQ explicitly verifies a person and binds source/pharmacist records without guessing identity or storing proofs', async ({
  page,
  baseURL,
}, testInfo) => {
  const blocked: string[] = []
  const writes: { path: string; body: Record<string, unknown> }[] = []
  let people: { personId: string; displayName: string; referenceLabel: string }[] = []
  let sourceBound = false
  const account = {
    accountId: 'account-demo',
    personId: 'person-demo',
    name: 'Демо Сотрудник',
    email: 'demo@example.test',
    jobTitle: 'Демо',
    roleCeiling: 'HQ_HEAD',
    status: 'active',
    managed: true,
    version: 1,
    primaryHqOwner: false,
    editable: true,
  }
  await page.setViewportSize({ width: 1600, height: 1100 })
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'owner-demo',
        name: 'Демо HQ',
        email: 'owner@example.test',
        role: 'HQ_HEAD',
        company: 'DEMO',
      }),
    )
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-admin',
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
    if (!path.startsWith('/api/admin/access/')) {
      blocked.push(path)
      return route.abort()
    }
    expect(request.headers().authorization).toBe('Bearer synthetic-admin')
    if (request.method() === 'POST') {
      const body = request.postDataJSON()
      writes.push({ path, body })
      expect(body.currentPassword).toBe('synthetic-current-password')
      expect(body.verificationReference).toBe('DEMO-check-2026')
      expect(body).not.toHaveProperty('grants')
      if (path.endsWith('/people')) {
        people = [
          { personId: 'person-demo', displayName: body.name, referenceLabel: 'Связь подтверждена' },
        ]
        return route.fulfill({ json: people[0] })
      }
      if (path.endsWith('/source-accounts/source-demo/bind')) {
        expect(body).toMatchObject({
          verifiedPersonId: 'person-demo',
          targetAccountId: 'account-demo',
        })
        sourceBound = true
        return route.fulfill({ json: {} })
      }
      if (path.endsWith('/pharmacist-person-links')) {
        expect(body).toMatchObject({
          verifiedPersonId: 'person-demo',
          pharmacistId: 'pharmacist-demo',
        })
        return route.fulfill({ json: {} })
      }
    }
    if (path.endsWith('/me'))
      return route.fulfill({
        json: {
          managed: true,
          roleCeiling: 'HQ_HEAD',
          version: 1,
          grants: [],
          canViewAccounts: true,
          canManageAccounts: true,
        },
      })
    if (path.endsWith('/accounts'))
      return route.fulfill({
        json: [
          account,
          {
            ...account,
            accountId: 'other-demo',
            personId: 'other-person',
            email: 'other@example.test',
          },
        ],
      })
    if (path.endsWith('/people')) return route.fulfill({ json: people })
    if (path.endsWith('/catalog'))
      return route.fulfill({
        json: { permissions: [], pharmacies: [], assignableRoles: ['REGIONAL_MANAGER'] },
      })
    if (path.endsWith('/source-accounts'))
      return route.fulfill({
        json: [
          {
            sourceAccountId: 'source-demo',
            name: account.name,
            email: account.email,
            sourceRole: 'mas',
            active: true,
            hasPassword: true,
            accountId: sourceBound ? account.accountId : null,
            personId: sourceBound ? 'person-demo' : null,
          },
        ],
      })
    if (path.endsWith('/pharmacist-options'))
      return route.fulfill({
        json: {
          items: [
            {
              id: 'pharmacist-demo',
              name: 'Демо Фармацевт',
              pharmacyName: 'Демо-аптека 01',
              personId: null,
            },
          ],
          offset: 0,
          limit: 50,
          hasMore: false,
        },
      })
    blocked.push(path)
    return route.abort()
  })
  await page.goto('/settings')
  await page.getByRole('tab', { name: 'Личности и перенос' }).click()
  await expect(page.getByText(/Проверенных личностей пока нет/)).toBeVisible()
  expect(writes).toEqual([])
  await page.getByRole('button', { name: 'Подтвердить нового человека' }).click()
  await page.getByLabel('ФИО подтверждаемого человека').fill('Демо Сотрудник')
  const proof = async () => {
    await page.getByLabel('Основание проверки личности').fill('DEMO-check-2026')
    await page.getByRole('checkbox', { name: /Я проверил личность/ }).check()
    await page
      .getByLabel('Текущий пароль подтверждения личности')
      .fill('synthetic-current-password')
  }
  await proof()
  await page.screenshot({
    path: testInfo.outputPath('verify-person-explicit-proof.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
  await page.getByRole('button', { name: 'Сопоставить аккаунт' }).click()
  await expect(
    page.getByRole('combobox', { name: 'Проверенный человек для сопоставления' }),
  ).toHaveValue('')
  await page
    .getByRole('combobox', { name: 'Проверенный человек для сопоставления' })
    .selectOption('person-demo')
  const target = page.getByRole('combobox', { name: 'Целевой административный аккаунт' })
  await expect(target.getByRole('option', { name: /other@example/ })).toHaveCount(0)
  await expect(target).toHaveValue('')
  await target.selectOption('account-demo')
  await proof()
  await page.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
  await expect(page.getByText('Связь сохранена')).toBeVisible()
  await page.getByRole('button', { name: 'Связать фармацевта' }).click()
  await page.getByRole('button', { name: 'Демо Фармацевт · Демо-аптека 01' }).click()
  await page
    .getByRole('combobox', { name: 'Проверенный человек для сопоставления' })
    .selectOption('person-demo')
  await proof()
  await page.getByRole('button', { name: 'Подтвердить действие', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Связать фармацевта' })).toBeVisible()
  expect(writes.map((write) => write.path)).toEqual([
    '/api/admin/access/people',
    '/api/admin/access/source-accounts/source-demo/bind',
    '/api/admin/access/pharmacist-person-links',
  ])
  expect(
    await page.evaluate(() =>
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ),
  ).not.toMatch(/synthetic-current-password|DEMO-check-2026/)
  expect(blocked).toEqual([])
})
