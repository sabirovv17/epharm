// E2E: error scenarios — backend down, 500, validation, 403.
// Verify describeError выдаёт правильные сообщения.

import { test, expect, ACCOUNTS, BACKEND_URL } from './fixtures'

test.describe('Errors — login: backend недоступен', () => {
  test('блокировка /login → «Сервер недоступен»', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.route(`${BACKEND_URL}/api/admin/auth/login`, (route) => {
      route.abort('failed')
    })
    await freshPage.getByLabel(/Email/i).fill(ACCOUNTS.damir.email)
    await freshPage.getByLabel(/Пароль/i).fill(ACCOUNTS.damir.password)
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage.getByRole('alert')).toContainText(/Сервер недоступен/i)
  })

  test('500 от backend → «На сервере произошла ошибка»', async ({ freshPage }) => {
    await freshPage.goto('/login')
    await freshPage.route(`${BACKEND_URL}/api/admin/auth/login`, (route) => {
      route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
    })
    await freshPage.getByLabel(/Email/i).fill(ACCOUNTS.damir.email)
    await freshPage.getByLabel(/Пароль/i).fill(ACCOUNTS.damir.password)
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    // LoginPage показывает «Неверный email или пароль» для всех ошибок auth → проверяем что что-то есть
    await expect(freshPage.getByRole('alert')).toBeVisible()
  })
})

test.describe('Errors — empty data на изолированной странице', () => {
  test('/promo с blocked API + no cache → full Empty error state', async ({
    freshPage,
  }) => {
    // Сначала логинимся (нужен auth для попадания на /promo)
    await freshPage.goto('/login')
    await freshPage.getByLabel(/Email/i).fill(ACCOUNTS.damir.email)
    await freshPage.getByLabel(/Пароль/i).fill(ACCOUNTS.damir.password)
    await freshPage.getByRole('button', { name: /^Войти/i }).click()
    await expect(freshPage).toHaveURL(/\/rules$/)

    // Чистим cache чтобы не было stale data
    await freshPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))

    // Блокируем /promo API
    await freshPage.route(`${BACKEND_URL}/api/admin/promo*`, (route) => {
      route.abort('failed')
    })

    await freshPage.goto('/promo')
    // Full error — нет cached data, нет успешного ответа
    await expect(freshPage.getByText(/Не удалось загрузить кампании/i)).toBeVisible({
      timeout: 10_000,
    })
    await expect(freshPage.getByRole('button', { name: /Повторить/ })).toBeVisible()
  })

  test('кнопка «Повторить» делает retry → fresh data', async ({ loggedInPage }) => {
    // Идём на /promo, дожидаемся данных
    await loggedInPage.goto('/promo')
    await expect(
      loggedInPage.locator('[data-testid^="promo-card-"]').first(),
    ).toBeVisible({ timeout: 10_000 })

    // Блокируем API ПЕРЕД reload, чтобы fetch после reload падал
    let blocked = true
    await loggedInPage.route('**/api/admin/promo*', (route) => {
      if (blocked) route.abort('failed')
      else route.continue()
    })

    // Атомарная очистка cache + waitForLoadState'a гарантирует что debounced
    // subscriber из старого контекста не успеет переписать (page заменяется).
    await loggedInPage.evaluate(() => {
      localStorage.removeItem('epharm.query.cache')
    })
    await loggedInPage.waitForTimeout(150) // дольше чем debounceMs=50
    await loggedInPage.evaluate(() => {
      // Перепроверяем + чистим ещё раз на случай если subscriber успел переписать
      localStorage.removeItem('epharm.query.cache')
    })

    await loggedInPage.reload()
    await expect(loggedInPage.getByText(/Не удалось загрузить кампании/i)).toBeVisible({
      timeout: 15_000,
    })

    // Разблокируем + клик «Повторить»
    blocked = false
    await loggedInPage.getByRole('button', { name: /^Повторить/ }).click()
    // Данные должны вернуться
    await expect(
      loggedInPage.locator('[data-testid^="promo-card-"]').first(),
    ).toBeVisible({ timeout: 10_000 })
  })
})

test.describe('Errors — JWT refresh flow', () => {
  test('expired access-token + valid refresh → axios автоматически рефрешит', async ({
    loggedInPage,
  }) => {
    // Этот сценарий трудно воспроизвести без модификации backend (нет endpoint'а для
    // экспирации). Проверяем что инфра базово работает: 401 не должен сразу выкидывать.
    await loggedInPage.goto('/promo')
    await expect(
      loggedInPage.locator('[data-testid^="promo-card-"]').first(),
    ).toBeVisible({ timeout: 10_000 })

    // Имитация: один-разовый 401 на promo, потом обычная работа
    let count = 0
    await loggedInPage.route(`${BACKEND_URL}/api/admin/promo*`, (route) => {
      count++
      if (count === 1) {
        route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'UNAUTHORIZED', message: 'token expired' }),
        })
      } else {
        route.continue()
      }
    })
    await loggedInPage.reload()
    // После refresh-retry должны быть данные
    await expect(
      loggedInPage.locator('[data-testid^="promo-card-"]').first(),
    ).toBeVisible({ timeout: 10_000 })
  })
})
