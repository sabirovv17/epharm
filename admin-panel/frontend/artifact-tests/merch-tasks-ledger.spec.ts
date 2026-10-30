import { expect, test, type Page } from '@playwright/test'
import type { MerchTask } from '../src/features/merch/tasks-api'
const cap = 'synthetic_task_capability_000000000000000000000000'
const pixel = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRuoAAAAASUVORK5CYII=',
  'base64',
)
const evidence = {
  id: 'photo-demo',
  mimeType: 'image/png',
  byteSize: pixel.length,
  sha256: null,
  originalFilename: 'demo.png',
  createdAt: '2026-10-29T00:00:00Z',
  verification: 'manual_review_required',
}
const freshTask = (): MerchTask => ({
  id: 'task-demo',
  branchId: 'branch-demo',
  title: 'Демо-выкладка',
  status: 'assigned',
  dueAt: null,
  sourceSystem: 'EPHARM',
  version: 1,
  rewardAmountKzt: 0,
  reward: null,
  itemCount: 1,
  detailsIncluded: true,
  priority: 'normal',
  taskType: 'photo_report',
  items: [
    {
      id: 'item-demo',
      title: 'Проверить полку',
      instructions: 'Сделайте общее фото полки.',
      status: 'assigned',
      taskType: 'photo_report',
      required: true,
      requiredPhotos: 1,
      checklist: [{ id: 'check-demo', title: 'Выкладка соответствует заданию', required: true }],
      version: 1,
      currentAttempt: null,
      legacyEvidence: [],
      availableFrom: null,
      dueAt: null,
      canStart: true,
      canUpload: false,
      canSubmit: false,
      canReview: false,
    },
  ],
})
async function seedAdmin(page: Page, caps: Record<string, boolean>) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.auth.user',
      JSON.stringify({
        id: 'admin-demo',
        role: 'HQ_HEAD',
        name: 'Демо HQ',
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
  return {
    managed: true,
    version: 1,
    grants: [],
    roleCeiling: 'HQ_HEAD',
    canViewAccounts: false,
    canManageAccounts: false,
    canViewMerchInventory: false,
    canViewMerchPlacements: false,
    canViewMerchTasks: false,
    ...caps,
  }
}
test('free QR flow keeps its capability out of URLs/storage and uploads a current-attempt photo before submitting', async ({
  page,
  baseURL,
}, testInfo) => {
  const blocked: string[] = []
  const writes: string[] = []
  let task = freshTask()
  await page.setViewportSize({ width: 390, height: 1000 })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    expect(path).toMatch(/^\/api\/public\/merch\/tasks/)
    expect(request.headers()['x-merch-task-token']).toBe(cap)
    expect(request.headers().authorization).toBeUndefined()
    expect(request.url()).not.toContain(cap)
    if (path.endsWith('/session'))
      return route.fulfill({
        json: {
          dispatchId: task.id,
          branchId: task.branchId,
          expiresAt: '2099-01-01T00:00:00Z',
          readOnly: false,
          rewardAmountKzt: 0,
          identityRequired: false,
        },
      })
    if (path === '/api/public/merch/tasks') return route.fulfill({ json: task })
    if (path.endsWith('/attempts')) {
      writes.push('start')
      task = {
        ...task,
        status: 'in_progress',
        items: [
          {
            ...task.items[0],
            status: 'in_progress',
            canStart: false,
            canUpload: true,
            currentAttempt: {
              id: 'attempt-demo',
              number: 1,
              status: 'draft',
              executorPersonId: null,
              pharmacistId: null,
              submittedAt: null,
              createdAt: '2026-10-29T00:00:00Z',
              evidence: [],
              review: null,
              executorIdentityStatus: 'capability_unverified',
            },
          },
        ],
      }
      return route.fulfill({ json: task.items[0].currentAttempt })
    }
    if (path.endsWith('/evidence') && request.method() === 'POST') {
      expect(url.searchParams.get('clientUploadId')).toMatch(/^[a-f0-9-]{36}$/)
      expect(request.headers()['content-type']).toContain('multipart/form-data; boundary=')
      writes.push('photo')
      task.items[0] = {
        ...task.items[0],
        canSubmit: true,
        currentAttempt: { ...task.items[0].currentAttempt!, evidence: [evidence] },
      }
      return route.fulfill({ json: evidence })
    }
    if (path.endsWith('/evidence/photo-demo'))
      return route.fulfill({ contentType: 'image/png', body: pixel })
    if (path.endsWith('/submit')) {
      expect(request.postDataJSON()).toEqual({
        expectedAttempt: 1,
        completedChecklistIds: ['check-demo'],
        comment: 'Демо-фото загружено',
      })
      writes.push('submit')
      task = {
        ...task,
        status: 'submitted',
        items: [
          {
            ...task.items[0],
            status: 'submitted',
            canUpload: false,
            canSubmit: false,
            currentAttempt: {
              ...task.items[0].currentAttempt!,
              status: 'submitted',
              comment: 'Демо-фото загружено',
              completedChecklistIds: ['check-demo'],
            },
          },
        ],
      }
      return route.fulfill({ json: task.items[0].currentAttempt })
    }
    blocked.push(path)
    return route.abort()
  })
  await page.goto(`/tasks/staff?task=1#task=${cap}`)
  await expect(page.getByRole('heading', { name: 'Демо-выкладка' })).toBeVisible()
  expect(new URL(page.url()).hash).toBe('')
  await page.getByRole('button', { name: 'Начать выполнение' }).click()
  await page
    .getByLabel('Фото для Проверить полку')
    .setInputFiles({ name: 'demo.png', mimeType: 'image/png', buffer: pixel })
  await page.getByRole('button', { name: 'Загрузить фото', exact: true }).click()
  await page.getByRole('checkbox', { name: /Выкладка соответствует заданию/ }).check()
  await page.getByLabel('Комментарий для Проверить полку').fill('Демо-фото загружено')
  await page.getByRole('button', { name: 'Отправить пункт на проверку' }).click()
  await expect(page.getByText('Текущая попытка №1: На проверке')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Принять пункт' })).toHaveCount(0)
  expect(
    await page.evaluate(() =>
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ),
  ).not.toContain(cap)
  await page.screenshot({
    path: testInfo.outputPath('free-task-photo-report.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  expect(writes).toEqual(['start', 'photo', 'submit'])
  expect(blocked).toEqual([])
})

test('paid QR opens only metadata before requiring the separate personal learner login', async ({
  page,
  baseURL,
}) => {
  const requests: string[] = []
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.origin !== new URL(baseURL!).origin) return route.abort()
    if (!url.pathname.startsWith('/api/')) return route.continue()
    requests.push(url.pathname)
    expect(url.pathname).toBe('/api/public/merch/tasks/session')
    return route.fulfill({
      json: {
        dispatchId: 'task-paid-demo',
        branchId: 'branch-demo',
        expiresAt: '2099-01-01T00:00:00Z',
        readOnly: false,
        rewardAmountKzt: 400,
        identityRequired: true,
      },
    })
  })
  await page.goto(`/tasks/staff?task=1#task=${cap}`)
  await expect(page.getByRole('link', { name: 'Войти и открыть задание' })).toHaveAttribute(
    'href',
    '/learn/merch/tasks/task-paid-demo',
  )
  expect(requests).toEqual(['/api/public/merch/tasks/session'])
})

test('paid native task confirms the current learner by explicit SMS before opening the task, without an admin session', async ({
  page,
  baseURL,
}, testInfo) => {
  const blocked: string[] = []
  const writes: string[] = []
  let bound = false
  const task = { ...freshTask(), rewardAmountKzt: 400 }
  await page.setViewportSize({ width: 390, height: 1000 })
  await page.addInitScript(() => {
    localStorage.setItem(
      'epharm.learner.tokens',
      JSON.stringify({
        accessToken: 'synthetic-learner',
        refreshToken: 'synthetic-learner-refresh',
      }),
    )
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/public/merch/tasks/session') {
      expect(request.headers()['x-merch-task-token']).toBe(cap)
      expect(request.headers().authorization).toBeUndefined()
      return route.fulfill({
        json: {
          dispatchId: task.id,
          branchId: task.branchId,
          expiresAt: '2099-01-01T00:00:00Z',
          readOnly: false,
          rewardAmountKzt: 400,
          identityRequired: true,
        },
      })
    }
    if (!path.startsWith('/api/mobile/')) {
      blocked.push(path)
      return route.abort()
    }
    expect(request.headers().authorization).toBe('Bearer synthetic-learner')
    if (path === '/api/mobile/auth/me')
      return route.fulfill({
        json: {
          id: 'learner-demo',
          name: 'Демо Фармацевт',
          status: 'active',
          tier: 'Silver',
          pharmacyName: 'Демо-аптека',
          city: 'Демо-город',
        },
      })
    if (path === '/api/mobile/training')
      return route.fulfill({
        json: {
          total: 0,
          inProgress: 0,
          completed: 0,
          overdue: 0,
          assignments: [],
          certificates: [],
          notifications: [],
        },
      })
    if (path === '/api/mobile/promotions') return route.fulfill({ json: [] })
    if (path === '/api/mobile/me/balance')
      return route.fulfill({ json: { balanceKzt: 0, currency: 'KZT' } })
    if (path === '/api/mobile/me/recommendations')
      return route.fulfill({ json: { items: [], page: 0, size: 20, total: 0, currency: 'KZT' } })
    const taskBase = `/api/mobile/merch/tasks/${task.id}`
    if (path === taskBase)
      return route.fulfill(
        bound
          ? { json: task }
          : {
              status: 403,
              json: { code: 'OTP_NOT_VERIFIED', message: 'Подтвердите получателя задания' },
            },
      )
    if (path.startsWith(`${taskBase}/executor`)) {
      expect(request.headers()['x-merch-task-token']).toBe(cap)
      expect(url.href).not.toContain(cap)
      if (path.endsWith('/sms/request')) {
        writes.push('request')
        expect(request.postData()).toBeNull()
        return route.fulfill({
          json: {
            otpChallengeId: 'challenge-demo',
            phoneMasked: '+7 *** 0000',
            expiresAt: '2099-01-01T00:00:00Z',
            retryAfterSeconds: 60,
          },
        })
      }
      if (path.endsWith('/sms/verify')) {
        writes.push('verify')
        expect(request.postDataJSON()).toEqual({ otpChallengeId: 'challenge-demo', code: '1234' })
        bound = true
        return route.fulfill({
          json: {
            bindingId: 'binding-demo',
            dispatchId: task.id,
            pharmacistId: 'learner-demo',
            pharmacyId: 'branch-demo',
            duplicate: false,
          },
        })
      }
      return route.fulfill({
        json: {
          dispatchId: task.id,
          taskTitle: task.title,
          pharmacyName: 'Демо-аптека',
          amountKzt: 400,
          dueAt: '2099-01-01T00:00:00Z',
          challengeExpiresAt: '2099-01-01T00:00:00Z',
          phoneMasked: '+7 *** 0000',
          bindingId: null,
          currency: 'KZT',
        },
      })
    }
    blocked.push(path)
    return route.abort()
  })
  await page.goto(`/tasks/staff?task=1#task=${cap}`)
  await page.getByRole('link', { name: 'Войти и открыть задание' }).click()
  await expect(
    page.getByRole('heading', { name: 'Подтвердите исполнителя', exact: true }),
  ).toBeVisible()
  expect(writes).toEqual([])
  await page.getByRole('button', { name: 'Получить код подтверждения' }).click()
  await page.getByLabel('SMS-код исполнителя').fill('1234')
  await page.getByRole('button', { name: 'Подтвердить исполнителя', exact: true }).click()
  await expect(page.getByRole('heading', { name: task.title, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Начать выполнение' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Принять пункт', exact: true })).toHaveCount(0)
  expect(writes).toEqual(['request', 'verify'])
  expect(await page.evaluate(() => localStorage.getItem('epharm.auth.tokens'))).toBeNull()
  expect(
    await page.evaluate(() =>
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ),
  ).not.toContain(cap)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
  await page.screenshot({
    path: testInfo.outputPath('learner-paid-task-after-confirmation.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(blocked).toEqual([])
})

test('HQ returns only the selected item with a reason and current CAS version; photo bytes stay authenticated', async ({
  page,
  baseURL,
}, testInfo) => {
  const caps = await seedAdmin(page, { canViewMerchTasks: true, canReviewMerchTasks: true })
  const blocked: string[] = []
  const writes: unknown[] = []
  let task = freshTask()
  task = {
    ...task,
    status: 'submitted',
    itemCount: 2,
    pendingReviewCount: 2,
    nextPendingReviewItemId: 'item-demo',
    rewardAmountKzt: 400,
    items: [
      {
        ...task.items[0],
        status: 'submitted',
        version: 7,
        canStart: false,
        canReview: true,
        currentAttempt: {
          id: 'attempt-demo',
          number: 2,
          status: 'submitted',
          executorPersonId: 'person-demo',
          pharmacistId: 'ph-demo',
          submittedAt: '2026-10-29T00:00:00Z',
          createdAt: '2026-10-29T00:00:00Z',
          evidence: [evidence],
          review: null,
          executorIdentityStatus: 'verified_person',
        },
      },
    ],
  }
  task.items.push({
    ...task.items[0],
    id: 'next-item-demo',
    title: 'Следующая полка',
    currentAttempt: { ...task.items[0].currentAttempt!, id: 'next-attempt-demo' },
  })
  const queues: string[] = []
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    expect(request.headers().authorization).toBe('Bearer synthetic-admin')
    if (path === '/api/admin/access/me') return route.fulfill({ json: caps })
    if (path === '/api/admin/merch/tasks/branches')
      return route.fulfill({
        json: [
          {
            id: task.branchId,
            name: 'Демо-аптека',
            city: 'Демо',
            address: 'Демо, 1',
            active: true,
            createEligible: false,
            canConfigureReward: false,
          },
        ],
      })
    if (path === '/api/admin/merch/tasks') {
      queues.push(url.searchParams.get('queue') ?? 'all')
      return route.fulfill({
        json: {
          items: [{ ...task, detailsIncluded: false, items: [] }],
          offset: 0,
          limit: 20,
          hasMore: false,
        },
      })
    }
    if (path === '/api/admin/merch/tasks/task-demo') return route.fulfill({ json: task })
    if (path.endsWith('/evidence/photo-demo'))
      return route.fulfill({ contentType: 'image/png', body: pixel })
    if (path.endsWith('/reviews')) {
      expect(path).toBe('/api/admin/merch/tasks/items/item-demo/reviews')
      const body = request.postDataJSON()
      writes.push(body)
      expect(body).toEqual({
        attemptId: 'attempt-demo',
        expectedItemVersion: 7,
        decision: 'revision',
        reason: 'Нужен общий план полки',
      })
      task.items[0] = {
        ...task.items[0],
        version: 8,
        status: 'revision_required',
        canReview: false,
        currentAttempt: {
          ...task.items[0].currentAttempt!,
          status: 'revision_required',
          review: {
            id: 'review-demo',
            attemptId: 'attempt-demo',
            reviewerAccountId: 'admin-demo',
            reviewerPersonId: 'reviewer-person-demo',
            decision: 'revision',
            reason: body.reason,
            createdAt: '2026-10-29T00:00:00Z',
          },
        },
      }
      task.status = 'revision_required'
      task.pendingReviewCount = 1
      task.nextPendingReviewItemId = 'next-item-demo'
      return route.fulfill({ json: task })
    }
    blocked.push(path)
    return route.abort()
  })
  await page.goto('/merch/tasks')
  await expect(page.getByText('Пунктов: 2', { exact: true })).toBeVisible()
  await expect(page.getByText('Демо · Демо, 1', { exact: true })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('hq-task-queue.png'),
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('combobox', { name: 'Рабочая очередь заданий' }).selectOption('review')
  await expect(page.getByRole('heading', { name: 'Отчёты на проверке' })).toBeVisible()
  expect(queues).toContain('review')
  await page.getByRole('button', { name: 'Открыть отчёт' }).click()
  const firstItem = page.getByRole('region', { name: 'Пункт: Проверить полку' })
  await expect(firstItem).toBeFocused()
  await expect(firstItem.getByRole('img', { name: 'Фотоотчёт по пункту задания' })).toBeVisible()
  await firstItem.getByRole('button', { name: 'Вернуть пункт на доработку' }).click()
  await expect(page.getByRole('button', { name: 'Подтвердить решение' })).toBeDisabled()
  await page.getByLabel('Причина проверки пункта').fill('Нужен общий план полки')
  expect(writes).toEqual([])
  await page.getByRole('button', { name: 'Подтвердить решение' }).click()
  await expect(page.getByText('Пункт возвращён: Нужен общий план полки')).toBeVisible()
  await expect(page.getByText('Пунктов: 1', { exact: true })).toBeVisible()
  const nextItem = page.getByRole('region', { name: 'Пункт: Следующая полка' })
  await expect(nextItem).toBeFocused()
  await expect(nextItem.getByRole('button', { name: 'Вернуть пункт на доработку' })).toBeVisible()
  await page.screenshot({
    path: testInfo.outputPath('hq-item-review.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(writes).toHaveLength(1)
  expect(blocked).toEqual([])
})

test('ledger uses wallet-only rows, keeps unknown lineage null and exports the same applied period/source', async ({
  page,
  baseURL,
}, testInfo) => {
  const caps = await seedAdmin(page, { canViewLedger: true, canExportLedger: true })
  const exports: URL[] = []
  const blocked: string[] = []
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (url.origin !== new URL(baseURL!).origin) {
      blocked.push(path)
      return route.abort()
    }
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/admin/access/me') return route.fulfill({ json: caps })
    if (path === '/api/admin/reports/ledger')
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          timezone: 'Asia/Almaty',
          generatedAt: '2026-10-29T12:00:00Z',
          asOfExclusive: '2026-10-29T12:00:00Z',
          sourceFilter: url.searchParams.get('source'),
          pharmacistFilter: null,
          scopePharmacyIds: ['demo-pharmacy'],
          entries: [
            {
              id: 'entry-demo',
              pharmacistId: 'ph-demo',
              pharmacistName: 'Демо-сотрудник',
              source: 'training_bonus',
              sourceId: 'assignment-demo',
              amountKzt: 1000,
              earnedDeltaKzt: 1000,
              currency: 'KZT',
              contextKey: 'must-not-display',
              balanceAfterKzt: null,
              createdAt: '2026-10-29T11:00:00Z',
              historicalPharmacyId: null,
              historicalPharmacyName: null,
              historicalPharmacyStatus: 'not_recorded_in_source',
              basisStatus: null,
              state: 'posted',
            },
          ],
          totals: [{ source: 'training_bonus', entries: 1, amountKzt: 1000, earnedDeltaKzt: 1000 }],
          unresolvedHistoricalRows: null,
          historicalScopeNotice: 'Только доказанная историческая область',
          coverage: 'Только проводки кошелька',
        },
      })
    if (path === '/api/admin/reports/ledger.xlsx') {
      exports.push(url)
      return route.fulfill({
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        body: Buffer.from('PK-SYNTHETIC-XLSX-TRANSPORT'),
      })
    }
    blocked.push(path)
    return route.abort()
  })
  await page.goto('/finance/ledger')
  await expect(page.getByRole('cell', { name: 'Не записана в источнике' })).toBeVisible()
  await expect(page.getByRole('cell', { name: '—' })).toBeVisible()
  await expect(page.getByText(/не раскрывается в вашей области/)).toBeVisible()
  await page.getByLabel('Начало периода начислений').fill('2026-09-01')
  await page.getByLabel('Окончание периода начислений').fill('2026-09-30')
  await page.getByRole('combobox', { name: 'Источник начисления' }).selectOption('training_bonus')
  await expect(page.getByRole('button', { name: 'Excel: начисления' })).toBeDisabled()
  await page.getByRole('button', { name: 'Показать', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Excel: начисления' })).toBeEnabled()
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Excel: начисления' }).click()
  const file = await downloading
  expect(file.suggestedFilename()).toBe('ledger-2026-09-01-2026-09-30.xlsx')
  expect(exports[0].searchParams.get('source')).toBe('training_bonus')
  expect(exports[0].searchParams.get('from')).toBe('2026-09-01')
  expect(exports[0].searchParams.get('to')).toBe('2026-09-30')
  await expect(page.getByText('must-not-display')).toHaveCount(0)
  await page.screenshot({
    path: testInfo.outputPath('ledger-scoped-report.png'),
    fullPage: true,
    animations: 'disabled',
  })
  expect(blocked).toEqual([])
})
