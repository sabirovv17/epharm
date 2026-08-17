// E2E: Rules Engine — read-only list/builder + backend validation contracts.
// Покрывает: Bug F (trigger shape), Bug G (PATCH archived), Bug H (self-ref).

import { test, expect, BACKEND_URL, getBearer } from './fixtures'

test.beforeEach(async ({ loggedInPage }) => {
  await loggedInPage.goto('/rules')
  await expect(
    loggedInPage.getByRole('heading', { level: 1, name: /Rules Engine/i }),
  ).toBeVisible()
})

test.describe('Rules — список', () => {
  test('seed-данные: substitution + crosssell + 0 archive', async ({ loggedInPage }) => {
    await expect(loggedInPage.getByRole('button', { name: /Замены/ })).toBeVisible()
    await expect(loggedInPage.getByRole('button', { name: /Кросс-селл/ })).toBeVisible()
    await expect(loggedInPage.getByRole('button', { name: /Архив/ })).toBeVisible()

    const rows = loggedInPage.locator('[data-testid^="rule-row-"]')
    await expect(rows.first()).toBeVisible()
  })

  test('клик по правилу → builder показывает его в правой панели', async ({
    loggedInPage,
  }) => {
    const first = loggedInPage.locator('[data-testid^="rule-row-"]').first()
    await first.click()
    const builder = loggedInPage.getByTestId('rule-builder-panel')
    await expect(builder.getByText(/Замена/)).toBeVisible()
  })
})

test.describe('Rules — табы', () => {
  test('переключение «Кросс-селл» меняет список', async ({ loggedInPage }) => {
    await loggedInPage.getByRole('button', { name: /Кросс-селл/ }).click()
    // На пустом архиве показывается empty state OR rules. На кросс-сейл — 2 rules.
    const rows = loggedInPage.locator('[data-testid^="rule-row-"]')
    await expect(rows.first()).toBeVisible({ timeout: 5_000 })
  })

  // Детерминированно благодаря per-test reset (fixtures.resetBackend): seed =
  // 4 substitution + 2 crosssell + 0 archived. Расширяем вьюпорт: при ширине ровно
  // 1280px (root min-width) трейлинг-фильтр <select> перекрывает таб «Архив» и
  // перехватывает клик — на 1600px раскладка не сворачивается, клик чистый.
  test('переключение на «Архив» → пустой архив в seed-базисе', async ({ loggedInPage }) => {
    await loggedInPage.setViewportSize({ width: 1600, height: 900 })
    const archiveTab = loggedInPage.getByRole('button', { name: /Архив/ })
    await archiveTab.click()
    await expect(loggedInPage.locator('[data-testid^="rule-row-"]')).toHaveCount(0)
  })
})

test.describe('Rules — фильтр + поиск', () => {
  test('фильтр по «Активные» скрывает paused', async ({ loggedInPage }) => {
    await loggedInPage.locator('select').first().selectOption('active')
    const rows = loggedInPage.locator('[data-testid^="rule-row-"]')
    await expect(rows.first()).toBeVisible()
  })

  test('поиск по триггеру → фильтрация', async ({ loggedInPage }) => {
    await loggedInPage.getByPlaceholder(/Поиск по правилам/).fill('Аквалор')
    const rows = loggedInPage.locator('[data-testid^="rule-row-"]')
    await expect(rows.first()).toBeVisible()
  })
})

test.describe('Rules — read-only контракт', () => {
  test('детальная панель показывает статус без переключателя', async ({ loggedInPage }) => {
    await loggedInPage.locator('[data-testid^="rule-row-"]').first().click()
    await expect(loggedInPage.getByTestId('rule-builder-panel')).toBeVisible()
    await expect(loggedInPage.getByRole('switch')).toHaveCount(0)
  })

  test('страница объясняет, что правила задаются из кампаний', async ({ loggedInPage }) => {
    await expect(loggedInPage.getByTestId('rules-from-campaigns-banner')).toContainText(
      /Промо-кампании/,
    )
  })

  test('в строках нет меню архивирования/дублирования', async ({ loggedInPage }) => {
    await expect(loggedInPage.locator('[data-testid^="row-menu-trigger-"]')).toHaveCount(0)
    await expect(loggedInPage.getByRole('menu')).toHaveCount(0)
  })
})

test.describe('Rules — Bug F regression: backend отклоняет невалидный trigger', () => {
  test('POST /rules с {kind:product, value:array} → 400 VALIDATION_FAILED', async ({
    request,
  }) => {
    const bearer = await getBearer(request)
    const r = await request.post(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer, 'Content-Type': 'application/json' },
      data: {
        type: 'substitution',
        trigger: { kind: 'product', value: ['p_aql_norm_s'] },
        recommend: 'p_aqm_norm_s',
        bonus: 100,
      },
    })
    expect(r.status()).toBe(400)
    const body = (await r.json()) as { code: string }
    expect(body.code).toBe('VALIDATION_FAILED')
  })

  test('POST /rules с {kind:product_any, value:string} → 400', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.post(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer, 'Content-Type': 'application/json' },
      data: {
        type: 'substitution',
        trigger: { kind: 'product_any', value: 'p_aql_norm_s' },
        recommend: 'p_aqm_norm_s',
      },
    })
    expect(r.status()).toBe(400)
  })
})

test.describe('Rules — Bug G regression: PATCH не может архивировать', () => {
  test('PATCH {status:archived} → 400 VALIDATION_FAILED', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.patch(`${BACKEND_URL}/api/admin/rules/r_001`, {
      headers: { Authorization: bearer, 'Content-Type': 'application/json' },
      data: { status: 'archived' },
    })
    expect(r.status()).toBe(400)
    const body = (await r.json()) as { code: string }
    expect(body.code).toBe('VALIDATION_FAILED')
  })
})

test.describe('Rules — Bug H regression: self-reference отклоняется', () => {
  test('POST с recommend == trigger.value → 400', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.post(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer, 'Content-Type': 'application/json' },
      data: {
        type: 'substitution',
        trigger: { kind: 'product', value: 'p_aqm_norm_s' },
        recommend: 'p_aqm_norm_s',
        bonus: 100,
      },
    })
    expect(r.status()).toBe(400)
    const body = (await r.json()) as { code: string }
    expect(body.code).toBe('VALIDATION_FAILED')
  })
})
