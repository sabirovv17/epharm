import { test, expect, type Page } from '@playwright/test'
import type { PromoChangesBundle, PromoChangesViewDto, PromoDto } from '../src/lib/api-types'

async function publicationFixture(page: Page, baseURL: string, unavailable = false) {
  let changesStatus = unavailable ? 404 : 0
  const published: PromoDto = {
    id: 'publication-demo',
    title: 'Опубликованная версия',
    status: 'active',
    brand: 'DEMO',
    period: '',
    pharmacies: 42,
    budget: 0,
    spent: 0,
    kpi: '',
    cover: '#D97757',
    medusaProductId: 'demo-offer',
    productName: 'Демо-предложение',
    productImage: null,
    barcode: '0012345678905',
    barcodes: ['0012345678905', '4601164003157'],
    ipartId: null,
    overrideImage: null,
    overrideDescription: null,
    overrideCharacteristics: null,
    price: 990,
    pharmacistBonus: 0,
    dateStart: null,
    dateEnd: null,
    tiers: [],
    createdBy: 'demo',
    createdAt: '2026-10-29T00:00:00Z',
    updatedAt: '2026-10-29T00:00:00Z',
  }
  let bundle: PromoChangesBundle = {
    campaign: {
      title: published.title,
      brand: published.brand,
      period: '',
      budget: 0,
      kpi: '',
      cover: published.cover,
      medusaProductId: published.medusaProductId,
      productName: published.productName,
      productImage: null,
      barcode: published.barcode,
      barcodes: published.barcodes,
      ipartId: null,
      overrideImage: null,
      overrideDescription: null,
      overrideCharacteristics: null,
      pharmacistBonus: 0,
      dateStart: null,
      dateEnd: null,
    },
    rules: {
      replacements: [
        {
          medusaProductId: 'demo-trigger',
          name: 'Демо-триггер',
          triggerKind: 'product',
          barcode: '4603423004936',
          active: true,
          bonus: 0,
        },
      ],
      crossSells: [],
      script: '',
      advantages: [],
      partnerLabel: null,
      comparison: [],
      goalLabel: null,
      goalTarget: null,
      goalBonus: null,
    },
  }
  let revision = 0
  let pending = false
  const writes: { path: string; body: unknown }[] = []
  const blocked: string[] = []
  const view = (): PromoChangesViewDto => ({
    promoId: published.id,
    revision,
    hasPendingChanges: pending,
    bundle,
    conflict: false,
    canSave: true,
    canApply: pending,
    readinessIssues: [],
    updatedAt: null,
    updatedBy: null,
  })
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'publication-user',
        email: 'demo@example.test',
        name: 'Демо HQ',
        role: 'HQ_HEAD',
        company: 'DEMO',
      }),
    )
    localStorage.setItem(
      'epharm.auth.tokens',
      JSON.stringify({
        accessToken: 'synthetic-token',
        refreshToken: 'synthetic-refresh',
        accessTokenExpiresAt: '2099-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
      }),
    )
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (
      url.origin !== new URL(baseURL).origin ||
      url.pathname.startsWith('/src/') ||
      url.pathname.startsWith('/@')
    ) {
      blocked.push(request.url())
      return route.abort()
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname
    const method = request.method()
    if (method === 'GET' && path === '/api/admin/access/me') return route.fulfill({ json: { managed: false, version: null, grants: [], roleCeiling: 'HQ_HEAD', canViewAccounts: false, canManageAccounts: false, canViewMerchInventory: false } })
    const campaignPath = `/api/admin/promo/${published.id}`
    if (method === 'GET' && path === campaignPath) return route.fulfill({ json: published })
    if (method === 'GET' && path === `${campaignPath}/changes`)
      return changesStatus
        ? route.fulfill({
            status: changesStatus,
            json: {
              code: changesStatus === 403 ? 'FORBIDDEN' : 'NOT_FOUND',
              message: 'Подготовка недоступна',
            },
          })
        : route.fulfill({ json: view() })
    if (method === 'GET' && path === '/api/admin/storefront/products/demo-offer')
      return route.fulfill({
        json: {
          id: 'demo-offer',
          name: published.productName,
          price: 990,
          brand: 'DEMO',
          barcode: published.barcode,
          barcodes: published.barcodes,
          images: [],
          keyFacts: [],
          description: null,
        },
      })
    if (method === 'PUT' && path === `${campaignPath}/changes`) {
      const body = request.postDataJSON() as {
        expectedRevision: number
        bundle: PromoChangesBundle
      }
      expect(body.expectedRevision).toBe(revision)
      expect(body.bundle.campaign).not.toHaveProperty('status')
      expect(published.title).toBe('Опубликованная версия')
      writes.push({ path, body })
      bundle = body.bundle
      pending = true
      revision++
      return route.fulfill({ json: view() })
    }
    if (method === 'POST' && path === `${campaignPath}/changes/apply`) {
      const body = request.postDataJSON()
      expect(body).toEqual({ expectedRevision: revision })
      expect(pending).toBe(true)
      expect(published.title).toBe('Опубликованная версия')
      writes.push({ path, body })
      Object.assign(published, bundle.campaign)
      pending = false
      revision++
      return route.fulfill({ json: view() })
    }
    // Apply invalidates published rule and engine caches; either read stays entirely synthetic.
    if (method === 'GET' && path === `${campaignPath}/rules`)
      return route.fulfill({
        json: { promoId: published.id, config: bundle.rules, ruleCount: 1, activeCount: 1 },
      })
    if (method === 'GET' && path === '/api/admin/rules') return route.fulfill({ json: [] })
    blocked.push(`${method} ${path}`)
    return route.abort()
  })
  await page.goto(`/promo/${published.id}`)
  return {
    published,
    writes,
    blocked,
    denyChanges: () => {
      changesStatus = 403
    },
  }
}

test('built client saves a full pending revision, blocks dirty Apply, and publishes only after explicit review', async ({
  page,
  baseURL,
}) => {
  const state = await publicationFixture(page, baseURL!)
  const title = page.getByLabel('Название', { exact: true })
  await expect(title).toBeEnabled()
  await title.fill('Подготовленная версия')
  await page.getByTestId('promo-changes-save').click()
  await expect(page.getByTestId('promo-publication-state')).toContainText(
    'Есть подготовленные изменения',
  )
  expect(state.published.title).toBe('Опубликованная версия')
  expect(state.writes).toHaveLength(1)
  await page.getByTestId('pr-goal-label').fill('Несохранённая цель')
  await expect(
    page.getByRole('button', { name: 'Проверить и применить', exact: true }),
  ).toBeDisabled()
  await page.getByTestId('pr-goal-label').fill('')
  await page.getByRole('button', { name: 'Проверить и применить', exact: true }).click()
  const review = page.getByRole('dialog')
  await expect(review).toContainText('Применяется сохранённая ревизия 1')
  await expect(review).toContainText('Подготовленная версия')
  await expect(review).toContainText('Конкретный товар: Демо-триггер')
  await expect(review).toContainText('Бонус основного товара: 0 ₸')
  expect(state.published.title).toBe('Опубликованная версия')
  await review.getByRole('button', { name: 'Применить изменения', exact: true }).click()
  await expect(page.getByTestId('promo-publication-state')).toContainText(
    'Подготовленных изменений нет',
  )
  expect(state.published.title).toBe('Подготовленная версия')
  expect(state.writes).toHaveLength(2)
  expect(state.blocked).toEqual([])
  await page.screenshot({
    path: 'test-results/artifact/promo-publication-applied.png',
    fullPage: true,
  })
})

test('built client on unsupported pending API keeps published data read-only and never falls back to legacy publication', async ({
  page,
  baseURL,
}) => {
  const state = await publicationFixture(page, baseURL!, true)
  await expect(page.getByRole('alert')).toContainText('Подготовка изменений недоступна')
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Опубликованная версия')
  await expect(page.getByLabel('Название', { exact: true })).toBeDisabled()
  await expect(page.getByTestId('promo-changes-save')).toBeDisabled()
  await expect(page.getByTestId('promo-rules-editor')).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.blocked).toEqual([])
})

test('a later 403 scrubs the previously visible prepared form and never persists its bundle or capabilities', async ({
  page,
  baseURL,
}) => {
  const state = await publicationFixture(page, baseURL!)
  const title = page.getByLabel('Название', { exact: true })
  await expect(title).toBeEnabled()
  await title.fill('PRIVATE-PENDING-BODY')
  await page.getByTestId('promo-changes-save').click()
  await expect(page.getByTestId('promo-publication-state')).toContainText(
    'Есть подготовленные изменения',
  )
  await expect(title).toHaveValue('PRIVATE-PENDING-BODY')
  const persisted = await page.evaluate(() => localStorage.getItem('epharm.query.cache'))
  expect(persisted).not.toContain('PRIVATE-PENDING-BODY')
  expect(persisted).not.toContain('canApply')
  state.denyChanges()
  await page.getByRole('button', { name: 'Загрузить сохранённую версию' }).click()
  await expect(page.getByRole('alert')).toContainText('Подготовка изменений недоступна')
  await expect(title).toHaveValue('Опубликованная версия')
  await expect(title).toBeDisabled()
  await expect(page.getByTestId('promo-rules-editor')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: 'Проверить и применить', exact: true }),
  ).toHaveCount(0)
  expect(state.writes).toHaveLength(1)
  expect(state.published.title).toBe('Опубликованная версия')
  expect(state.blocked).toEqual([])
})
