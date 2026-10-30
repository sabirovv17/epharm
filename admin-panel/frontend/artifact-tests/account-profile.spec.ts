import { expect, test } from '@playwright/test'

test('TRAINER changes only own password once and must sign in again with local auth cleared', async ({
  page,
  baseURL,
}, testInfo) => {
  const account = {
    id: 'profile-password-demo',
    name: 'Учебный профиль',
    email: 'trainer-password@example.test',
    role: 'TRAINER',
    company: 'Учебный центр',
  }
  const currentPassword = 'Synthetic-old-2026!'
  const newPassword = 'Synthetic-new-2026!'
  const blocked: string[] = []
  const passwordWrites: unknown[] = []
  let logoutRequests = 0
  let releasePassword!: () => void
  const passwordResponse = new Promise<void>((resolve) => {
    releasePassword = resolve
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.addInitScript((user) => {
    localStorage.setItem('epharm.auth.user', JSON.stringify(user))
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-admin',
        refreshToken: 'synthetic-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
      }),
    )
  }, account)
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(`${method} ${url.href}`)
      return route.abort()
    }
    if (!path.startsWith('/api/') && method === 'GET') return route.continue()
    if (method === 'GET' && path === '/api/admin/auth/me') {
      expect(request.headers().authorization).toBe('Bearer synthetic-admin')
      return route.fulfill({ json: account })
    }
    if (method === 'GET' && path === '/api/admin/access/me') {
      expect(request.headers().authorization).toBe('Bearer synthetic-admin')
      return route.fulfill({
        json: {
          managed: true,
          roleCeiling: 'TRAINER',
          grants: [],
          canViewAccounts: false,
          canManageAccounts: false,
        },
      })
    }
    if (method === 'POST' && path === '/api/admin/auth/password') {
      expect(request.headers().authorization).toBe('Bearer synthetic-admin')
      expect(url.search).toBe('')
      const body = request.postDataJSON()
      passwordWrites.push(body)
      // Exact equality rules out an account ID, role, or confirmation field being sent.
      expect(body).toEqual({ currentPassword, newPassword })
      await passwordResponse
      return route.fulfill({ status: 204 })
    }
    if (method === 'POST' && path === '/api/admin/auth/logout') {
      expect(request.headers().authorization).toBe('Bearer synthetic-admin')
      logoutRequests++
      return route.fulfill({ status: 204 })
    }
    blocked.push(`${method} ${path}`)
    return route.abort()
  })
  await page.goto('/account')
  const form = page.getByRole('form', { name: 'Сменить пароль' })
  await expect(form).toBeVisible()
  await form.getByLabel('Текущий пароль', { exact: true }).fill(currentPassword)
  await form.getByLabel('Новый пароль', { exact: true }).fill(newPassword)
  await form.getByLabel('Повторите новый пароль', { exact: true }).fill(newPassword)
  await form.getByRole('button', { name: 'Сменить пароль', exact: true }).click()
  try {
    await expect.poll(() => passwordWrites.length).toBe(1)
    await expect(form.getByRole('button')).toBeDisabled()
    await expect(form.getByLabel('Текущий пароль', { exact: true })).toBeDisabled()
    await expect(form.getByLabel('Новый пароль', { exact: true })).toBeDisabled()
    await expect(form.getByLabel('Повторите новый пароль', { exact: true })).toBeDisabled()
    // Even a duplicate submit event cannot start a second mutation while one is pending.
    await form.dispatchEvent('submit')
  } finally {
    releasePassword()
  }
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toHaveText('Пароль изменён. Войдите с новым паролем.')
  await expect(page.getByRole('form', { name: 'Форма входа' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeEnabled()
  await expect(page.locator('input[type="password"]')).toHaveValue('')
  await expect(page.locator('input[type="email"]')).toHaveValue('')
  expect(
    await page.evaluate(() => ({
      user: localStorage.getItem('epharm.auth.user'),
      tokens: localStorage.getItem('epharm.auth.tokens'),
    })),
  ).toEqual({ user: null, tokens: null })
  await expect.poll(() => logoutRequests).toBe(1)
  expect(passwordWrites).toEqual([{ currentPassword, newPassword }])
  expect(blocked).toEqual([])
  await page.screenshot({
    path: testInfo.outputPath('account-password-changed-login.png'),
    fullPage: true,
  })
  expect(blocked).toEqual([])
})

for (const role of ['TRAINER', 'REGIONAL_MANAGER']) {
  test(`${role} opens own profile without management permissions`, async ({
    page,
    baseURL,
  }, testInfo) => {
    const account = {
      id: 'profile-demo',
      name: 'Профиль сотрудника',
      email: 'profile@example.test',
      role,
      company: 'Учебный центр',
    }
    const blocked: string[] = []
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.addInitScript((user) => {
      localStorage.setItem('epharm.auth.user', JSON.stringify(user))
      localStorage.setItem(
        'epharm.auth.tokens',
        JSON.stringify({
          accessToken: 'synthetic-admin',
          refreshToken: 'synthetic-refresh',
          accessTokenExpiresAt: '2099-01-01T00:00:00Z',
          refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
        }),
      )
    }, account)
    await page.route('**/*', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      if (url.origin !== new URL(baseURL!).origin) {
        blocked.push(url.pathname)
        return route.abort()
      }
      if (!url.pathname.startsWith('/api/')) return route.continue()
      if (request.method() !== 'GET') {
        blocked.push(`${request.method()} ${url.pathname}`)
        return route.abort()
      }
      expect(request.headers().authorization).toBe('Bearer synthetic-admin')
      if (url.pathname === '/api/admin/auth/me') return route.fulfill({ json: account })
      if (url.pathname === '/api/admin/access/me')
        return route.fulfill({
          json: {
            managed: true,
            roleCeiling: role,
            grants: [],
            canViewAccounts: false,
            canManageAccounts: false,
          },
        })
      blocked.push(url.pathname)
      return route.abort()
    })
    await page.goto('/account')
    await expect(page.locator(`input[value="${account.email}"]`)).toBeDisabled()
    await expect(page.locator(`input[value="${account.name}"]`)).toBeDisabled()
    await expect(
      page.getByText(
        'После смены пароля все сессии этого аккаунта будут завершены. Потребуется войти заново.',
      ),
    ).toBeVisible()
    await page.getByRole('button', { name: new RegExp(account.name) }).click()
    await page.getByRole('menuitem', { name: 'Мой профиль' }).click()
    await expect(page).toHaveURL(/\/account$/)
    await expect(page.locator(`input[value="${account.email}"]`)).toBeVisible()
    await expect(page.getByRole('button', { name: /Создать аккаунт/ })).toHaveCount(0)
    expect(blocked).toEqual([])
    await page.screenshot({
      path: testInfo.outputPath('internal-account-profile.png'),
      fullPage: true,
    })
  })
}
