import type { Page } from '@playwright/test'
import { test, expect, scanProduct, offerProduct } from './fixtures'

test.beforeEach(async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel(/Email/i).fill('artifact@example.invalid')
  await page.getByLabel(/Пароль/i).fill('artifact-test-only')
  await page.getByRole('button', { name: /^Войти$/ }).click()
  await expect(page).toHaveURL(/\/rules$/)
  await page.goto('/promo')
  await page.getByRole('button', { name: /Новая кампания/ }).first().click()
})

async function chooseOffer(page: Page, type: 'Замена' | 'Допродажа') {
  await page.getByRole('radio', { name: type, exact: true }).click()
  await page.getByPlaceholder(/Поиск товара/).fill('Предлагаемый')
  const offer = page.getByTestId(`promo-product-option-${offerProduct.id}`)
  await expect(offer).toBeEnabled()
  await offer.click()
}

test('built artifact opens the trigger-first modal with all four trigger kinds', async ({ page }) => {
  const dialog = page.getByRole('dialog', { name: /Новая кампания/ })
  await expect(dialog.getByRole('radiogroup', { name: 'Что запускает рекомендацию' })).toBeVisible()
  for (const label of ['Конкретный товар', 'Группа ACC', 'Подгруппа ACC', 'МНН ACC']) {
    await expect(dialog.getByRole('radio', { name: label, exact: true })).toBeVisible()
  }
  await expect(dialog.getByPlaceholder(/Поиск товара/)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
  const scripts = await page.locator('script[src]').evaluateAll((nodes) => nodes.map((node) => (node as HTMLScriptElement).src))
  expect(scripts.length).toBeGreaterThan(0)
  expect(scripts.every((src) => /\/assets\/[^/]+-[\w-]+\.js$/.test(new URL(src).pathname))).toBe(true)
})

for (const [label, kind] of [
  ['Группа ACC', 'acc_group'],
  ['Подгруппа ACC', 'acc_subgroup'],
  ['МНН ACC', 'acc_mnn'],
] as const) {
  test(`built artifact ${kind}: searches the scope and submits an inactive draft with a separate offer`, async ({ page, artifactApi }) => {
    await page.getByRole('radio', { name: label, exact: true }).click()
    await expect(page.getByTestId('create-trigger-snapshot')).toContainText('Каталог АСС.xlsx')
    await page.getByRole('textbox', { name: /Найти группу/ }).fill('сорб')
    await expect.poll(() => artifactApi.taxonomyRequests.at(-1)?.searchParams.get('q')).toBe('сорб')
    expect(artifactApi.taxonomyRequests.at(-1)?.searchParams.get('kind')).toBe(kind)
    const option = page.getByTestId(`create-trigger-option-${kind}:sorbents`)
    if (kind === 'acc_subgroup') await expect(option).toContainText('Желудочно-кишечные препараты')
    await option.click()
    await chooseOffer(page, 'Допродажа')
    await page.getByRole('button', { name: /Создать черновик/ }).click()
    await expect(page).toHaveURL(/\/promo\/artifact-draft$/)
    expect(artifactApi.drafts).toHaveLength(1)
    expect(artifactApi.drafts[0]).toMatchObject({
      status: 'draft', medusaProductId: offerProduct.id,
      initialRecommendation: {
        type: 'crosssell',
        trigger: { triggerKind: kind, triggerValue: `${kind}:sorbents`, medusaProductId: '', active: false },
      },
    })
  })
}

test('built artifact exact-product trigger requires a scan identity while offered goods remain independent', async ({ page, artifactApi }) => {
  await page.getByRole('radio', { name: 'Конкретный товар', exact: true }).click()
  await page.getByPlaceholder(/Поиск товара/).fill('Предлагаемый')
  const unavailable = page.getByTestId(`promo-product-option-${offerProduct.id}`)
  await expect(unavailable).toBeDisabled()
  await expect(unavailable).toContainText('товар нельзя выбрать как триггер на кассе')
  await page.getByPlaceholder(/Поиск товара/).fill('для сканирования')
  await page.getByTestId(`promo-product-option-${scanProduct.id}`).click()
  await chooseOffer(page, 'Замена')
  await page.getByRole('button', { name: /Создать черновик/ }).click()
  await expect(page).toHaveURL(/\/promo\/artifact-draft$/)
  expect(artifactApi.drafts).toHaveLength(1)
  expect(artifactApi.drafts[0]).toMatchObject({
    status: 'draft', medusaProductId: offerProduct.id,
    initialRecommendation: {
      type: 'substitution',
      trigger: { triggerKind: 'product', medusaProductId: scanProduct.id, barcode: scanProduct.barcode, active: false },
    },
  })
})

test('built artifact resets the new-campaign modal after closing it', async ({ page }) => {
  await page.getByRole('radio', { name: 'Конкретный товар', exact: true }).click()
  await page.getByPlaceholder(/Поиск товара/).fill('для сканирования')
  await page.getByTestId(`promo-product-option-${scanProduct.id}`).click()
  await chooseOffer(page, 'Замена')
  await expect(page.getByRole('button', { name: /Создать черновик/ })).toBeEnabled()
  await page.getByRole('dialog').getByRole('button', { name: 'Отмена', exact: true }).click()
  await page.getByRole('button', { name: /Новая кампания/ }).first().click()
  const dialog = page.getByRole('dialog', { name: /Новая кампания/ })
  await expect(dialog.getByRole('radio', { checked: true })).toHaveCount(0)
  await expect(dialog.getByPlaceholder(/Поиск товара/)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: /Создать черновик/ })).toBeDisabled()
})
