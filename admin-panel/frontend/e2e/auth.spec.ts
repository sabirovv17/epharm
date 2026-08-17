// E2E: auth flows — login, validation, logout, route guards, JWT hydration.

import { test, expect, ACCOUNTS, loginViaUI } from './fixtures'

test.describe('Auth — /login форма', () => {
  test('рендерит wordmark, поля email/password, кнопку «Войти»', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await expect(freshPage.getByText(/Вход для HQ Inkar/i)).toBeVisible()
    await expect(freshPage.getByLabel(/Email/i)).toBeVisible()
    await expect(freshPage.getByLabel(/Пароль/i)).toBeVisible()
    await expect(freshPage.getByRole('button', { name: /^Войти/i })).toBeVisible()
  })

  test('email field получает autofocus', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await expect(freshPage.getByLabel(/Email/i)).toBeFocused()
  })

  test('актуальный брендинг: логотип-глиф + Console, без старых wordmark', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await expect(freshPage.getByText('Console', { exact: true })).toBeVisible()
    await expect(freshPage.locator('svg').first()).toBeVisible()
    const bodyText = await freshPage.locator('body').textContent()
    expect(bodyText ?? '').not.toMatch(/Epharm Console/)
    expect(bodyText ?? '').not.toMatch(/PharmaPay/)
  })
})

test.describe('Auth — валидация на клиенте', () => {
  test('пустые поля → ошибка «Заполните email и пароль», без сетевого вызова', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage.getByRole('alert')).toContainText(/Заполните email и пароль/i)
  })

  test('некорректный email → ошибка формата', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.getByLabel(/Email/i).fill('not-an-email')
    await freshPage.getByLabel(/Пароль/i).fill('somepass')
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage.getByRole('alert')).toContainText(/Неверный формат email/i)
  })
})

test.describe('Auth — submit', () => {
  test('валидный логин → редирект на /rules + имя в Topbar', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.getByLabel(/Email/i).fill(ACCOUNTS.damir.email)
    await freshPage.getByLabel(/Пароль/i).fill(ACCOUNTS.damir.password)
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage).toHaveURL(/\/rules$/)
    await expect(freshPage.getByText(ACCOUNTS.damir.name)).toBeVisible()
  })

  test('неверный пароль → INVALID_CREDENTIALS message', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.getByLabel(/Email/i).fill(ACCOUNTS.damir.email)
    await freshPage.getByLabel(/Пароль/i).fill('wrong-password')
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage.getByRole('alert')).toContainText(/Неверный email или пароль/i)
  })

  test('case-insensitive email login (Damir@... → ok)', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.getByLabel(/Email/i).fill('Damir@jadran.com')
    await freshPage.getByLabel(/Пароль/i).fill(ACCOUNTS.damir.password)
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage).toHaveURL(/\/rules$/)
  })
})

test.describe('Auth — route guards', () => {
  test('прямой URL /promo без auth → редирект на /login', async ({ freshPage }) => {
    await freshPage.goto('/promo')
    await expect(freshPage).toHaveURL(/\/login$/)
  })

  test('прямой URL /rules без auth → редирект на /login', async ({ freshPage }) => {
    await freshPage.goto('/rules')
    await expect(freshPage).toHaveURL(/\/login$/)
  })

  test('после логина прямой URL /promo → грузится без re-login', async ({ loggedInPage }) => {
    await loggedInPage.goto('/promo')
    await expect(loggedInPage).toHaveURL(/\/promo$/)
    await expect(loggedInPage.getByRole('heading', { level: 1, name: /Промо-кампании/i })).toBeVisible()
  })
})

test.describe('Auth — logout', () => {
  test('Topbar role-pill → клик «Выйти» → редирект /login', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: new RegExp(ACCOUNTS.damir.name) }).click()
    await loggedInPage.getByRole('menuitem', { name: /Выйти/i }).click()
    await expect(loggedInPage).toHaveURL(/\/login$/)
  })

  test('после logout прямой URL /rules → редирект /login (state очищен)', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: new RegExp(ACCOUNTS.damir.name) }).click()
    await loggedInPage.getByRole('menuitem', { name: /Выйти/i }).click()
    await loggedInPage.waitForURL(/\/login$/)
    // Подтверждаем что localStorage очищен — иначе reload восстановит сессию.
    await expect
      .poll(async () =>
        await loggedInPage.evaluate(() => localStorage.getItem('epharm.auth.tokens')),
      )
      .toBeNull()
    await loggedInPage.goto('/rules')
    await expect(loggedInPage).toHaveURL(/\/login$/, { timeout: 10_000 })
  })
})

test.describe('Auth — Bug J regression: Cmd+R не разлогинивает', () => {
  test('reload на /rules → юзер остаётся залогинен', async ({ loggedInPage }) => {
    await loggedInPage.goto('/rules')
    await expect(loggedInPage.getByText(ACCOUNTS.damir.name)).toBeVisible()
    await loggedInPage.reload()
    await expect(loggedInPage).toHaveURL(/\/rules$/)
    await expect(loggedInPage.getByText(ACCOUNTS.damir.name)).toBeVisible()
  })

  test('reload на /promo → URL и user сохраняются', async ({ loggedInPage }) => {
    await loggedInPage.goto('/promo')
    await loggedInPage.reload()
    await expect(loggedInPage).toHaveURL(/\/promo$/)
    await expect(loggedInPage.getByText(ACCOUNTS.damir.name)).toBeVisible()
  })
})

test.describe('Auth — switching между учётками', () => {
  test('login as aigerim → name в Topbar обновляется', async ({ freshPage }) => {
    await loginViaUI(freshPage, ACCOUNTS.aigerim)
    await expect(freshPage.getByText(ACCOUNTS.aigerim.name)).toBeVisible()
    await expect(freshPage).toHaveURL(/\/rules$/)
  })

  test('logout damir → login aigerim → видит свои данные, не damir', async ({
    freshPage,
  }) => {
    await loginViaUI(freshPage, ACCOUNTS.damir)
    await freshPage.getByRole('button', { name: new RegExp(ACCOUNTS.damir.name) }).click()
    await freshPage.getByRole('menuitem', { name: /Выйти/i }).click()
    await loginViaUI(freshPage, ACCOUNTS.aigerim)
    await expect(freshPage.getByText(ACCOUNTS.aigerim.name)).toBeVisible()
    await expect(freshPage.getByText(ACCOUNTS.damir.name)).not.toBeVisible()
  })
})
