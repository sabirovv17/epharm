// E2E — сверка чеков: очередь → клик по чеку → drawer → одобрить / отклонить.
// Покрывает реальный путь модератора HQ (раньше был только unit-тест ReconcilePage).

import { test, expect } from './fixtures'

test.describe('Reconcile — модерация чеков', () => {
  test.beforeEach(async ({ loggedInPage }) => {
    await loggedInPage.goto('/reconcile')
    await expect(
      loggedInPage.getByRole('heading', { level: 1, name: /Сверка чеков/i }),
    ).toBeVisible()
  })

  test('клик по чеку открывает drawer с деталями', async ({ loggedInPage }) => {
    const firstRow = loggedInPage.locator('[data-testid^="receipt-rcp"]').first()
    await expect(firstRow).toBeVisible()
    await firstRow.click()

    const drawer = loggedInPage.getByTestId('receipt-detail')
    await expect(drawer).toBeVisible()
    // В детали либо фото, либо заглушка — но drawer обязан показать содержимое.
    const photo = drawer.getByTestId('receipt-photo')
    const noPhoto = drawer.getByTestId('receipt-nophoto')
    expect((await photo.count()) + (await noPhoto.count())).toBeGreaterThan(0)
  })

  test('одобрение чека из drawer → тост + чек уходит из очереди', async ({ loggedInPage }) => {
    // confirm() при одобрении — принимаем.
    loggedInPage.on('dialog', (d) => d.accept())

    const firstRow = loggedInPage.locator('[data-testid^="receipt-rcp"]').first()
    const receiptId = (await firstRow.getAttribute('data-testid'))!.replace('receipt-', '')
    await firstRow.click()
    await loggedInPage.getByTestId('drawer-approve').click()

    await expect(loggedInPage.getByText(/Чек одобрен/i)).toBeVisible()
    // Этот чек больше не в текущей вкладке очереди.
    await expect(loggedInPage.getByTestId(`receipt-${receiptId}`)).toHaveCount(0)
  })

  test('отклонение чека из drawer → тост', async ({ loggedInPage }) => {
    // confirm/prompt — принимаем с причиной.
    loggedInPage.on('dialog', (d) => d.accept('поддельный'))

    const firstRow = loggedInPage.locator('[data-testid^="receipt-rcp"]').first()
    await firstRow.click()
    await loggedInPage.getByTestId('drawer-reject').click()

    await expect(loggedInPage.getByText(/Чек отклонён/i)).toBeVisible()
  })

  test('переключение вкладок очереди работает', async ({ loggedInPage }) => {
    // Вкладка «Одобрены» содержит авто-одобренные демо-чеки.
    await loggedInPage.getByRole('button', { name: /Одобрены/i }).click()
    await expect(loggedInPage.getByTestId('reconcile-table')).toBeVisible()
  })
})
