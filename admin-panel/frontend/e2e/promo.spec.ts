// E2E: Промо-кампании — CRUD, filter, search, archive/restore и товарная форма.

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
  test('клик «Поставить на паузу» НЕ навигирует на страницу кампании', async ({ loggedInPage }) => {
    // pr_001 — стабильная active seed-кампания. Per-test reset возвращает её
    // перед каждым сценарием, поэтому не создаём невалидную active-кампанию без товара.
    const card = loggedInPage.getByTestId('promo-card-pr_001')
    await expect(card).toBeVisible({ timeout: 10_000 })
    await card.getByRole('button', { name: /Поставить на паузу/ }).click()

    // Навигации не произошло — остались на списке /promo
    await expect(loggedInPage).toHaveURL(/\/promo$/)
    await expect(loggedInPage.getByTestId('promo-detail')).not.toBeVisible({ timeout: 1500 })
  })
})

test.describe('Promo — create кампанию', () => {
  const storefrontProduct = {
    id: 'prod_e2e_storefront',
    name: 'E2E товар витрины',
    brand: 'E2E Brand',
    mnn: null,
    rxOtc: 'OTC',
    price: 4990,
    currency: 'KZT',
    imageUrl: null,
    barcode: '4603423004936',
    ipartId: 'E2E-IPART',
    category: 'Тестовая категория',
  }

  test.beforeEach(async ({ loggedInPage }) => {
    // UI-сценарии не должны зависеть от доступности внешней Medusa. Интеграция
    // backend↔Medusa покрывается backend-тестами; здесь фиксируем контракт формы.
    await loggedInPage.route('**/api/admin/storefront/products**', async (route) => {
      const path = new URL(route.request().url()).pathname
      const isDetail = path.endsWith(`/${storefrontProduct.id}`)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          isDetail
            ? {
                ...storefrontProduct,
                atc: null,
                images: [],
                country: null,
                manufacturer: null,
                description: 'Тестовое описание товара',
                keyFacts: [],
              }
            : { items: [storefrontProduct], total: 1, limit: 50, offset: 0 },
        ),
      })
    })
  })

  const selectFirstProduct = async (loggedInPage: import('@playwright/test').Page) => {
    const option = loggedInPage.locator('[data-testid^="promo-product-option-"]').first()
    await expect(option).toBeVisible()
    await option.click()
    await expect(loggedInPage.getByTestId('create-price-readonly')).toBeVisible()
  }

  test('«Новая кампания» открывает товарную форму Medusa', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    const dialog = loggedInPage.getByRole('dialog', { name: /Новая кампания/ })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText(/Один товар из витрины/i)).toBeVisible()
    await expect(dialog.getByPlaceholder(/Поиск товара в витрине Medusa/)).toBeVisible()
  })

  test('выбор товара заполняет название и показывает read-only цену', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    await selectFirstProduct(loggedInPage)
    await expect(loggedInPage.getByPlaceholder(/Майский марафон Аквамарис/)).not.toHaveValue('')
  })

  test('выбор пресета меняет цвет обложки', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    const preset = loggedInPage.getByRole('button', { name: '#BE5A38' })
    await preset.click()
    await expect(preset).toHaveAttribute('aria-pressed', 'true')
  })

  test('кнопка disabled до выбора товара, после выбора — enabled', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    const submit = loggedInPage.getByRole('button', { name: /Создать черновик/ })
    await expect(submit).toBeDisabled()
    await selectFirstProduct(loggedInPage)
    await expect(submit).toBeEnabled()
  })

  test('создать кампанию → корректный POST + toast', async ({ loggedInPage }) => {
    const unique = `E2E-${Date.now()}`
    let submitted: Record<string, unknown> | null = null
    await loggedInPage.route('**/api/admin/promo', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      const payload = route.request().postDataJSON() as Record<string, unknown>
      submitted = payload
      const now = new Date().toISOString()
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'pr_e2e_created',
          ...payload,
          status: 'draft',
          pharmacies: 0,
          budget: 0,
          spent: 0,
          kpi: '',
          price: storefrontProduct.price,
          tiers: [{ minQty: 1, price: storefrontProduct.price, bonus: 0 }],
          createdBy: 'u_brand',
          createdAt: now,
          updatedAt: now,
        }),
      })
    })

    await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
    await selectFirstProduct(loggedInPage)
    await loggedInPage.getByPlaceholder(/Майский марафон Аквамарис/).fill(unique)
    await loggedInPage.getByRole('button', { name: /Создать черновик/ }).click()
    await expect(loggedInPage.getByText(/Кампания создана в черновиках/i)).toBeVisible()
    await expect(loggedInPage.getByRole('dialog', { name: /Новая кампания/ })).toHaveCount(0)
    expect(submitted).toMatchObject({
      title: unique,
      status: 'draft',
      medusaProductId: storefrontProduct.id,
      productName: storefrontProduct.name,
      barcode: storefrontProduct.barcode,
      ipartId: storefrontProduct.ipartId,
    })
  })
})

test.describe('Promo — Bug L regression: archive + restore', () => {
  test('архивировать → подтверждение → toast', async ({ loggedInPage }) => {
    const card = loggedInPage.getByTestId('promo-card-pr_001')
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
    await request.post(`${BACKEND_URL}/api/admin/promo/pr_001/archive`, {
      headers: { Authorization: bearer },
    })
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')
    await loggedInPage.locator('select').first().selectOption('archived')

    const card = loggedInPage.getByTestId('promo-card-pr_001')
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card.getByRole('button', { name: /^Восстановить$/ })).toBeVisible()
    await expect(card.getByRole('button', { name: /Поставить на паузу/ })).toHaveCount(0)
  })

  test('Restore → status переходит в draft + toast', async ({ loggedInPage, request }) => {
    const bearer = await getBearer(request)
    await request.post(`${BACKEND_URL}/api/admin/promo/pr_001/archive`, {
      headers: { Authorization: bearer },
    })
    await loggedInPage.evaluate(() => localStorage.removeItem('epharm.query.cache'))
    await loggedInPage.goto('/promo')
    await loggedInPage.locator('select').first().selectOption('archived')

    const card = loggedInPage.getByTestId('promo-card-pr_001')
    await expect(card).toBeVisible({ timeout: 10_000 })
    await card.getByRole('button', { name: /^Восстановить$/ }).click()
    await expect(loggedInPage.getByText(/Кампания восстановлена/i)).toBeVisible()
  })
})
