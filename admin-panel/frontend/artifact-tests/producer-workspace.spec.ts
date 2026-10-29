import { expect, test, type Page } from '@playwright/test'
import type { Placement } from '../src/features/merch/placements-api'

const company = {
  id: 'demo-producer',
  name: 'Демо-производитель',
  phoneMasked: '+7 *** 0000',
  profileComplete: true,
}
const tokens = {
  accessToken: 'synthetic-producer-token',
  refreshToken: 'synthetic-producer-refresh-token-0000000000',
  tokenType: 'Bearer',
  expiresIn: 900,
  sessionExpiresAt: '2099-01-01T00:00:00Z',
  customer: company,
}
const branch = {
  id: 'demo-branch',
  name: 'Демо-аптека',
  city: 'Демо-город',
  address: 'Демо-адрес, 1',
}
const place = {
  shelfId: 'demo-shelf',
  code: 'DEMO-01',
  saleMode: 'whole',
  whole: { price: 1000, available: true },
  left: null,
  right: null,
}
const basePlan: Placement = {
  id: 'demo-plan',
  producerId: company.id,
  producerName: company.name,
  advertiserName: company.name,
  placementMonth: '2026-11-01',
  status: 'draft',
  origin: 'request',
  revision: 1,
  totalAmount: 1000,
  heldUntil: null,
  reservationState: 'closed',
  reservationBusinessDays: 3,
  reservationTimezone: 'Asia/Almaty',
  reviewNote: null,
  note: null,
  items: [
    {
      id: 'demo-row',
      shelfId: place.shelfId,
      branchId: branch.id,
      shelfCode: place.code,
      placementMonth: '2026-11-01',
      portion: 'whole',
      status: 'draft',
      monthlyPrice: 1000,
    },
  ],
  canEdit: true,
  canSubmit: true,
  canApprove: false,
  canReject: false,
  canCancel: true,
  createdAt: '2026-10-29T00:00:00Z',
  updatedAt: '2026-10-29T00:00:00Z',
  producerBindingStatus: 'verified',
}

async function producerFixture(page: Page, baseURL: string, disabled = false, incomplete = false) {
  const blocked: string[] = []
  const writes: { path: string; body: unknown }[] = []
  let plan: Placement | null = null
  let ownCompany = {
    ...company,
    name: incomplete ? null : company.name,
    profileComplete: !incomplete,
  }
  let companyRevision = 2
  let legal = {
    companyName: ownCompany.name,
    legalName: null,
    bin: null,
    legalAddress: null,
    actualAddress: null,
    bankName: null,
    bic: null,
    iban: null,
    kbe: null,
    directorName: null,
    directorPosition: null,
    contactPosition: null,
    contactEmail: null,
    vatRegistered: false,
  }
  await page.addInitScript(() => {
    localStorage.setItem('epharm.auth.tokens', 'SYNTHETIC-ADMIN-SENTINEL')
    localStorage.setItem('epharm.learner.tokens', 'SYNTHETIC-LEARNER-SENTINEL')
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (
      url.origin !== new URL(baseURL).origin ||
      path.startsWith('/src/') ||
      path.startsWith('/@')
    ) {
      blocked.push(request.url())
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    if (!path.startsWith('/api/producer/')) {
      blocked.push(path)
      return route.abort()
    }
    const method = request.method()
    if (path.endsWith('/sms/request'))
      return route.fulfill(
        disabled
          ? { status: 503, json: { message: 'Disabled' } }
          : {
              json: {
                challengeId: 'demo-challenge',
                ttlSeconds: 300,
                retryAfterSeconds: 60,
                phoneMasked: '+7 *** 0000',
                message: 'Если номер зарегистрирован, код отправлен',
              },
            },
      )
    if (path.endsWith('/sms/verify')) {
      expect(request.headers().authorization).toBeUndefined()
      return route.fulfill({ json: { ...tokens, customer: ownCompany } })
    }
    expect(request.headers().authorization).toBe('Bearer synthetic-producer-token')
    if (path === '/api/producer/auth/me') return route.fulfill({ json: ownCompany })
    if (path === '/api/producer/profile') {
      if (method === 'PUT') {
        const body = request.postDataJSON()
        expect(body.expectedRevision).toBe(companyRevision)
        expect(body.profile).not.toHaveProperty('phone')
        expect(body.profile).not.toHaveProperty('password')
        writes.push({ path, body })
        legal = body.profile
        companyRevision++
        ownCompany = {
          ...ownCompany,
          name: legal.companyName,
          profileComplete: !!legal.companyName,
        }
      }
      return route.fulfill({
        json: {
          id: company.id,
          revision: companyRevision,
          profile: legal,
          profileComplete: ownCompany.profileComplete,
        },
      })
    }
    if (path === '/api/producer/branches')
      return route.fulfill({ json: { items: [branch], total: 1, limit: 50, offset: 0 } })
    if (path.endsWith('/availability'))
      return route.fulfill({
        json: {
          branchId: branch.id,
          placementMonth: '2026-11-01',
          paymentStatus: 'not_connected',
          items: [place],
        },
      })
    if (path === '/api/producer/placements' && method === 'GET')
      return route.fulfill({
        json: { items: plan ? [plan] : [], total: plan ? 1 : 0, limit: 50, offset: 0 },
      })
    if (path === '/api/producer/placements' && method === 'POST') {
      const body = request.postDataJSON()
      expect(body).not.toHaveProperty('producerId')
      expect(body).not.toHaveProperty('price')
      writes.push({ path, body })
      plan = { ...basePlan, placementMonth: body.placementMonth }
      return route.fulfill({ json: plan })
    }
    if (path === '/api/producer/placements/demo-plan' && method === 'GET')
      return route.fulfill({ json: plan })
    if (path === '/api/producer/placements/demo-plan/submit' && method === 'POST') {
      expect(request.postDataJSON()).toEqual({ expectedRevision: 1, reason: null })
      writes.push({ path, body: request.postDataJSON() })
      plan = {
        ...plan!,
        revision: 2,
        status: 'pending',
        canEdit: false,
        canSubmit: false,
        reservationState: 'active',
        heldUntil: '2026-11-03T12:00:00Z',
        items: plan!.items.map((row) => ({ ...row, status: 'pending' })),
      }
      return route.fulfill({ json: plan })
    }
    blocked.push(`${method} ${path}`)
    return route.abort()
  })
  return { blocked, writes }
}

test('external disabled SMS is honest and never falls into an admin or pharmacist login', async ({
  page,
  baseURL,
}) => {
  const state = await producerFixture(page, baseURL!, true)
  await page.goto('/producer/login')
  await page.getByLabel('Телефон производителя').fill('+70000000000')
  await page.getByRole('button', { name: 'Получить SMS-код' }).click()
  await expect(page.getByRole('alert')).toContainText('временно недоступен')
  await expect(page.getByRole('complementary', { name: 'Основная навигация' })).toHaveCount(0)
  expect(await page.evaluate(() => sessionStorage.getItem('epharm.producer.session.v1'))).toBeNull()
  expect(state.blocked).toEqual([])
})

test('external SMS session creates its own draft and explicitly submits a hold without HQ controls', async ({
  page,
  baseURL,
}, testInfo) => {
  const state = await producerFixture(page, baseURL!)
  await page.setViewportSize({ width: 1280, height: 1000 })
  await page.goto('/producer/login')
  await page.getByLabel('Телефон производителя').fill('+70000000000')
  await page.getByRole('button', { name: 'Получить SMS-код' }).click()
  await page.getByLabel('SMS-код производителя').fill('1234')
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Мои размещения' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Фильтр производителя' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Новый медиаплан' }).click()
  await page.getByRole('combobox', { name: 'Аптека для размещения' }).selectOption(branch.id)
  await page.getByRole('combobox', { name: 'Часть места DEMO-01' }).selectOption('whole')
  expect(state.writes).toEqual([])
  await page.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(page.getByText('Сумма по данным сервера: 1 000 ₸')).toBeVisible()
  await page.getByRole('button', { name: 'Отправить на подтверждение' }).click()
  expect(state.writes).toHaveLength(1)
  await page.getByRole('button', { name: 'Подтвердить действие' }).click()
  await expect(page.getByText(/Ожидает подтверждения · Версия 2/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Подтвердить размещение' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Служебное размещение/ })).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('producer-own-placement.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(await page.evaluate(() => localStorage.getItem('epharm.auth.tokens'))).toBe(
    'SYNTHETIC-ADMIN-SENTINEL',
  )
  expect(await page.evaluate(() => localStorage.getItem('epharm.learner.tokens'))).toBe(
    'SYNTHETIC-LEARNER-SENTINEL',
  )
  expect(state.writes).toHaveLength(2)
  expect(state.blocked).toEqual([])
})

test('internal placements open for an explicit regional capability without inventory permissions', async ({
  page,
  baseURL,
}, testInfo) => {
  const blocked: string[] = []
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'demo-regional',
        role: 'REGIONAL_MANAGER',
        name: 'Демо МАС',
        email: 'demo@example.test',
        company: 'DEMO',
      }),
    )
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-admin',
        refreshToken: 'synthetic-admin-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
      }),
    )
  })
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/admin/access/me')
      return route.fulfill({
        json: {
          managed: true,
          version: 1,
          grants: [],
          roleCeiling: 'REGIONAL_MANAGER',
          canViewAccounts: false,
          canManageAccounts: false,
          canViewMerchInventory: false,
          canViewMerchPlacements: true,
          canViewMerchTasks: false,
        },
      })
    if (path === '/api/admin/merch/placements/producers')
      return route.fulfill({ json: [{ ...company, active: true }] })
    if (path === '/api/admin/merch/placements')
      return route.fulfill({ json: { items: [basePlan], total: 1, limit: 50, offset: 0 } })
    if (path === '/api/admin/merch/placements/demo-plan')
      return route.fulfill({ json: { ...basePlan, totalAmount: null, canSubmit: false } })
    blocked.push(path)
    return route.abort()
  })
  await page.goto('/merch/placements')
  await expect(page.getByRole('heading', { name: 'Кабинет производителя' })).toBeVisible()
  const sidebar = page.getByRole('complementary', { name: 'Основная навигация' })
  await expect(sidebar.getByRole('button', { name: 'Календарь и доступность' })).toHaveCount(0)
  await expect(
    sidebar.getByRole('button', { name: 'Кабинет производителя', exact: true }),
  ).toBeEnabled()
  await page.getByRole('button', { name: 'Открыть', exact: true }).click()
  await expect(page.getByText('Сумма по данным сервера: —')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить на подтверждение' })).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('internal-placement-scope.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(blocked).toEqual([])
})

test('built training video is an actual MP4 asset rather than an SPA fallback', async ({
  request,
  baseURL,
}) => {
  expect(['127.0.0.1', 'localhost']).toContain(new URL(baseURL!).hostname)
  const response = await request.head(`${baseURL}/training/fire-safety-training-video.mp4`)
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toContain('video/mp4')
  expect(Number(response.headers()['content-length'])).toBeGreaterThan(1_000_000)
})

test('a producer with a truthful empty company name completes only its own profile', async ({
  page,
  baseURL,
}, testInfo) => {
  const state = await producerFixture(page, baseURL!, false, true)
  await page.goto('/producer/login')
  await page.getByLabel('Телефон производителя').fill('+70000000000')
  await page.getByRole('button', { name: 'Получить SMS-код' }).click()
  await page.getByLabel('SMS-код производителя').fill('1234')
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Название компании не заполнено')
  await page.getByRole('button', { name: 'Заполнить профиль компании' }).click()
  await page.getByLabel('Название компании', { exact: true }).fill('Демо-организация')
  await page.screenshot({
    path: testInfo.outputPath('producer-company-profile.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: 'Сохранить профиль' }).click()
  await expect(page.getByRole('heading', { name: 'Мои размещения' })).toBeVisible()
  await expect(page.getByText('Название компании не заполнено.', { exact: false })).toHaveCount(0)
  expect(state.writes).toHaveLength(1)
  expect(state.blocked).toEqual([])
})
