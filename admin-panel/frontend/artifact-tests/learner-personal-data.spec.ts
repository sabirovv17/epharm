import { test as base, expect, type Page, type Route } from '@playwright/test'

const tokenKey = 'epharm.learner.tokens'
const accounts = {
  a: { id: 'learner-a', name: 'Альфа', iin: '000101500011', balance: 36_941 },
  b: { id: 'learner-b', name: 'Бета', iin: '000101500012', balance: 94_126 },
}
const tokens = (id: 'a' | 'b') => ({ accessToken: `artifact-learner-${id}`, refreshToken: `artifact-refresh-${id}` })
const overview = { total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [], certificates: [], notifications: [] }
const recommendations = {
  items: [{
    campaign: { id: 'artifact-campaign', title: 'Действующая группа', dateStart: null, dateEnd: null },
    type: 'substitution', trigger: { kind: 'acc_group', value: 'artifact-acc', label: 'Группа АСС' },
    triggerProducts: [],
    offers: ['Первое предложение', 'Второе предложение'].map((name, index) => ({
      ruleId: `artifact-rule-${index}`, bonusKzt: index * 500, script: 'Справочная подсказка', advantages: ['Проверенные данные'],
      product: { id: `artifact-product-${index}`, name, brand: 'Тест', mnn: 'Тестовая МНН', volume: 'Упаковка',
        barcodes: [], ipartId: null, catalogPriceKzt: 2500, catalogUpdatedAt: '2026-10-21T00:00:00Z' },
    })),
  }],
  page: 0, size: 20, total: 1, currency: 'KZT',
}

interface LearnerApi {
  requests: { path: string; authorization: string }[]
  blocked: string[]
  personalFailure?: { status: number; code: string }
  firstBalanceWait?: Promise<void>
  firstBalanceFinished: boolean
  firstBalanceUnauthorized?: boolean
  firstRefreshWait?: Promise<void>
  firstRefreshFinished: boolean
  firstLogoutWait?: Promise<void>
  firstLogoutFinished: boolean
  trainingOverview?: unknown
}

async function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, json: body, headers: { 'Cache-Control': 'no-store' } })
}

const test = base.extend<{ learnerApi: LearnerApi; learnerSession: boolean }>({
  learnerSession: [true, { option: true }],
  learnerApi: [async ({ page, baseURL, learnerSession }, use) => {
    const api: LearnerApi = { requests: [], blocked: [], firstBalanceFinished: false, firstRefreshFinished: false, firstLogoutFinished: false }
    const origin = new URL(baseURL!).origin
    await page.addInitScript(({ key, initialTokens }) => {
      localStorage.removeItem('epharm.auth.user')
      localStorage.removeItem('epharm.auth.tokens')
      sessionStorage.removeItem(key)
      localStorage.removeItem(key)
      if (initialTokens) localStorage.setItem(key, JSON.stringify(initialTokens))
    }, { key: tokenKey, initialTokens: learnerSession ? tokens('a') : null })
    page.on('pageerror', (error) => api.blocked.push(`Browser error: ${error.message}`))
    await page.route('**/*', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      const method = request.method()
      if (url.origin !== origin || path.startsWith('/src/') || path.startsWith('/@')) {
        api.blocked.push(`${method} ${url.href}`)
        return route.abort('blockedbyclient')
      }
      if (!path.startsWith('/api/')) return route.continue()
      const authorization = request.headers().authorization ?? ''
      api.requests.push({ path, authorization })
      if (method === 'POST' && path === '/api/mobile/auth/activation/status') {
        return json(route, { passwordSet: true, phoneMasked: '+7 (***) ***-**-12' })
      }
      if (method === 'POST' && path === '/api/mobile/auth/login') {
        const id = request.postDataJSON().iin === accounts.b.iin ? 'b' : 'a'
        return json(route, { tokens: tokens(id), pharmacist: accounts[id] })
      }
      if (method === 'POST' && path === '/api/mobile/auth/refresh') {
        const id = request.postDataJSON().refreshToken === tokens('b').refreshToken ? 'b' : 'a'
        if (id === 'a' && api.firstRefreshWait) await api.firstRefreshWait
        try {
          return await json(route, { tokens: tokens(id) })
        } finally {
          if (id === 'a') api.firstRefreshFinished = true
        }
      }
      if (method === 'POST' && path === '/api/mobile/auth/logout') {
        if (api.firstLogoutWait) await api.firstLogoutWait
        try {
          return await route.fulfill({ status: 204 })
        } finally {
          api.firstLogoutFinished = true
        }
      }
      if (method === 'GET' && path === '/api/mobile/promotions') return json(route, [])
      const id = (['a', 'b'] as const).find((candidate) => authorization === `Bearer ${tokens(candidate).accessToken}`)
      if (id && method === 'GET') {
        if (path === '/api/mobile/auth/me') {
          return json(route, { ...accounts[id], balance: 889_999, status: 'active', tier: 'Silver',
            phone: '+77000000000', pharmacyName: 'Тестовая аптека', city: 'Алматы' })
        }
        if (path === '/api/mobile/training') return json(route, api.trainingOverview ?? overview)
        if (path === '/api/mobile/me/balance' || path === '/api/mobile/me/recommendations') {
          if (api.personalFailure) return json(route, { code: api.personalFailure.code, message: 'Личные данные недоступны' }, api.personalFailure.status)
          if (path.endsWith('/balance')) {
            if (id === 'a' && api.firstBalanceUnauthorized) {
              api.firstBalanceUnauthorized = false
              return json(route, { code: 'UNAUTHORIZED', message: 'Требуется обновление сессии' }, 401)
            }
            if (id === 'a' && api.firstBalanceWait) await api.firstBalanceWait
            try {
              return await json(route, { balanceKzt: accounts[id].balance, currency: 'KZT' })
            } finally {
              if (id === 'a') api.firstBalanceFinished = true
            }
          }
          return json(route, recommendations)
        }
      }
      api.blocked.push(`${method} ${url.href}`)
      return route.abort('blockedbyclient')
    })
    await use(api)
    expect(api.blocked, 'All API operations must be mocked; HQ, external and development-server traffic is forbidden').toEqual([])
  }, { auto: true }],
})

async function expectOffers(page: Page) {
  await expect(page.getByRole('heading', { name: 'Активные рекомендации', exact: true })).toBeVisible()
  await expect(page.getByText('Действующая группа', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Первое предложение', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Второе предложение', exact: true })).toBeVisible()
}

for (const viewport of [{ name: 'desktop', width: 1440, height: 1000 }, { name: 'mobile', width: 390, height: 844 }]) {
  test.describe(viewport.name, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } })
    test('training has its own route, home keeps the current course and card linking stays a safe preview', async ({ page, learnerApi }, testInfo) => {
      const assignments = [
        { id: 'next', programName: 'Следующий курс', status: 'assigned', progressPct: 0 },
        { id: 'current', programName: 'Текущий учебный курс', status: 'in_progress', progressPct: 40 },
        { id: 'done', programName: 'Завершённый учебный курс', status: 'completed', progressPct: 100 },
        { id: 'cancelled', programName: 'Отменённый учебный курс', status: 'cancelled', progressPct: 0 },
      ].map((assignment) => ({ ...assignment, format: 'online', priority: 'normal', pharmacyName: 'Тестовая аптека', city: 'Алматы', stages: [] }))
      learnerApi.trainingOverview = { ...overview, total: 4, inProgress: 1, completed: 1, assignments }
      await page.goto('/learn')
      await expect(page.getByRole('heading', { name: 'Текущий курс', exact: true })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Текущий учебный курс', exact: true })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Следующий курс', exact: true })).toHaveCount(0)
      await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-current-course.png`), fullPage: true, animations: 'disabled' })
      await page.getByRole('button', { name: 'Обучение', exact: true }).click()
      await expect(page).toHaveURL(/\/learn\/training$/)
      await expect(page.getByRole('button', { name: 'Обучение', exact: true })).toHaveAttribute('aria-current', 'page')
      await expect(page.getByRole('heading', { name: 'Все доступные курсы', exact: true })).toBeVisible()
      for (const assignment of assignments.filter((item) => item.status !== 'cancelled')) {
        await expect(page.getByRole('heading', { name: assignment.programName, exact: true })).toBeVisible()
      }
      await expect(page.getByRole('heading', { name: 'Отменённый учебный курс', exact: true })).toHaveCount(0)
      await page.reload()
      await expect(page.getByRole('heading', { name: 'Все доступные курсы', exact: true })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-all-courses.png`), fullPage: true, animations: 'disabled' })
      await page.getByRole('button', { name: 'Профиль', exact: true }).click()
      const card = page.getByRole('region', { name: 'Банковская карта', exact: true })
      await expect(card.getByText('Не подключена', { exact: true })).toBeVisible()
      await expect(page.getByText(/36\s*941\s*₸/).first()).toBeVisible()
      const requestCount = learnerApi.requests.length
      const storage = await page.evaluate(() => JSON.stringify({ ...localStorage }))
      await card.getByRole('button', { name: 'Привязать карту', exact: true }).click()
      await expect(card.getByRole('status')).toHaveText('Привязка карты появится позже. Сейчас реквизиты не запрашиваются, выплаты на карту недоступны.')
      await expect(card.locator('input, textarea, select, iframe, form')).toHaveCount(0)
      await expect(card.getByText('Не подключена', { exact: true })).toBeVisible()
      expect(learnerApi.requests).toHaveLength(requestCount)
      expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).toBe(storage)
      await expect(page.getByText('Карта привязана', { exact: true })).toHaveCount(0)
      await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-card-preview.png`), fullPage: true, animations: 'disabled' })
    })
    test('the built AppRouter keeps learner navigation and direct reload outside HQ login', async ({ page, learnerApi }, testInfo) => {
      await page.goto('/learn')
      await expect(page.getByText(/36\s*941\s*₸/).first()).toBeVisible()
      await expect(page.getByText(/889\s*999\s*₸/)).toHaveCount(0)
      await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-learner-home.png`), fullPage: true, animations: 'disabled' })
      await page.getByRole('button', { name: 'Рекомендации', exact: true }).click()
      await expect(page).toHaveURL(/\/learn\/recommendations$/)
      await expect(page.getByRole('button', { name: 'Рекомендации', exact: true })).toHaveAttribute('aria-current', 'page')
      await expect(page.getByRole('button', { name: 'Главная', exact: true })).not.toHaveAttribute('aria-current', 'page')
      await expectOffers(page)
      await page.screenshot({ path: testInfo.outputPath(`${viewport.name}-learner-recommendations.png`), fullPage: true, animations: 'disabled' })
      await page.reload()
      await expect(page).toHaveURL(/\/learn\/recommendations$/)
      await expectOffers(page)
      expect(await page.evaluate(() => localStorage.getItem('epharm.auth.user'))).toBeNull()
      expect(await page.evaluate(() => localStorage.getItem('epharm.auth.tokens'))).toBeNull()
      expect(learnerApi.requests.some((request) => request.path === '/api/mobile/me/balance')).toBe(true)
      expect(learnerApi.requests.some((request) => request.path === '/api/mobile/me/recommendations')).toBe(true)
      expect(learnerApi.requests.filter((request) => request.path.startsWith('/api/mobile/me/'))
        .every((request) => request.authorization === `Bearer ${tokens('a').accessToken}`)).toBe(true)
    })
  })
}

test.describe('signed out learner', () => {
  test.use({ learnerSession: false })
  test('a direct recommendations URL opens pharmacist login and never the HQ email form', async ({ page, learnerApi }) => {
    await page.goto('/learn/recommendations')
    await expect(page.getByRole('heading', { name: 'Вход для фармацевта', exact: true })).toBeVisible()
    await expect(page.getByRole('textbox', { name: 'ИИН', exact: true })).toBeVisible()
    await expect(page).toHaveURL(/\/learn\/recommendations$/)
    await expect(page.getByRole('textbox', { name: 'Email', exact: true })).toHaveCount(0)
    expect(learnerApi.requests).toEqual([])
  })
})

for (const failure of [
  { status: 401, code: 'UNAUTHORIZED', message: 'Подтвердите личный вход ePharm, чтобы загрузить данные.' },
  { status: 403, code: 'USER_NOT_ACTIVE', message: 'Аккаунт ePharm не активен. Обратитесь к администратору.' },
  { status: 403, code: 'FORBIDDEN', message: 'Для просмотра нужен личный аккаунт фармацевта ePharm.' },
]) {
  test(`personal API ${failure.status} ${failure.code} stays an explicit error, not an empty feed or stale wallet`, async ({ page, learnerApi }) => {
    learnerApi.personalFailure = failure
    await page.goto('/learn')
    await expect(page.getByText(failure.message, { exact: true }).first()).toBeVisible()
    await expect(page.getByText(/889\s*999\s*₸/)).toHaveCount(0)
    await expect(page.getByText(/36\s*941\s*₸/)).toHaveCount(0)
    await page.getByRole('button', { name: 'Рекомендации', exact: true }).click()
    await expect(page).toHaveURL(/\/learn\/recommendations$/)
    await expect(page.getByText(failure.message, { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Активных рекомендаций сейчас нет.', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Действующая группа', { exact: true })).toHaveCount(0)
  })
}

for (const delayed of ['balance', 'refresh', 'logout'] as const) {
test(`logout and account switch ignore a late ${delayed} and clear both token stores`, async ({ page, learnerApi }) => {
  let releaseFirstResponse!: () => void
  const pending = new Promise<void>((resolve) => { releaseFirstResponse = resolve })
  if (delayed === 'balance') learnerApi.firstBalanceWait = pending
  else if (delayed === 'refresh') {
    learnerApi.firstBalanceUnauthorized = true
    learnerApi.firstRefreshWait = pending
  } else learnerApi.firstLogoutWait = pending
  try {
    await page.goto('/learn')
    if (delayed === 'logout') await expect(page.getByText(/36\s*941\s*₸/).first()).toBeVisible()
    else {
      const pendingPath = delayed === 'balance' ? '/api/mobile/me/balance' : '/api/mobile/auth/refresh'
      await expect.poll(() => learnerApi.requests.some((request) => request.path === pendingPath)).toBe(true)
    }
    await page.getByRole('button', { name: 'Выйти', exact: true }).first().click()
    await expect(page.getByRole('heading', { name: 'Вход для фармацевта', exact: true })).toBeVisible()
    expect(await page.evaluate((key) => [localStorage.getItem(key), sessionStorage.getItem(key)], tokenKey)).toEqual([null, null])
    await expect(page.getByText(/36\s*941\s*₸/)).toHaveCount(0)
    await page.getByRole('textbox', { name: 'ИИН', exact: true }).fill(accounts.b.iin)
    await page.getByRole('button', { name: 'Продолжить', exact: true }).click()
    await page.getByLabel('Пароль', { exact: true }).fill('SyntheticFarm123!')
    await page.getByRole('button', { name: 'Войти', exact: true }).click()
    await expect(page.getByText(/94\s*126\s*₸/).first()).toBeVisible()
    releaseFirstResponse()
    await expect.poll(() => delayed === 'balance' ? learnerApi.firstBalanceFinished
      : delayed === 'refresh' ? learnerApi.firstRefreshFinished : learnerApi.firstLogoutFinished).toBe(true)
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
    await expect(page.getByText(/94\s*126\s*₸/).first()).toBeVisible()
    await expect(page.getByText(/36\s*941\s*₸/)).toHaveCount(0)
    await expect(page.getByText('Альфа', { exact: true })).toHaveCount(0)
    expect(await page.evaluate((key) => JSON.parse(localStorage.getItem(key) || '{}').accessToken, tokenKey)).toBe(tokens('b').accessToken)
  } finally {
    releaseFirstResponse()
  }
})
}
