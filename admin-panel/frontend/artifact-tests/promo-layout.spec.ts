import { expect, test, type Page } from '@playwright/test'

async function campaignFixture(page: Page, baseURL: string) {
  const blocked: string[] = []
  const name = 'Длинное название лекарственного препарата в таблетках с подробным описанием упаковки № 100'
  const pair = (id: string) => ({
    medusaProductId: id,
    name: `${name} ${id}`,
    barcode: '4601164003157',
    brand: 'Производитель с длинным названием',
    active: true,
    triggerConditions: [{ kind: 'acc_mnn', value: 'long-inn', label: `МНН ${name.replaceAll(' ', '')}` }],
    script: 'Пояснение фармацевту с подробным описанием рекомендации.',
    comparison: [{ label: 'Параметр сравнения', triggerValue: 'Подробное исходное значение', recommendValue: 'Подробное новое значение', recommendHighlight: true }],
    additionalRecommendations: [{ medusaProductId: `${id}-offer`, name, price: 12500, bonus: 200 }],
  })
  const promo = {
    id: 'layout-demo', title: name, status: 'active', brand: 'DEMO', period: '',
    pharmacies: 532, budget: 1_000_000, spent: 20_000, kpi: '', cover: '#008069',
    medusaProductId: 'layout-offer', productName: name, productImage: null,
    barcode: '4601164003157', barcodes: ['4601164003157'], ipartId: null,
    overrideImage: null, overrideDescription: null, overrideCharacteristics: null,
    price: 12500, pharmacistBonus: 200, dateStart: null, dateEnd: null, tiers: [],
    createdBy: 'demo', createdAt: '2026-11-03T00:00:00Z', updatedAt: '2026-11-03T00:00:00Z',
  }
  const rules = {
    replacements: [pair('replacement')], crossSells: [pair('cross-sell')],
    script: '', advantages: [], partnerLabel: null, comparison: [],
    goalLabel: null, goalTarget: null, goalBonus: null,
  }
  await page.addInitScript(() => {
    localStorage.setItem('epharm.auth.user', JSON.stringify({ id: 'layout-hq', email: 'layout@example.invalid', name: 'Демо HQ', role: 'HQ_HEAD', company: 'DEMO' }))
    localStorage.setItem('epharm.auth.tokens', JSON.stringify({ accessToken: 'synthetic-token', refreshToken: 'synthetic-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshTokenExpiresAt: '2099-01-02T00:00:00Z' }))
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== new URL(baseURL).origin || url.pathname.startsWith('/src/') || url.pathname.startsWith('/@')) {
      blocked.push(request.url())
      return route.abort()
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (request.method() === 'GET') {
      if (url.pathname === '/api/admin/access/me') return route.fulfill({ json: { managed: false, version: null, grants: [], roleCeiling: 'HQ_HEAD' } })
      if (url.pathname === '/api/admin/promo/layout-demo') return route.fulfill({ json: promo })
      if (url.pathname === '/api/admin/promo/layout-demo/changes') return route.fulfill({ json: {
        promoId: promo.id, revision: 3, hasPendingChanges: true, conflict: false,
        canSave: true, canApply: true, readinessIssues: [], updatedAt: null, updatedBy: null,
        bundle: { campaign: promo, rules },
      } })
      if (url.pathname === '/api/admin/storefront/products/layout-offer') return route.fulfill({ json: { id: 'layout-offer', name, price: 12500, images: [], keyFacts: [], description: null } })
    }
    blocked.push(`${request.method()} ${url.pathname}`)
    return route.abort()
  })
  return blocked
}

for (const width of [1280, 1440, 1920]) {
  test(`campaign actions, offers and comparison controls fit without overlap at ${width}px`, async ({ page, baseURL }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 })
    const blocked = await campaignFixture(page, baseURL!)
    await page.goto('/promo/layout-demo')
    await expect(page.getByTestId('promo-rules-editor')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Проверить и применить', exact: true })).toBeVisible()
    const toggles = page.locator('[data-testid^="pr-card-toggle-"]')
    await expect(toggles).toHaveCount(2)
    for (const toggle of await toggles.all()) await toggle.click()
    await expect(page.locator('[data-testid^="pr-cmp-"]')).toHaveCount(2)
    await page.evaluate(() => document.fonts.ready)

    const problems = await page.evaluate(() => {
      const errors: string[] = []
      const main = document.querySelector('main')!
      if (main.scrollWidth > main.clientWidth + 1) errors.push(`main overflows by ${main.scrollWidth - main.clientWidth}px`)
      const pairs = [...document.querySelectorAll('[data-testid^="pr-chosen-"]')]
      const containers = [
        document.querySelector('h1')!.parentElement!.parentElement!,
        ...pairs,
        ...pairs.map((pair) => pair.firstElementChild!),
        ...document.querySelectorAll('[data-testid^="pr-cmp-"]'),
      ]
      for (const container of containers) {
        const outer = container.getBoundingClientRect()
        const controls = [...container.querySelectorAll<HTMLElement>('button,input,textarea')]
          .filter((control) => control.getClientRects().length && control.getBoundingClientRect().width > 0)
        const label = container.getAttribute('data-testid') || container.parentElement?.getAttribute('data-testid') || 'page header'
        for (const control of controls) {
          const rect = control.getBoundingClientRect()
          if (rect.left < outer.left - 1 || rect.right > outer.right + 1) errors.push(`${label}: control outside container: ${control.getAttribute('aria-label') || control.textContent || control.tagName}`)
        }
        for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
          const a = controls[i].getBoundingClientRect()
          const b = controls[j].getBoundingClientRect()
          if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) errors.push(`${label}: overlapping controls ${i}/${j}`)
        }
      }
      return errors
    })
    await testInfo.attach('layout-geometry', { body: JSON.stringify(problems), contentType: 'application/json' })
    await page.locator('[data-testid^="pr-chosen-"]').first().evaluate((element) => element.scrollIntoView({ block: 'start' }))
    await page.screenshot({ path: testInfo.outputPath(`recommendation-controls-${width}.png`), animations: 'disabled' })
    await page.locator('[data-testid^="pr-cmp-"]').first().evaluate((element) => element.scrollIntoView({ block: 'center' }))
    await page.screenshot({ path: testInfo.outputPath(`recommendation-comparison-${width}.png`), animations: 'disabled' })
    expect(problems).toEqual([])
    expect(blocked).toEqual([])
  })
}
