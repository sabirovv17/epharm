import type { Page } from '@playwright/test'
import { test, expect } from './fixtures'

const triggerProduct = {
  id: 'prod_contract_trigger', name: 'Сканируемый товар', brand: 'Контракт', mnn: null,
  rxOtc: 'OTC', price: 990, currency: 'KZT', imageUrl: null,
  barcode: '4601164003157', ipartId: null, category: null,
}
const offerProduct = {
  ...triggerProduct, id: 'prod_contract_offer', name: 'Предлагаемый товар', barcode: null,
}

test.beforeEach(async ({ loggedInPage }) => {
  await loggedInPage.route('**/api/admin/storefront/products**', async (route) => {
    const url = new URL(route.request().url())
    const query = url.searchParams.get('q')?.toLocaleLowerCase() ?? ''
    const products = [triggerProduct, offerProduct]
    const detail = products.find((product) => url.pathname.endsWith(`/${product.id}`))
    const items = products.filter((product) => product.name.toLocaleLowerCase().includes(query))
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(detail
        ? { ...detail, images: [], description: null, keyFacts: [] }
        : { items, total: items.length, limit: 50, offset: 0 }),
    })
  })
  await loggedInPage.route('**/api/admin/promo', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    // This contract check must never publish a recommendation or create real data.
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 'pr_001' }) })
  })
  await loggedInPage.goto('/promo')
  await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
})

async function chooseOffer(page: Page, type: 'Замена' | 'Допродажа') {
  await page.getByRole('radio', { name: type, exact: true }).click()
  await page.getByPlaceholder(/Поиск товара/).fill('Предлагаемый')
  const offer = page.getByTestId(`promo-product-option-${offerProduct.id}`)
  // A product without a barcode may be offered as advice, but cannot trigger a scan.
  await expect(offer).toBeEnabled()
  await offer.click()
}

async function submitDraft(page: Page) {
  const [request] = await Promise.all([
    page.waitForRequest((request) => new URL(request.url()).pathname === '/api/admin/promo' && request.method() === 'POST'),
    page.getByRole('button', { name: /Создать черновик/ }).click(),
  ])
  return request.postDataJSON()
}

for (const [label, kind] of [
  ['Группа ACC', 'acc_group'],
  ['Подгруппа ACC', 'acc_subgroup'],
  ['МНН ACC', 'acc_mnn'],
] as const) {
  test(`${label}: поиск отправляет kind/q, черновик сохраняет ключ и текущий товар каталога`, async ({ loggedInPage }) => {
    const requests: URL[] = []
    await loggedInPage.route('**/api/admin/catalog/trigger-options**', async (route) => {
      const url = new URL(route.request().url())
      requests.push(url)
      const requestedKind = url.searchParams.get('kind')
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          snapshot: { sha256: 'contract', sourceName: 'Каталог АСС.xlsx', itemCount: 50, barcodeCount: 40, importedAt: '2026-10-01T00:00:00Z' },
          options: [{ key: `${requestedKind}:sorbents`, label: 'Сорбенты', count: 12,
            parentLabel: requestedKind === 'acc_subgroup' ? 'Желудочно-кишечные препараты' : null }],
        }),
      })
    })
    await loggedInPage.getByRole('radio', { name: label, exact: true }).click()
    await expect(loggedInPage.getByTestId('create-trigger-snapshot')).toContainText('Каталог АСС.xlsx')
    await loggedInPage.getByRole('textbox', { name: /Найти группу/ }).fill('сорб')
    await expect.poll(() => requests.at(-1)?.searchParams.get('q')).toBe('сорб')
    expect(requests.at(-1)?.searchParams.get('kind')).toBe(kind)
    const scope = loggedInPage.getByTestId(`create-trigger-option-${kind}:sorbents`)
    if (kind === 'acc_subgroup') await expect(scope).toContainText('Желудочно-кишечные препараты')
    await scope.click()
    await chooseOffer(loggedInPage, 'Допродажа')
    const payload = await submitDraft(loggedInPage)
    expect(payload).toMatchObject({
      status: 'draft', medusaProductId: offerProduct.id,
      initialRecommendation: {
        type: 'crosssell',
        trigger: { triggerKind: kind, triggerValue: `${kind}:sorbents`, medusaProductId: '', active: false },
      },
    })
  })
}

test('конкретный товар: запрещает триггер без идентификаторов, сохраняет доступный триггер и отдельное предложение', async ({ loggedInPage }) => {
  await loggedInPage.getByRole('radio', { name: 'Конкретный товар', exact: true }).click()
  await loggedInPage.getByPlaceholder(/Поиск товара/).fill('Предлагаемый')
  const unavailable = loggedInPage.getByTestId(`promo-product-option-${offerProduct.id}`)
  await expect(unavailable).toBeDisabled()
  await expect(unavailable).toContainText('товар нельзя выбрать как триггер на кассе')
  await expect(loggedInPage.getByRole('radio', { name: 'Замена', exact: true })).toHaveCount(0)

  await loggedInPage.getByPlaceholder(/Поиск товара/).fill('Сканируемый')
  await loggedInPage.getByTestId(`promo-product-option-${triggerProduct.id}`).click()
  await chooseOffer(loggedInPage, 'Замена')
  const payload = await submitDraft(loggedInPage)
  expect(payload).toMatchObject({
    medusaProductId: offerProduct.id,
    initialRecommendation: {
      type: 'substitution',
      trigger: { triggerKind: 'product', medusaProductId: triggerProduct.id, barcode: triggerProduct.barcode, active: false },
    },
  })
})

test('повторное открытие окна начинает с выбора триггера без сохранённого товара', async ({ loggedInPage }) => {
  await loggedInPage.getByRole('radio', { name: 'Конкретный товар', exact: true }).click()
  await loggedInPage.getByPlaceholder(/Поиск товара/).fill('Сканируемый')
  await loggedInPage.getByTestId(`promo-product-option-${triggerProduct.id}`).click()
  await chooseOffer(loggedInPage, 'Замена')
  await expect(loggedInPage.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
  await loggedInPage.getByRole('dialog').getByRole('button', { name: 'Отмена', exact: true }).click()
  await loggedInPage.getByRole('button', { name: /Новая кампания/ }).first().click()
  await expect(loggedInPage.getByRole('radiogroup', { name: 'Что запускает рекомендацию' })).toBeVisible()
  await expect(loggedInPage.getByRole('radio', { checked: true })).toHaveCount(0)
  await expect(loggedInPage.getByPlaceholder(/Поиск товара/)).toHaveCount(0)
  await expect(loggedInPage.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
})

for (const [label, identity] of [
  ['plural-only source', { barcode: null, barcodes: ['0012345678905', '4601164003157'] }],
  ['legacy packed source', { barcode: '_0012345678905_4601164003157_', barcodes: ['4601164003157'] }],
] as const) {
  test(`${label}: exact trigger displays and submits every explicit code`, async ({ loggedInPage }, testInfo) => {
    await loggedInPage.route('**/api/admin/storefront/products**', async (route) => {
      const url = new URL(route.request().url())
      const products = [{ ...triggerProduct, ...identity, name: '[ТЕСТ] Цитрамон-П 10 таблеток' }, offerProduct]
      const detail = products.find((product) => url.pathname.endsWith(`/${product.id}`))
      const query = url.searchParams.get('q')?.toLocaleLowerCase() ?? ''
      const items = products.filter((product) => product.name.toLocaleLowerCase().includes(query))
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(detail
        ? { ...detail, images: [], description: null, keyFacts: [] }
        : { items, total: items.length, limit: 50, offset: 0 }) })
    })
    await loggedInPage.getByRole('radio', { name: 'Конкретный товар', exact: true }).click()
    await loggedInPage.getByPlaceholder(/Поиск товара/).fill('Цитрамон')
    const trigger = loggedInPage.getByTestId(`promo-product-option-${triggerProduct.id}`)
    await expect(trigger).toBeEnabled()
    await expect(trigger).toContainText('Штрихкоды: 2')
    await expect(trigger).toContainText('0012345678905')
    await expect(trigger).toContainText('4601164003157')
    await loggedInPage.screenshot({ path: testInfo.outputPath('TEST-citramon-multiple-barcodes.png'), fullPage: true })
    await trigger.click()
    await chooseOffer(loggedInPage, 'Замена')
    const payload = await submitDraft(loggedInPage)
    expect(payload.initialRecommendation.trigger).toMatchObject({
      triggerKind: 'product', medusaProductId: triggerProduct.id, barcode: '0012345678905',
      barcodes: ['0012345678905', '4601164003157'], active: false,
    })
  })
}
