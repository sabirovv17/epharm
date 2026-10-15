import { test as base, expect, type Route } from '@playwright/test'

export const scanProduct = {
  id: 'artifact-scan-product', name: 'Товар для сканирования', brand: 'Артефакт',
  mnn: null, rxOtc: 'OTC', price: 990, currency: 'KZT', imageUrl: null,
  barcode: '4601164003157', ipartId: null, category: null,
}
export const offerProduct = {
  ...scanProduct, id: 'artifact-offer-product', name: 'Предлагаемый товар', barcode: null,
}

export interface DraftRequest {
  title: string
  status: string
  medusaProductId: string
  initialRecommendation: {
    type: string
    trigger: { triggerKind: string; triggerValue?: string; medusaProductId: string; active: boolean; barcode?: string }
  }
}

interface ArtifactApi {
  taxonomyRequests: URL[]
  drafts: DraftRequest[]
  blockedRequests: string[]
}

function campaign(payload?: DraftRequest) {
  return {
    id: 'artifact-draft', title: payload?.title ?? 'Артефакт: черновик', status: 'draft',
    brand: offerProduct.brand, period: '', pharmacies: 0, budget: 0, spent: 0, kpi: '', cover: '#D97757',
    medusaProductId: offerProduct.id, productName: offerProduct.name, productImage: null,
    barcode: null, ipartId: null, overrideImage: null, overrideDescription: null,
    overrideCharacteristics: null, price: offerProduct.price, pharmacistBonus: 0,
    dateStart: null, dateEnd: null, tiers: [], createdBy: 'artifact-user',
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
  }
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

export const test = base.extend<{ artifactApi: ArtifactApi }>({
  artifactApi: [async ({ page, baseURL }, use) => {
    const state: ArtifactApi = { taxonomyRequests: [], drafts: [], blockedRequests: [] }
    const origin = new URL(baseURL!).origin
    await page.route('**/*', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const method = request.method()
      const path = url.pathname
      if (url.origin !== origin || path.startsWith('/src/') || path.startsWith('/@')) {
        state.blockedRequests.push(`${method} ${request.url()}`)
        return route.abort('blockedbyclient')
      }
      // All API calls, including expected writes, are fulfilled in memory.
      // An unexpected API route or external asset never reaches its server.
      if (path.startsWith('/api/')) {
        if (method === 'POST' && path === '/api/admin/auth/login') {
          return json(route, {
            user: { id: 'artifact-user', email: 'artifact@example.invalid', name: 'Проверка артефакта', role: 'BRAND_MANAGER', company: 'Артефакт' },
            tokens: { accessToken: 'artifact-only-token', refreshToken: 'artifact-only-refresh',
              accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' },
          })
        }
        if (method === 'GET' && ['/api/admin/rules', '/api/admin/catalog/products'].includes(path)) return json(route, [])
        if (path === '/api/admin/promo') {
          if (method === 'GET') return json(route, [])
          if (method === 'POST') {
            const payload = request.postDataJSON() as DraftRequest
            state.drafts.push(payload)
            return json(route, campaign(payload), 201)
          }
        }
        if (method === 'GET' && path === '/api/admin/promo/artifact-draft') return json(route, campaign(state.drafts.at(-1)))
        if (method === 'GET' && path === '/api/admin/promo/artifact-draft/rules') {
          return json(route, { promoId: 'artifact-draft', ruleCount: 0, activeCount: 0,
            config: { replacements: [], crossSells: [], script: '', advantages: [], partnerLabel: null,
              comparison: [], goalLabel: null, goalTarget: null, goalBonus: null } })
        }
        if (method === 'GET' && path === '/api/admin/storefront/products') {
          const query = url.searchParams.get('q')?.toLocaleLowerCase() ?? ''
          const items = [scanProduct, offerProduct].filter((product) => product.name.toLocaleLowerCase().includes(query))
          return json(route, { items, total: items.length, limit: 50, offset: 0 })
        }
        if (method === 'GET' && [scanProduct, offerProduct].some((product) => path === `/api/admin/storefront/products/${product.id}`)) {
          const product = path.endsWith(scanProduct.id) ? scanProduct : offerProduct
          return json(route, { ...product, description: null, images: [], keyFacts: [] })
        }
        if (method === 'GET' && path === '/api/admin/catalog/trigger-options') {
          state.taxonomyRequests.push(url)
          const kind = url.searchParams.get('kind')
          return json(route, {
            snapshot: { sha256: 'artifact-snapshot', sourceName: 'Каталог АСС.xlsx', itemCount: 50, barcodeCount: 40, importedAt: '2026-10-01T00:00:00Z' },
            options: [{ key: `${kind}:sorbents`, label: 'Сорбенты', count: 12,
              parentLabel: kind === 'acc_subgroup' ? 'Желудочно-кишечные препараты' : null }],
          })
        }
        state.blockedRequests.push(`${method} ${request.url()}`)
        return route.abort('blockedbyclient')
      }
      return route.continue()
    })

    await use(state)
    expect(state.blockedRequests, 'Unknown API calls, external resources, and dev-server source modules must remain blocked').toEqual([])
  }, { auto: true }],
})

export { expect }
