// E2E — сверка чеков: очередь → клик по чеку → drawer → одобрить / отклонить.
// Покрывает реальный путь модератора HQ (раньше был только unit-тест ReconcilePage).

import {
  test as ordinaryTest,
  expect,
  ACCOUNTS,
  BACKEND_URL,
  getBearer,
  loginViaUI,
} from './fixtures'

// Financial mutations require the real seeded HQ account, not the default Brand Manager.
// Scope this override to reconciliation: all other specs keep their ordinary-role fixture.
const test = ordinaryTest.extend({
  loggedInPage: async ({ freshPage }, use) => {
    await loginViaUI(freshPage, ACCOUNTS.bauyrzhan)
    await use(freshPage)
  },
})

test.describe('Reconcile — модерация чеков', () => {
  test.beforeEach(async ({ loggedInPage }) => {
    await loggedInPage.goto('/reconcile')
    await expect(
      loggedInPage.getByRole('heading', { level: 1, name: /Сверка чеков/i }),
    ).toBeVisible()
    await expect(loggedInPage.getByTestId('import-excel')).toBeVisible()
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

ordinaryTest('Brand Manager can read receipts but cannot moderate or import financial evidence', async ({ loggedInPage, request }) => {
  await loggedInPage.goto('/reconcile')
  await expect(loggedInPage.getByRole('heading', { level: 1, name: /Сверка чеков/i })).toBeVisible()
  await expect(loggedInPage.getByTestId('import-excel')).toHaveCount(0)

  const firstRow = loggedInPage.locator('[data-testid^="receipt-rcp"]').first()
  await expect(firstRow).toBeVisible()
  const receiptId = (await firstRow.getAttribute('data-testid'))!.replace('receipt-', '')
  await firstRow.click()
  await expect(loggedInPage.getByTestId('receipt-detail')).toBeVisible()
  await expect(loggedInPage.getByTestId('drawer-approve')).toHaveCount(0)
  await expect(loggedInPage.getByTestId('drawer-reject')).toHaveCount(0)

  // Hidden controls are not authorization: direct requests must be forbidden too.
  const headers = { Authorization: await getBearer(request, ACCOUNTS.damir) }
  const approve = await request.post(`${BACKEND_URL}/api/admin/reconcile/${receiptId}/approve`, { headers })
  const reject = await request.post(`${BACKEND_URL}/api/admin/reconcile/${receiptId}/reject`, {
    headers, data: { reason: 'Blocked ordinary-role attempt' },
  })
  const imported = await request.post(`${BACKEND_URL}/api/admin/reconcile/import-excel`, {
    headers,
    multipart: {
      file: { name: 'blocked.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('must-not-be-parsed') },
    },
  })
  expect(approve.status()).toBe(403)
  expect(reject.status()).toBe(403)
  expect(imported.status()).toBe(403)

  await loggedInPage.reload()
  await expect(loggedInPage.getByTestId(`receipt-${receiptId}`)).toBeVisible()
})
