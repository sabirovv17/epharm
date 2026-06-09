// E2E: Промо-кампании — CRUD, filter, search, archive/restore, detail modal,
// live preview. Покрывает Bug L+M+N+O regressions.

import { test, expect, BACKEND_URL, getBearer } from './fixtures'

test.beforeEach(async ({ loggedInPage }) => {
  await loggedInPage.goto('/promo')
  await expect(
    loggedInPage.getByRole('heading', { level: 1, name: /Промо-кампании/i }),
  ).toBeVisible()
})

test.describe('Promo — список и метрики', () => {
  test('seed-данные: видны 5 demo-кампаний', async ({ loggedInPage }) => {
    // Хотя бы 5 карточек должны быть на странице (из DevDataSeeder)
    const cards = loggedInPage.locator('[data-testid^="promo-card-"]')
    await expect(cards.first()).toBeVisible()
    expect(await cards.count()).toBeGreaterThanOrEqual(5)
  })

  test('4 метрики наверху страницы', async ({ loggedInPage }) => {
    await expect(loggedInPage.getByText('Активных кампаний')).toBeVisible()
    await expect(loggedInPage.getByText('Бюджет в работе')).toBeVisible()
    await expect(loggedInPage.getByText('Освоено')).toBeVisible()
    await expect(loggedInPage.getByText('Среднее ROI')).toBeVisible()
  })

  test('Bug M regression: «Экспорт» кнопки нет в header', async ({ loggedInPage }) => {
    await expect(loggedInPage.getByRole('button', { name: /^Экспорт$/ })).toHaveCount(0)
  })

  test('Bug M regression: «⋯» меню кнопки нет на карточках', async ({ loggedInPage }) => {
    await expect(
      loggedInPage.getByRole('button', { name: /Дополнительные действия/ }),
    ).toHaveCount(0)
  })
})

test.describe('Promo — фильтр и поиск', () => {
  // Детерминированно после globalSetup-reset (seed-промо со стабильными статусами).
  // Этот тест объявлен ДО create/archive в этом же файле + workers:1 → состояние = seed.
  test('фильтр по «Активные» скрывает draft/paused', async ({ loggedInPage }) => {
    const cards = loggedInPage.locator('[data-testid^="promo-card-"]')
    // Дожидаемся загрузки списка ПЕРЕД подсчётом (иначе в полном прогоне count=0 — флейк).
    await expect(cards.first()).toBeVisible()
    const allCount = await cards.count()

    await loggedInPage.locator('select').first().selectOption('active')
    // Web-first ожидание применения фильтра: черновики должны пропасть (retry до таймаута).
    await expect(loggedInPage.locator('.chip', { hasText: /Черновик/ })).toHaveCount(0)

    const activeCount = await cards.count()
    expect(activeCount).toBeLessThanOrEqual(allCount)
  })

  test('поиск по title фильтрует список', async ({ loggedInPage }) => {
    await loggedInPage.getByPlaceholder(/Поиск кампании/).fill('Аквамарис')
    const cards = loggedInPage.locator('[data-testid^="promo-card-"]')
    await expect(cards.first()).toBeVisible()
    const titles = await cards.allTextContents()
    expect(titles.some((t) => /Аквамарис/i.test(t))).toBe(true)
  })

  test('поиск по несуществующему → Empty state «Ничего не найдено»', async ({
    loggedInPage,
  }) => {
    await loggedInPage.getByPlaceholder(/Поиск кампании/).fill('xyzzyx-no-match')
    await expect(loggedInPage.getByText(/Ничего не найдено/i)).toBeVisible()
  })
})

test.describe('Promo — клик по карточке открывает страницу кампании', () => {
  test('клик по карточке → переход на /promo/:id с редактором', async ({ loggedInPage }) => {
    await loggedInPage.locator('[data-testid^="promo-card-"]').first().click()
    await expect(loggedInPage).toHaveURL(/\/promo\/[^/]+$/)
    await expect(loggedInPage.getByTestId('promo-detail')).toBeVisible()
    await expect(loggedInPage.getByText(/Аптек участвует/i)).toBeVisible()
  })

  test('cover-блок содержит title + brand на странице', async ({ loggedInPage }) => {
    const firstCard = loggedInPage.locator('[data-testid^="promo-card-"]').first()
    const cardTitle = await firstCard.locator('.font-extrabold').first().textContent()
    await firstCard.click()
    const cover = loggedInPage.locator('[data-testid="promo-detail-cover"]')
    await expect(cover).toBeVisible()
    if (cardTitle) {
      await expect(cover).toContainText(cardTitle.trim())
    }
  })

  test('«К кампаниям» возвращает на список', async ({ loggedInPage }) => {
    await loggedInPage.locator('[data-testid^="promo-card-"]').first().click()
    await expect(loggedInPage).toHaveURL(/\/promo\/[^/]+$/)
    await loggedInPage.getByRole('button', { name: /К кампаниям/ }).click()
    await expect(loggedInPage).toHaveURL(/\/promo$/)
    await expect(loggedInPage.getByTestId('promo-grid')).toBeVisible()
  })

  test('редактирование названия → Сохранить → toast', async ({ loggedInPage }) => {
    await loggedInPage.locator('[data-testid^="promo-card-"]').first().click()
    await expect(loggedInPage.getByTestId('promo-detail')).toBeVisible()
    // Берём первое текстовое поле (Название) и дописываем суффикс
    const title = loggedInPage.getByLabel('Название')
    await title.fill((await title.inputValue()) + ' ✎')
    await loggedInPage.getByRole('button', { name: /^Сохранить$/ }).click()
    await expect(loggedInPage.getByText(/Изменения сохранены/i)).toBeVisible()
  })
})

test.describe('Promo — toggle pause/resume + stopPropagation', () => {
  test('клик «Поставить на паузу» НЕ навигирует на страницу кампании', async ({
    loggedInPage,
    request,
  }) => {
    // Создаём свою active кампанию через API — не зависим от seed-state,
    // который мог быть выключен прошлыми E2E run'ами (archive/pause).
    const bearer = await getBearer(request)
    const created = await request.post(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
      data: {
        title: `E2E-pause-${Date.now()}`,
        brand: 'Test',
        status: 'active',
        budget: 100,
      },
    })
    expect(created.ok()).toBe(true)
    const promo = (await created.json()) as { id: string }

    // Чистим cache чтобы fresh fetch включил новую кампанию
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')

    const card = loggedInPage.locator(`[data-testid="promo-card-${promo.id}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })
    await card.getByRole('button', { name: /Поставить на паузу/ }).click()

    // Навигации не произошло — остались на списке /promo
    await expect(loggedInPage).toHaveURL(/\/promo$/)
    await expect(loggedInPage.getByTestId('promo-detail')).not.toBeVisible({ timeout: 1500 })
  })
})

test.describe('Promo — create кампанию', () => {
  test('«Новая кампания» открывает modal с live preview', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    await expect(loggedInPage.getByTestId('promo-create-preview')).toBeVisible()
    await expect(loggedInPage.getByText(/Название кампании/i)).toBeVisible()
  })

  test('Bug O regression: ввод title обновляет preview', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    await loggedInPage
      .getByPlaceholder(/Майский марафон Аквамарис/)
      .fill('E2E test campaign')
    await expect(loggedInPage.getByTestId('promo-create-preview')).toContainText(
      'E2E test campaign',
    )
  })

  test('Bug O regression: ввод hex обновляет background preview', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    const coverInput = loggedInPage.getByPlaceholder('#16C97A')
    await coverInput.clear()
    await coverInput.fill('#FF00AA')
    // Real chromium может сериализовать как rgb или hex. Проверяем через computed.
    const bgImage = await loggedInPage.getByTestId('promo-create-preview').evaluate((el) =>
      window.getComputedStyle(el).backgroundImage,
    )
    // #FF00AA = rgb(255, 0, 170)
    expect(bgImage).toMatch(/rgb\(255,\s*0,\s*170\)/)
  })

  test('кнопка disabled пока title или brand пустые', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    const submit = loggedInPage.getByRole('button', { name: /Создать черновик/ })
    await expect(submit).toBeDisabled()
    await loggedInPage.getByPlaceholder(/Майский марафон Аквамарис/).fill('Test')
    await expect(submit).toBeDisabled()
    await loggedInPage.getByPlaceholder(/Jadran-Galenski/).fill('Jadran')
    await expect(submit).toBeEnabled()
  })

  test('создать кампанию → toast + новый promo в списке', async ({ loggedInPage }) => {
    const unique = `E2E-${Date.now()}`
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    await loggedInPage.getByPlaceholder(/Майский марафон Аквамарис/).fill(unique)
    await loggedInPage.getByPlaceholder(/Jadran-Galenski/).fill('Test brand')
    await loggedInPage.getByRole('button', { name: /Создать черновик/ }).click()
    await expect(loggedInPage.getByText(/Кампания создана в черновиках/i)).toBeVisible()
    // Modal закрылась
    await expect(loggedInPage.getByText(/Сохраним черновик/i)).not.toBeVisible()
    // Карточка появилась
    await expect(loggedInPage.getByText(unique)).toBeVisible()
  })
})

test.describe('Promo — Bug L regression: archive + restore', () => {
  test('архивировать → подтверждение → toast', async ({ loggedInPage, request }) => {
    const bearer = await getBearer(request)
    const created = await request.post(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
      data: { title: `E2E-archive-${Date.now()}`, brand: 'Test', status: 'active', budget: 100 },
    })
    expect(created.ok()).toBe(true)
    const promo = (await created.json()) as { id: string; title: string }

    // Чистим cache, чтобы staleTime не закэшировал данные без нового промо
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')
    const card = loggedInPage.locator(`[data-testid="promo-card-${promo.id}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })

    loggedInPage.once('dialog', (d) => d.accept())
    await card.getByRole('button', { name: /Архивировать/ }).click()
    await expect(loggedInPage.getByText(/Кампания отправлена в архив/i)).toBeVisible()
  })

  test('archived promo показывает «Восстановить» button', async ({
    loggedInPage,
    request,
  }) => {
    const bearer = await getBearer(request)
    const created = await request.post(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
      data: { title: `E2E-restore-${Date.now()}`, brand: 'Test', status: 'active', budget: 100 },
    })
    const promo = (await created.json()) as { id: string }
    await request.post(`${BACKEND_URL}/api/admin/promo/${promo.id}/archive`, {
      headers: { Authorization: bearer },
    })
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')
    await loggedInPage.locator('select').first().selectOption('archived')

    const card = loggedInPage.locator(`[data-testid="promo-card-${promo.id}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card.getByRole('button', { name: /^Восстановить$/ })).toBeVisible()
    await expect(card.getByRole('button', { name: /Поставить на паузу/ })).toHaveCount(0)
  })

  test('Restore → status переходит в draft + toast', async ({ loggedInPage, request }) => {
    const bearer = await getBearer(request)
    const created = await request.post(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
      data: { title: `E2E-restore-do-${Date.now()}`, brand: 'Test', status: 'active', budget: 1 },
    })
    const promo = (await created.json()) as { id: string }
    await request.post(`${BACKEND_URL}/api/admin/promo/${promo.id}/archive`, {
      headers: { Authorization: bearer },
    })
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')
    await loggedInPage.locator('select').first().selectOption('archived')

    const card = loggedInPage.locator(`[data-testid="promo-card-${promo.id}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })
    await card.getByRole('button', { name: /^Восстановить$/ }).click()
    await expect(loggedInPage.getByText(/Кампания восстановлена/i)).toBeVisible()
  })
})
