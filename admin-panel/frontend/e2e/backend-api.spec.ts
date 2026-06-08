// E2E: прямые тесты backend API (без UI). Покрывает контракт +
// все regression cases которые трудно проверить через UI.

import { test, expect, BACKEND_URL, ACCOUNTS, getBearer } from './fixtures'

test.describe('Backend — Health', () => {
  test('GET /api/health → 200 + ok', async ({ request }) => {
    const r = await request.get(`${BACKEND_URL}/api/health`)
    expect(r.ok()).toBe(true)
    const body = (await r.json()) as { status: string; service: string }
    expect(body.status).toBe('ok')
    expect(body.service).toBe('epharm-backend')
  })
})

test.describe('Backend — Auth', () => {
  test('POST /login с валидными credentials → tokens + user', async ({ request }) => {
    const r = await request.post(`${BACKEND_URL}/api/admin/auth/login`, {
      data: { email: ACCOUNTS.damir.email, password: ACCOUNTS.damir.password },
    })
    expect(r.ok()).toBe(true)
    const body = (await r.json()) as {
      tokens: { accessToken: string; refreshToken: string }
      user: { email: string; role: string }
    }
    expect(body.tokens.accessToken).toBeTruthy()
    expect(body.tokens.refreshToken).toBeTruthy()
    expect(body.user.email).toBe(ACCOUNTS.damir.email)
    expect(body.user.role).toBe('BRAND_MANAGER')
  })

  test('POST /login с неверным паролем → 401 INVALID_CREDENTIALS', async ({ request }) => {
    const r = await request.post(`${BACKEND_URL}/api/admin/auth/login`, {
      data: { email: ACCOUNTS.damir.email, password: 'wrong' },
    })
    expect(r.status()).toBe(401)
    expect(((await r.json()) as { code: string }).code).toBe('INVALID_CREDENTIALS')
  })

  test('POST /login case-insensitive email', async ({ request }) => {
    const r = await request.post(`${BACKEND_URL}/api/admin/auth/login`, {
      data: { email: 'Damir@jadran.com', password: ACCOUNTS.damir.password },
    })
    expect(r.ok()).toBe(true)
  })

  test('GET /me с Bearer → user info', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/auth/me`, {
      headers: { Authorization: bearer },
    })
    expect(r.ok()).toBe(true)
    expect(((await r.json()) as { email: string }).email).toBe(ACCOUNTS.damir.email)
  })

  test('GET /me без Bearer → 401', async ({ request }) => {
    // Bug S fix: неаутентифицированный запрос = 401 (не 403), чтобы фронт делал refresh.
    const r = await request.get(`${BACKEND_URL}/api/admin/auth/me`)
    expect(r.status()).toBe(401)
  })
})

test.describe('Backend — Catalog', () => {
  test('GET /catalog/products → seeded 13 продуктов', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/catalog/products`, {
      headers: { Authorization: bearer },
    })
    expect(r.ok()).toBe(true)
    const body = (await r.json()) as unknown[]
    expect(body.length).toBeGreaterThanOrEqual(13)
  })

  test('GET /catalog/brands → агрегированные бренды', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/catalog/brands`, {
      headers: { Authorization: bearer },
    })
    expect(r.ok()).toBe(true)
    const body = (await r.json()) as Array<{ brand: string; productCount: number }>
    expect(body.find((b) => b.brand === 'Jadran-Galenski')).toBeTruthy()
  })

  test('GET /catalog/products/{unknown} → 404 NOT_FOUND', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/catalog/products/nope`, {
      headers: { Authorization: bearer },
    })
    expect(r.status()).toBe(404)
    expect(((await r.json()) as { code: string }).code).toBe('NOT_FOUND')
  })
})

test.describe('Backend — Rules CRUD', () => {
  test('GET /rules → seeded rules', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer },
    })
    expect(r.ok()).toBe(true)
    expect(((await r.json()) as unknown[]).length).toBeGreaterThanOrEqual(6)
  })

  test('GET /rules?type=substitution → только substitution', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/rules?type=substitution`, {
      headers: { Authorization: bearer },
    })
    const body = (await r.json()) as Array<{ type: string }>
    expect(body.every((x) => x.type === 'substitution')).toBe(true)
  })

  test('Bug F: trigger shape mismatch → 400', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.post(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer },
      data: {
        type: 'substitution',
        trigger: { kind: 'mnn', value: [] },
        recommend: 'p_aqm_norm_s',
      },
    })
    expect(r.status()).toBe(400)
  })

  test('Bug H: self-reference (product_any containing recommend) → 400', async ({
    request,
  }) => {
    const bearer = await getBearer(request)
    const r = await request.post(`${BACKEND_URL}/api/admin/rules`, {
      headers: { Authorization: bearer },
      data: {
        type: 'crosssell',
        trigger: { kind: 'product_any', value: ['p_aql_norm_s', 'p_aqm_norm_s'] },
        recommend: 'p_aqm_norm_s',
        bonus: 100,
      },
    })
    expect(r.status()).toBe(400)
  })
})

test.describe('Backend — Promo CRUD', () => {
  test('GET /promo → seeded 5 promos', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.get(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
    })
    expect(r.ok()).toBe(true)
    expect(((await r.json()) as unknown[]).length).toBeGreaterThanOrEqual(5)
  })

  test('POST + PATCH + archive + restore cycle', async ({ request }) => {
    const bearer = await getBearer(request)

    // Create
    const created = await request.post(`${BACKEND_URL}/api/admin/promo`, {
      headers: { Authorization: bearer },
      data: { title: `E2E-cycle-${Date.now()}`, brand: 'Test', status: 'active', budget: 1000 },
    })
    expect(created.ok()).toBe(true)
    const promo = (await created.json()) as { id: string; status: string }
    expect(promo.status).toBe('active')

    // PATCH bonus
    const updated = await request.patch(`${BACKEND_URL}/api/admin/promo/${promo.id}`, {
      headers: { Authorization: bearer },
      data: { budget: 2000 },
    })
    expect(updated.ok()).toBe(true)
    expect(((await updated.json()) as { budget: number }).budget).toBe(2000)

    // Archive
    const archived = await request.post(`${BACKEND_URL}/api/admin/promo/${promo.id}/archive`, {
      headers: { Authorization: bearer },
    })
    expect(archived.ok()).toBe(true)
    expect(((await archived.json()) as { status: string }).status).toBe('archived')

    // Bug L: restore
    const restored = await request.post(`${BACKEND_URL}/api/admin/promo/${promo.id}/restore`, {
      headers: { Authorization: bearer },
    })
    expect(restored.ok()).toBe(true)
    expect(((await restored.json()) as { status: string }).status).toBe('draft')
  })

  test('Bug G: PATCH с status=archived → 400', async ({ request }) => {
    const bearer = await getBearer(request)
    const r = await request.patch(`${BACKEND_URL}/api/admin/promo/pr_001`, {
      headers: { Authorization: bearer },
      data: { status: 'archived' },
    })
    expect(r.status()).toBe(400)
    expect(((await r.json()) as { code: string }).code).toBe('VALIDATION_FAILED')
  })
})
