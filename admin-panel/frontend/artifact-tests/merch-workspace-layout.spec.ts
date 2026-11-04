import { expect, test as base, type Locator, type Page } from '@playwright/test'
import type { MerchTask } from '../src/features/merch/tasks-api'

const taskTitle = 'Тестовое задание: проверить выкладку и подробный фотоотчёт аптеки'
const pixel = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aRuoAAAAASUVORK5CYII=',
  'base64',
)
const branches = ['one', 'two'].map((key) => ({
  id: `layout-branch-${key}`,
  name: `Тестовая аптека ${key}`,
  city: 'Тестовый город',
  address: 'Тестовая улица с длинным названием, 123, помещение 45',
  active: true,
  createEligible: true,
  canConfigureReward: false,
}))
const evidence = {
  id: 'layout-photo',
  mimeType: 'image/png',
  byteSize: pixel.length,
  sha256: null,
  originalFilename: 'synthetic.png',
  createdAt: '2026-11-01T00:00:00Z',
  verification: 'manual_review_required',
}
const task: MerchTask = {
  id: 'layout-task',
  branchId: branches[0].id,
  title: taskTitle,
  status: 'submitted',
  dueAt: '2099-01-01T00:00:00Z',
  sourceSystem: 'EPHARM',
  version: 3,
  rewardAmountKzt: null,
  reward: null,
  itemCount: 2,
  detailsIncluded: true,
  priority: 'high',
  taskType: 'photo_report',
  pendingReviewCount: 2,
  nextPendingReviewItemId: 'layout-reviewable',
  items: ['reviewable', 'readonly'].map((key) => ({
    id: `layout-${key}`,
    title: key === 'reviewable' ? 'Полка с доступной проверкой' : 'Полка без права проверки',
    instructions: 'Проверьте все обязательные пункты и сохранённую фотографию. '.repeat(8),
    status: 'submitted',
    taskType: 'photo_report',
    required: true,
    requiredPhotos: 1,
    checklist: [
      { id: 'layout-check', title: 'Положение товара соответствует заданию', required: true },
    ],
    version: 7,
    availableFrom: null,
    dueAt: null,
    legacyEvidence: [],
    canStart: false,
    canUpload: false,
    canSubmit: false,
    canReview: key === 'reviewable',
    currentAttempt: {
      id: `layout-attempt-${key}`,
      number: 2,
      status: 'submitted',
      executorPersonId: 'synthetic-executor',
      pharmacistId: 'synthetic-pharmacist',
      submittedAt: '2026-11-01T00:00:00Z',
      createdAt: '2026-11-01T00:00:00Z',
      evidence: [evidence],
      review: null,
      executorIdentityStatus: 'verified_person',
    },
  })),
}
interface ApiFixture {
  reads: URL[]
  blocked: string[]
  unexpectedWrites: string[]
}

const test = base.extend<{ apiFixture: ApiFixture }>({
  apiFixture: [
    async ({ page, baseURL }, use) => {
      const state: ApiFixture = { reads: [], blocked: [], unexpectedWrites: [] }
      await page.addInitScript(() => {
        localStorage.setItem(
          'epharm.auth.user',
          JSON.stringify({
            id: 'synthetic-layout-hq',
            email: 'layout@example.invalid',
            name: 'Тестовый HQ',
            role: 'HQ_HEAD',
            company: 'SYNTHETIC',
          }),
        )
        localStorage.setItem(
          'epharm.auth.tokens',
          JSON.stringify({
            accessToken: 'synthetic-layout-token',
            refreshToken: 'synthetic-layout-refresh',
            accessTokenExpiresAt: '2099-01-01T00:00:00Z',
            refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
          }),
        )
      })
      const origin = new URL(baseURL!).origin
      await page.route('**/*', async (route) => {
        const request = route.request()
        const url = new URL(request.url())
        if (
          url.origin !== origin ||
          url.pathname.startsWith('/src/') ||
          url.pathname.startsWith('/@')
        ) {
          state.blocked.push(request.url())
          return route.abort('blockedbyclient')
        }
        if (!url.pathname.startsWith('/api/')) return route.continue()
        // These are UI/layout tests: no mutation, expected or unexpected, can reach a backend.
        if (request.method() !== 'GET') {
          state.unexpectedWrites.push(`${request.method()} ${url.pathname}`)
          return route.fulfill({
            status: 409,
            json: { message: 'Synthetic fixture refuses writes' },
          })
        }
        state.reads.push(url)
        expect(request.headers().authorization).toBe('Bearer synthetic-layout-token')
        const path = url.pathname
        if (path === '/api/admin/access/me')
          return route.fulfill({
            json: {
              managed: true,
              version: 1,
              grants: [],
              roleCeiling: 'HQ_HEAD',
              canViewAccounts: false,
              canManageAccounts: false,
              canViewMerchInventory: false,
              canViewMerchPlacements: false,
              canViewMerchTasks: true,
              canCreateMerchTasks: true,
              canExecuteMerchTasks: true,
              canReviewMerchTasks: true,
              canExportMerchTasks: true,
              canCommentMerchTasks: false,
              canCancelMerchTasks: false,
            },
          })
        if (
          path === '/api/admin/merch/tasks/branches' ||
          path === '/api/admin/merch/tasks/visits/branches'
        )
          return route.fulfill({ json: branches })
        if (path === '/api/admin/merch/tasks/templates')
          return route.fulfill({
            json: [
              {
                id: 'photo_report',
                title: 'Тестовый фотоотчёт',
                instructions: 'Сфотографируйте полки',
                requiredPhotos: 1,
                checklist: ['Проверьте выкладку'],
                shelfMode: 'occupied',
              },
            ],
          })
        if (path === '/api/admin/merch/tasks')
          return route.fulfill({
            json: {
              items: [{ ...task, detailsIncluded: false, items: [] }],
              limit: 20,
              offset: Number(url.searchParams.get('offset') ?? 0),
              hasMore: Number(url.searchParams.get('offset') ?? 0) === 0,
            },
          })
        if (path === '/api/admin/merch/tasks/layout-task') return route.fulfill({ json: task })
        if (path === '/api/admin/merch/tasks/evidence/layout-photo')
          return route.fulfill({ contentType: 'image/png', body: pixel })
        if (
          /^\/api\/admin\/merch\/tasks\/items\/layout-(reviewable|readonly)\/comments$/.test(path)
        )
          return route.fulfill({ json: { items: [], hasMore: false } })
        if (path === '/api/admin/merch/exports/tasks.xlsx')
          return route.fulfill({
            contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            body: Buffer.from('synthetic-download-fixture-only'),
          })
        state.blocked.push(`${request.method()} ${path}`)
        return route.abort('blockedbyclient')
      })
      await use(state)
      expect(
        state.unexpectedWrites,
        'No accidental submission/review/SMS/account mutation',
      ).toEqual([])
      expect(state.blocked, 'No unknown API, external resource, or dev-source request').toEqual([])
    },
    { auto: true },
  ],
})

async function openWorkspace(page: Page) {
  await page.goto('/merch/tasks')
  await expect(page.getByRole('button', { name: taskTitle, exact: true })).toBeVisible()
}
async function assertPageFits(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    page.viewportSize()!.width + 1,
  )
}
async function assertDrawer(page: Page, drawer: Locator, persistentAction: Locator) {
  await expect(drawer).toBeVisible()
  const viewport = page.viewportSize()!
  await expect
    .poll(
      async () => {
        const box = await drawer.boundingBox()
        return box
          ? Math.max(
              Math.abs(box.x + box.width - viewport.width),
              Math.abs(box.y),
              Math.abs(box.height - viewport.height),
            )
          : Infinity
      },
      { message: 'The drawer settles at the viewport edge after its opening animation' },
    )
    .toBeLessThanOrEqual(1)
  const rect = (await drawer.boundingBox())!
  expect(
    Math.abs(rect.x + rect.width - viewport.width),
    'Drawer ends at viewport right edge',
  ).toBeLessThanOrEqual(1)
  expect(Math.abs(rect.y), 'Drawer begins at viewport top').toBeLessThanOrEqual(1)
  expect(
    Math.abs(rect.height - viewport.height),
    'Drawer fills viewport height',
  ).toBeLessThanOrEqual(1)
  expect(rect.x).toBeGreaterThanOrEqual(-1)
  expect(
    await drawer.evaluate((element) => element.scrollWidth - element.clientWidth),
  ).toBeLessThanOrEqual(1)
  const assertActionFits = async () => {
    await expect(persistentAction).toBeVisible()
    const actionRect = (await persistentAction.boundingBox())!
    expect(actionRect.x).toBeGreaterThanOrEqual(0)
    expect(actionRect.x + actionRect.width).toBeLessThanOrEqual(viewport.width)
    expect(actionRect.y).toBeGreaterThanOrEqual(0)
    expect(actionRect.y + actionRect.height).toBeLessThanOrEqual(viewport.height)
  }
  await assertActionFits()
  // Create/template have fixed footer actions. Detail has a fixed header close control;
  // its optional top-body "Скрыть задание" naturally scrolls away during review focus.
  const scrollBody = drawer.locator('.scrollbar-thin').first()
  await scrollBody.evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await assertActionFits()
  await scrollBody.evaluate((element) => {
    element.scrollTop = 0
  })
  await assertActionFits()
  await drawer.focus()
  await page.keyboard.press('Shift+Tab')
  expect(await drawer.evaluate((element) => element.contains(document.activeElement))).toBe(true)
}

for (const width of [1280, 1440, 1920]) {
  test(`native merch right drawers retain usable actions and contained scrolling at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await openWorkspace(page)
    await assertPageFits(page)
    const create = page.getByRole('button', { name: 'Новое задание', exact: true })
    await create.click()
    const drawer = page.getByRole('dialog').first()
    await assertDrawer(
      page,
      drawer,
      drawer.getByRole('button', { name: 'Создать задание', exact: true }),
    )
    await drawer.getByRole('checkbox', { name: 'Назначить нескольким аптекам' }).check()
    await expect(drawer.getByLabel('Аптеки массового задания')).toBeVisible()
    await drawer
      .getByLabel('Аптеки массового задания')
      .selectOption(branches.map((branch) => branch.id))
    await expect(drawer.getByLabel('Вознаграждение нового задания')).toBeDisabled()
    await page.screenshot({
      path: testInfo.outputPath(`merch-create-${width}.png`),
      animations: 'disabled',
    })
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(create).toBeFocused()

    const templateButton = page.getByRole('button', { name: 'По шаблону', exact: true })
    await templateButton.click()
    const template = page.getByRole('dialog', { name: 'Задание по шаблону', exact: true })
    await assertDrawer(
      page,
      template,
      template.getByRole('button', { name: 'Создать задания по шаблону', exact: true }),
    )
    await expect(template.getByRole('combobox', { name: 'Шаблон задания' })).toBeEnabled()
    await page.screenshot({
      path: testInfo.outputPath(`merch-template-${width}.png`),
      animations: 'disabled',
    })
    await template.getByRole('button', { name: 'Закрыть окно', exact: true }).click()
    await expect(templateButton).toBeFocused()

    await page.getByRole('button', { name: taskTitle, exact: true }).click()
    const detail = page.getByRole('dialog').first()
    await expect(detail.getByRole('heading', { name: taskTitle, exact: true })).toBeVisible()
    await assertDrawer(
      page,
      detail,
      detail.getByRole('button', { name: 'Закрыть окно', exact: true }),
    )
    await expect(detail.getByRole('link', { name: 'Открыть в личном кабинете' })).toHaveAttribute(
      'href',
      '/learn/merch/tasks/layout-task',
    )
    await page.screenshot({
      path: testInfo.outputPath(`merch-detail-${width}.png`),
      animations: 'disabled',
    })
    await detail.getByRole('button', { name: 'Скрыть задание', exact: true }).click()
    await assertPageFits(page)
  })
}

test('390px drawers fit the viewport without claiming that the desktop HQ shell is mobile', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openWorkspace(page)
  // The existing HQ shell intentionally keeps its 1280px minimum. Do not override its CSS.
  await page.getByRole('button', { name: 'Новое задание', exact: true }).click()
  let drawer = page.getByRole('dialog').first()
  await assertDrawer(
    page,
    drawer,
    drawer.getByRole('button', { name: 'Создать задание', exact: true }),
  )
  await page.screenshot({
    path: testInfo.outputPath('merch-create-390.png'),
    animations: 'disabled',
  })
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'По шаблону', exact: true }).click()
  drawer = page.getByRole('dialog', { name: 'Задание по шаблону', exact: true })
  await assertDrawer(
    page,
    drawer,
    drawer.getByRole('button', { name: 'Создать задания по шаблону', exact: true }),
  )
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: taskTitle, exact: true }).click()
  drawer = page.getByRole('dialog').first()
  await expect(drawer.getByRole('heading', { name: taskTitle, exact: true })).toBeVisible()
  await assertDrawer(
    page,
    drawer,
    drawer.getByRole('button', { name: 'Закрыть окно', exact: true }),
  )
  await page.screenshot({
    path: testInfo.outputPath('merch-detail-390.png'),
    animations: 'disabled',
  })
})

test('nested review Escape dismisses only the review and restores focus without submitting or closing the task', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openWorkspace(page)
  const opener = page.getByRole('button', { name: taskTitle, exact: true })
  await opener.click()
  const detail = page.getByRole('dialog').first()
  const permitted = detail.getByRole('region', {
    name: 'Пункт: Полка с доступной проверкой',
    exact: true,
  })
  const readonly = detail.getByRole('region', {
    name: 'Пункт: Полка без права проверки',
    exact: true,
  })
  await expect(readonly.getByRole('button', { name: 'Принять пункт', exact: true })).toHaveCount(0)
  const reviewButton = permitted.getByRole('button', {
    name: 'Вернуть пункт на доработку',
    exact: true,
  })
  await reviewButton.click()
  const review = page.getByRole('dialog', { name: 'Вернуть пункт на доработку', exact: true })
  await expect(page.getByRole('dialog')).toHaveCount(2)
  await expect(review.getByRole('button', { name: 'Подтвердить решение' })).toBeDisabled()
  await review.getByLabel('Причина проверки пункта').fill('Тестовая причина, которую не отправляем')
  await expect(review.getByRole('button', { name: 'Подтвердить решение' })).toBeEnabled()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(1)
  await expect(detail).toBeVisible()
  await expect(reviewButton).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(opener).toBeFocused()
})

test('queue search pagination Excel monthly visit and template surfaces keep their native contracts', async ({
  page,
  apiFixture,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openWorkspace(page)
  const queue = page.getByRole('combobox', { name: 'Рабочая очередь заданий' })
  await queue.selectOption('review')
  await expect(page.getByRole('heading', { name: 'Отчёты на проверке', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Далее', exact: true }).click()
  await expect
    .poll(() =>
      apiFixture.reads.some(
        (url) =>
          url.pathname === '/api/admin/merch/tasks' &&
          url.searchParams.get('offset') === '20' &&
          url.searchParams.get('queue') === 'review',
      ),
    )
    .toBe(true)
  await page.getByLabel('Поиск заданий').fill('тестовый поиск')
  await page.getByRole('button', { name: 'Найти', exact: true }).click()
  await expect
    .poll(() =>
      apiFixture.reads.some(
        (url) =>
          url.pathname === '/api/admin/merch/tasks' &&
          url.searchParams.get('q') === 'тестовый поиск' &&
          url.searchParams.get('offset') === '0',
      ),
    )
    .toBe(true)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Excel: задания', exact: true }).click()
  expect((await download).suggestedFilename()).toBe('merch-tasks.xlsx')
  await page.getByRole('button', { name: 'Месячный визит', exact: true }).click()
  await page.getByRole('combobox', { name: 'Аптека визита' }).selectOption(branches[0].id)
  await expect(
    page.getByRole('button', { name: 'Начать или продолжить визит', exact: true }),
  ).toBeEnabled()
  await page.getByRole('button', { name: 'По шаблону', exact: true }).click()
  const template = page.getByRole('dialog', { name: 'Задание по шаблону', exact: true })
  await template.getByRole('combobox', { name: 'Шаблон задания' }).selectOption('photo_report')
  await expect(template.getByRole('combobox', { name: 'Режим полок' })).toHaveValue('occupied')
  await expect(template.getByLabel('Месяц размещения')).toBeVisible()
  await expect(template.getByLabel('Вознаграждение шаблонного задания')).toBeDisabled()
})
