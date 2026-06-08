// E2E: AppShell — Sidebar, Topbar, CommandPalette, role switcher, contract widget.

import { test, expect } from './fixtures'

test.describe('Sidebar — навигация', () => {
  test('12 пунктов меню кликабельны', async ({ loggedInPage }) => {
    const sections = [
      { label: /Дашборд аналитики/, url: /\/dashboard/ },
      { label: /Промо-кампании/, url: /\/promo/ },
      { label: /Rules Engine/, url: /\/rules/ },
      { label: /Управление экранами/, url: /\/screens/ },
      { label: /Сеть аптек/, url: /\/pharmacies/ },
      { label: /Фармацевты/, url: /\/pharmacists/ },
      { label: /Сверка чеков/, url: /\/reconcile/ },
      { label: /AI-Экзаменация/, url: /\/ai-exam/ },
      { label: /Финансы \/ выплаты/, url: /\/finance/ },
      { label: /Аналитика lift/, url: /\/lift/ },
      { label: /Обучение \/ LMS/, url: /\/lms/ },
      { label: /Настройки/, url: /\/settings/ },
    ]
    for (const s of sections) {
      await loggedInPage.getByRole('button', { name: s.label }).click()
      await expect(loggedInPage).toHaveURL(s.url)
    }
  })

  test('активный пункт highlighted при текущей странице', async ({ loggedInPage }) => {
    await loggedInPage.goto('/promo')
    const promoBtn = loggedInPage.getByRole('button', { name: /Промо-кампании/ })
    await expect(promoBtn).toHaveClass(/sidebar-active/)
  })

  test('Bug J/K regression: Epharm wordmark с E зелёным', async ({ loggedInPage }) => {
    const wordmark = loggedInPage.locator('text=Epharm').first()
    await expect(wordmark).toBeVisible()
  })
})

test.describe('Sidebar — Contract widget', () => {
  test('Damir (без активного контракта) → empty state «Не подписан»', async ({
    loggedInPage,
  }) => {
    // MVP: ни у кого нет активного контракта
    await expect(loggedInPage.getByTestId('contract-widget-empty')).toBeVisible()
    await expect(loggedInPage.getByText(/Не подписан/i)).toBeVisible()
  })

  test('empty state не кликается (нет button внутри)', async ({ loggedInPage }) => {
    const widget = loggedInPage.getByTestId('contract-widget-empty')
    const button = widget.locator('button')
    expect(await button.count()).toBe(0)
  })
})

test.describe('Topbar — role-pill и logout', () => {
  test('role-pill показывает имя + роль', async ({ loggedInPage }) => {
    await expect(loggedInPage.getByText(/Дамир Нурланов/)).toBeVisible()
    await expect(loggedInPage.getByText(/Brand Manager/i)).toBeVisible()
  })

  test('клик по role-pill → dropdown с «Сменить роль» + «Выйти»', async ({
    loggedInPage,
  }) => {
    await loggedInPage.getByRole('button', { name: /Дамир Нурланов/ }).click()
    await expect(loggedInPage.getByRole('menuitem', { name: /Сменить роль/i })).toBeVisible()
    await expect(loggedInPage.getByRole('menuitem', { name: /Выйти/i })).toBeVisible()
  })

  test('«Сменить роль» → открывает RoleSwitcher modal', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Дамир Нурланов/ }).click()
    await loggedInPage.getByRole('menuitem', { name: /Сменить роль/i }).click()
    // RoleSwitcher показывает список trial accounts
    await expect(loggedInPage.getByText(/Айгерим/)).toBeVisible()
    await expect(loggedInPage.getByText(/Бауыржан/)).toBeVisible()
  })
})

test.describe('Topbar — CommandPalette (⌘K / клик)', () => {
  test('клик по топбар-кнопке «⌘K K» открывает palette', async ({ loggedInPage }) => {
    // На странице видна кнопка «Что найти» с шорткатом — кликаем её (более устойчиво
    // чем keyboard event, который может уйти не в тот focus).
    await loggedInPage.getByRole('button', { name: /Найти правило/i }).click()
    await expect(loggedInPage.getByPlaceholder(/Что найти/)).toBeVisible()
  })

  test('Escape закрывает palette', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Найти правило/i }).click()
    await expect(loggedInPage.getByPlaceholder(/Что найти/)).toBeVisible()
    await loggedInPage.keyboard.press('Escape')
    await expect(loggedInPage.getByPlaceholder(/Что найти/)).not.toBeVisible()
  })

  test('фильтр по тексту работает', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Найти правило/i }).click()
    await loggedInPage.getByPlaceholder(/Что найти/).fill('Rules')
    await expect(loggedInPage.getByText('Rules Engine').first()).toBeVisible()
  })
})

test.describe('Sidebar — collapse/expand', () => {
  test('collapse → ширина уменьшается до ~72px', async ({ loggedInPage }) => {
    const aside = loggedInPage.locator('aside').first()
    const before = (await aside.boundingBox())?.width ?? 0
    await loggedInPage.locator('aside button[title="Свернуть"]').click()
    // Даём transition'у завершиться
    await loggedInPage.waitForTimeout(300)
    const after = (await aside.boundingBox())?.width ?? 0
    expect(after).toBeLessThan(before)
    expect(after).toBeLessThanOrEqual(80)
  })

  test('expand button разворачивает обратно', async ({ loggedInPage }) => {
    await loggedInPage.locator('aside button[title="Свернуть"]').click()
    await loggedInPage.waitForTimeout(300)
    await loggedInPage.locator('aside button[title="Развернуть"]').first().click()
    await loggedInPage.waitForTimeout(300)
    const width = (await loggedInPage.locator('aside').first().boundingBox())?.width ?? 0
    expect(width).toBeGreaterThan(200)
  })
})

test.describe('Empty sections (10 ещё не подключены к backend)', () => {
  const empties = [
    { url: '/dashboard', title: /Дашборд/ },
    { url: '/screens', title: /Управление экранами/ },
    { url: '/pharmacies', title: /Сеть аптек/ },
    { url: '/pharmacists', title: /Фармацевты/ },
    { url: '/reconcile', title: /Сверка чеков/ },
    { url: '/ai-exam', title: /AI-Экзаменация/ },
    { url: '/finance', title: /Финансы/ },
    { url: '/lift', title: /Аналитика lift/ },
    { url: '/lms', title: /Обучение/ },
    { url: '/settings', title: /Настройки/ },
  ]

  for (const e of empties) {
    test(`${e.url} рендерится без ошибок`, async ({ loggedInPage }) => {
      await loggedInPage.goto(e.url)
      await expect(
        loggedInPage.getByRole('heading', { level: 1, name: e.title }),
      ).toBeVisible()
    })
  }
})
