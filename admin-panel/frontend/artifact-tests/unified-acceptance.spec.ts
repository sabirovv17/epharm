import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { chmodSync, readFileSync, statSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'

// Opt in only against acceptance_runtime.py's empty synthetic database. No API stubs,
// external requests, trace, or automatic screenshots containing authentication data.
test.use({ trace: 'off', screenshot: 'off', video: 'off' })
test.describe.configure({ mode: 'serial', timeout: 240_000 })
const fixturePath = process.env.UNIFIED_ACCEPTANCE_FIXTURE
interface Fixture {
  schemaVersion: number; syntheticOnly: boolean; runId: string; apiUrl: string; frontendUrl: string
  ids: Record<string, string>; hq: { email: string; password: string }
  learner: { iin: string; password: string }; producerPhone: string; syntheticOtp: string
  deviceKey: string; pharmacyId: string; foreignPharmacyId: string; deviceId: string
}
interface Grant {
  module: string; subsection: string; action: string
  allPharmacies: boolean; pharmacyIds: string[]; allMerchBranches: boolean
  merchBranchIds: string[]; allProducers: boolean; producerIds: string[]
}
interface Session { tokens: { accessToken: string; refreshToken: string }; user: Record<string, unknown> }
interface Profile { accountId: string; version: number }
interface Item { id: string; version: number; checklist: { id: string }[]; currentAttempt: { id: string; number: number } | null }
interface Dispatch { id: string; status: string; items: Item[] }
interface Plan { id: string; revision: number; status: string }
let fixture: Fixture
let hq: Session
let scoped: Session
let scopedProfile: Profile
let task: Dispatch
let foreignTask: Dispatch
let producerSession: { accessToken: string; refreshToken: string; customer: Record<string, unknown> }
let learnerSession: { tokens: { accessToken: string; refreshToken: string } }

function loopback(value: string): URL {
  const url = new URL(value)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password) throw new Error('Synthetic loopback URLs required')
  return url
}
async function call(request: APIRequestContext, method: string, path: string, token?: string, data?: unknown, statuses = [200]) {
  if (!path.startsWith('/api/') || path.includes('://')) throw new Error('Relative API path required')
  const response = await request.fetch(fixture.apiUrl + path, {
    method, data, headers: token ? { Authorization: `Bearer ${token}` } : {}, maxRedirects: 0, timeout: 40_000,
  })
  // Do not include response bodies/tokens in assertion errors or reports.
  if (!statuses.includes(response.status())) {
    const body = await response.json().catch(() => ({})) as { code?: string }
    throw new Error(`${method} ${path.split('?')[0]}: HTTP ${response.status()}, code=${body.code ?? 'unknown'}`)
  }
  return response
}
async function json<T>(request: APIRequestContext, method: string, path: string, token?: string, data?: unknown, statuses = [200]): Promise<T> {
  return (await call(request, method, path, token, data, statuses)).json() as Promise<T>
}
const token = () => hq.tokens.accessToken
function scopedGrant(section: string, action: string): Grant {
  return { module: 'merchandising', subsection: section, action, allPharmacies: false, pharmacyIds: [],
    allMerchBranches: false, merchBranchIds: [fixture.ids.branchA], allProducers: section === 'placements', producerIds: [] }
}
async function wireBrowser(page: Page, baseURL: string) {
  const origin = loopback(baseURL).origin
  if (origin !== loopback(fixture.frontendUrl).origin) throw new Error('Frontend fixture origin mismatch')
  const blocked: string[] = []
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== origin || url.pathname.startsWith('/src/') || url.pathname.startsWith('/@')) {
      blocked.push(url.origin); return route.abort('blockedbyclient')
    }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    // Transport only: preserve actual backend status, headers, and bytes.
    const response = await route.fetch({ url: fixture.apiUrl + url.pathname + url.search, maxRedirects: 0, timeout: 40_000 })
    await route.fulfill({ response })
  })
  return blocked
}
async function xlsx(request: APIRequestContext, path: string, auth: string) {
  const response = await call(request, 'GET', path, auth)
  expect(response.headers()['content-type']?.includes('spreadsheetml.sheet')).toBe(true)
  const bytes = await response.body()
  expect(bytes.subarray(0, 2).equals(Buffer.from('PK'))).toBe(true)
  expect(bytes.length > 500).toBe(true)
  expect(response.headers()['cache-control']?.includes('no-store')).toBe(true)
}
async function managed(request: APIRequestContext, role: string, grants: Grant[]) {
  const person = await json<{ personId: string }>(request, 'POST', '/api/admin/access/people', token(), {
    name: `Synthetic ${role}`, verificationReference: 'SYNTHETIC_ACCEPTANCE_ONLY', currentPassword: fixture.hq.password,
  }, [201])
  const credentials = { email: `role-${randomUUID()}@acceptance.invalid`, password: fixture.hq.password }
  let profile = await json<Profile>(request, 'POST', '/api/admin/access/accounts', token(), {
    ...credentials, name: `Synthetic ${role}`, role, verifiedPersonId: person.personId, currentPassword: fixture.hq.password,
  }, [201])
  profile = await json(request, 'PUT', `/api/admin/access/accounts/${profile.accountId}/grants`, token(), {
    expectedVersion: profile.version, currentPassword: fixture.hq.password, grants,
  })
  return { profile, session: await json<Session>(request, 'POST', '/api/admin/auth/login', undefined, credentials) }
}

test.beforeAll(async ({ request }) => {
  test.skip(!fixturePath, 'Requires an explicit synthetic acceptance fixture, never a preserved candidate')
  if (!fixturePath || (statSync(fixturePath).mode & 0o077) !== 0) throw new Error('Owner-only fixture required')
  fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixture
  if (fixture.schemaVersion !== 1 || fixture.syntheticOnly !== true) throw new Error('Synthetic fixture required')
  loopback(fixture.apiUrl); loopback(fixture.frontendUrl)
  // Verify Docker labels/images/internal network, not just a caller-supplied boolean.
  const manifestPath = resolve(dirname(fixturePath), 'manifest.json')
  execFileSync('python3', [resolve('../../tools/unification/acceptance_runtime.py'), 'verify', '--manifest', manifestPath], { stdio: 'pipe' })
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as { apiUrl: string; runId: string }
  if (manifest.apiUrl !== fixture.apiUrl || manifest.runId !== fixture.runId) throw new Error('Fixture differs from the pinned synthetic runtime')
  hq = await json(request, 'POST', '/api/admin/auth/login', undefined, fixture.hq)
})

test('HQ creates an explicitly scoped MAS; foreign task, finance, training and management deny', async ({ request }) => {
  const suffix = randomUUID()
  const person = await json<{ personId: string }>(request, 'POST', '/api/admin/access/people', token(), {
    name: 'Synthetic scoped operator', verificationReference: 'SYNTHETIC_ACCEPTANCE_ONLY', currentPassword: fixture.hq.password,
  }, [201])
  const credentials = { email: `scope-${suffix}@acceptance.invalid`, password: fixture.hq.password }
  scopedProfile = await json(request, 'POST', '/api/admin/access/accounts', token(), {
    ...credentials, name: 'Synthetic scoped MAS', role: 'CATEGORY_LEAD', verifiedPersonId: person.personId,
    jobTitle: 'Synthetic acceptance operator', currentPassword: fixture.hq.password,
  }, [201])
  scopedProfile = await json(request, 'PUT', `/api/admin/access/accounts/${scopedProfile.accountId}/grants`, token(), {
    expectedVersion: scopedProfile.version, currentPassword: fixture.hq.password,
    grants: [scopedGrant('tasks', 'view'), scopedGrant('placements', 'view'), scopedGrant('placements', 'export')],
  })
  scoped = await json(request, 'POST', '/api/admin/auth/login', undefined, credentials)
  const me = await json<{ canViewMerchTasks: boolean; canManageAccounts: boolean }>(request, 'GET', '/api/admin/access/me', scoped.tokens.accessToken)
  expect(me.canViewMerchTasks).toBe(true); expect(me.canManageAccounts).toBe(false)
  for (const path of ['/api/admin/access/accounts', '/api/admin/reports/ledger', '/api/admin/training/programs']) {
    await call(request, 'GET', path, scoped.tokens.accessToken, undefined, [403])
  }
  const create = (branchId: string) => ({ branchId, title: 'Synthetic acceptance task', dueAt: new Date(Date.now() + 86400_000).toISOString(),
    rewardAmountKzt: 0, assignedPharmacistId: branchId === fixture.ids.branchA ? 'accept-learner' : null,
    items: [{ title: 'Synthetic evidence', requiredPhotos: 1, checklist: ['Confirm synthetic shelf'] }], taskType: 'photo_report' })
  task = await json(request, 'POST', '/api/admin/merch/tasks', token(), create(fixture.ids.branchA))
  foreignTask = await json(request, 'POST', '/api/admin/merch/tasks', token(), create(fixture.ids.branchB))
  await call(request, 'GET', `/api/admin/merch/tasks/${task.id}`, scoped.tokens.accessToken)
  await call(request, 'GET', `/api/admin/merch/tasks/${foreignTask.id}`, scoped.tokens.accessToken, undefined, [403])
  await call(request, 'GET', `/api/admin/merch/exports/tasks.xlsx?branchId=${fixture.ids.branchB}`, scoped.tokens.accessToken, undefined, [403])
})

test('regional branch scope and training manager current-pharmacy scope remain distinct', async ({ request }) => {
  const regional = await managed(request, 'REGIONAL_MANAGER', [scopedGrant('tasks', 'view')])
  await call(request, 'GET', `/api/admin/merch/tasks/${task.id}`, regional.session.tokens.accessToken)
  await call(request, 'GET', `/api/admin/merch/tasks/${foreignTask.id}`, regional.session.tokens.accessToken, undefined, [403])
  const program = await json<{ id: string }>(request, 'POST', '/api/admin/training/programs', token(), {
    name: 'Synthetic acceptance program', allowedFormats: ['offline'], status: 'published', completionBonus: 0,
  })
  const event = await json<{ id: string }>(request, 'POST', '/api/admin/training/events', token(), {
    programId: program.id, title: 'Synthetic acceptance event', startsAt: new Date(Date.now() + 86400_000).toISOString(),
    endsAt: new Date(Date.now() + 90000_000).toISOString(), region: 'Synthetic City A', city: 'Synthetic City A', capacity: 10, status: 'scheduled',
  })
  const assigned = await json<{ assignments: { id: string; pharmacistId: string }[] }>(request, 'POST', '/api/admin/training/assignments', token(), {
    programId: program.id, pharmacistIds: ['accept-learner', 'accept-learner-foreign'], format: 'offline', eventId: event.id,
  })
  expect(assigned.assignments.length).toBe(2)
  const learningGrant = (section: string, action: string): Grant => ({ module: 'training', subsection: section, action,
    allPharmacies: false, pharmacyIds: [fixture.pharmacyId], allMerchBranches: false, merchBranchIds: [], allProducers: false, producerIds: [] })
  const trainer = await managed(request, 'TRAINING_MANAGER', [learningGrant('assignments', 'view'), learningGrant('assignments', 'export'),
    learningGrant('results', 'view'), learningGrant('results', 'export')])
  const auth = trainer.session.tokens.accessToken
  const rows = await json<{ pharmacistId: string }[]>(request, 'GET', '/api/admin/training/assignments', auth)
  expect(rows.length).toBe(1); expect(rows[0].pharmacistId === 'accept-learner').toBe(true)
  const foreign = assigned.assignments.find(row => row.pharmacistId === 'accept-learner-foreign')!
  await call(request, 'GET', `/api/admin/training/assignments/${foreign.id}/results`, auth, undefined, [403])
  await call(request, 'GET', `/api/admin/merch/tasks/${task.id}`, auth, undefined, [403])
  await xlsx(request, '/api/admin/training/exports/assignments.xlsx', auth)
})

test('producer OTP realm creates and submits a plan; HQ approves; real XLSX and separate realm denial', async ({ request, page, baseURL }) => {
  const blocked = await wireBrowser(page, baseURL!)
  await page.goto('/producer/login')
  await page.getByRole('textbox', { name: 'Телефон производителя' }).fill(fixture.producerPhone)
  await page.getByRole('button', { name: 'Получить SMS-код', exact: true }).click()
  await page.getByRole('textbox', { name: 'SMS-код производителя' }).fill(fixture.syntheticOtp)
  const verified = page.waitForResponse(response => response.url().endsWith('/api/producer/auth/sms/verify'))
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  producerSession = await (await verified).json()
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible({ timeout: 30_000 })
  await call(request, 'GET', '/api/admin/access/me', producerSession.accessToken, undefined, [401, 403])
  await call(request, 'GET', '/api/producer/auth/me', token(), undefined, [401, 403])
  const month = new Date(); month.setUTCMonth(month.getUTCMonth() + 1, 1)
  let plan = await json<Plan>(request, 'POST', '/api/producer/placements', producerSession.accessToken, {
    placementMonth: month.toISOString().slice(0, 7) + '-01', items: [{ shelfId: fixture.ids.shelfA, portion: 'whole' }],
  })
  plan = await json(request, 'POST', `/api/producer/placements/${plan.id}/submit`, producerSession.accessToken, { expectedRevision: plan.revision })
  expect(plan.status).toBe('pending')
  plan = await json(request, 'POST', `/api/admin/merch/placements/${plan.id}/approve`, token(), { expectedRevision: plan.revision })
  expect(plan.status).toBe('approved')
  await xlsx(request, `/api/admin/merch/exports/placements.xlsx?branchId=${fixture.ids.branchA}`, scoped.tokens.accessToken)
  await expect(page.getByText('Synthetic producer', { exact: true }).first()).toBeVisible()
  expect(blocked).toEqual([])
})

test('independent learner login executes and submits a task; HQ independently reviews; POSM binding and ACK remain real', async ({ request, page, baseURL }) => {
  const person = await json<{ personId: string }>(request, 'POST', '/api/admin/access/people', token(), {
    name: 'Synthetic learner proof', verificationReference: 'SYNTHETIC_ACCEPTANCE_ONLY', currentPassword: fixture.hq.password,
  }, [201])
  await call(request, 'POST', '/api/admin/access/pharmacist-person-links', token(), {
    pharmacistId: 'accept-learner', verifiedPersonId: person.personId, verificationReference: 'SYNTHETIC_ACCEPTANCE_ONLY', currentPassword: fixture.hq.password,
  })
  const blocked = await wireBrowser(page, baseURL!)
  await page.addInitScript(({ admin, producer }) => {
    localStorage.setItem('epharm.auth.tokens', JSON.stringify(admin.tokens))
    localStorage.setItem('epharm.auth.user', JSON.stringify(admin.user))
    sessionStorage.setItem('epharm.producer.session.v1', JSON.stringify(producer))
  }, { admin: hq, producer: producerSession })
  await page.goto('/learn')
  await page.getByLabel('ИИН', { exact: true }).fill(fixture.learner.iin)
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click()
  await page.getByLabel('Пароль', { exact: true }).fill(fixture.learner.password)
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await expect(page.getByText('Synthetic learner', { exact: true }).first()).toBeVisible({ timeout: 30_000 })
  const boundary = await page.evaluate(() => ({ admin: Boolean(localStorage.getItem('epharm.auth.tokens')), producer: Boolean(sessionStorage.getItem('epharm.producer.session.v1')), learner: Boolean(localStorage.getItem('epharm.learner.tokens')) }))
  expect(boundary).toEqual({ admin: true, producer: true, learner: true })
  learnerSession = await json(request, 'POST', '/api/mobile/auth/login', undefined, fixture.learner)
  const mobile = learnerSession.tokens.accessToken
  await call(request, 'GET', '/api/admin/access/me', mobile, undefined, [401, 403])
  await call(request, 'GET', `/api/mobile/merch/tasks/${foreignTask.id}`, mobile, undefined, [403])
  const headers = { 'X-Posm-Key': fixture.deviceKey, 'X-Device-Id': fixture.deviceId }
  const poll = await request.get(`${fixture.apiUrl}/api/posm/tasks?pharmacyId=${fixture.pharmacyId}`, { headers, timeout: 40_000 })
  expect(poll.status()).toBe(200)
  const envelope = await poll.json() as { task: { id: string; deliveryToken: string; publicUrl: string } | null }
  expect(envelope.task?.id === task.id).toBe(true)
  expect(envelope.task?.publicUrl.startsWith('https://epharm.inkar.kz/tasks/staff?task=1#task=')).toBe(true)
  const ackBody = { dispatchId: task.id, pharmacyId: fixture.pharmacyId, deviceId: fixture.deviceId, deliveryToken: envelope.task!.deliveryToken }
  for (let n = 0; n < 2; n++) {
    const ack = await request.post(fixture.apiUrl + '/api/posm/tasks/shown', { headers, data: ackBody, timeout: 40_000 })
    expect(ack.status()).toBe(200); expect((await ack.json() as { accepted: boolean }).accepted).toBe(true)
  }
  const wrong = await request.get(`${fixture.apiUrl}/api/posm/tasks?pharmacyId=${fixture.foreignPharmacyId}`, { headers, timeout: 40_000 })
  expect([401, 403].includes(wrong.status())).toBe(true)
  const itemId = task.items[0].id
  await call(request, 'POST', `/api/mobile/merch/tasks/items/${itemId}/attempts`, mobile)
  // Generated solid 16x12 PNG (stdlib zlib/CRC), no source/private photo.
  const photo = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAABAAAAAMCAIAAADkharWAAAAFUlEQVR4nGNwmLCBJMQwqmFUA3YEAIGiIBDyuwbjAAAAAElFTkSuQmCC', 'base64')
  const upload = await request.post(`${fixture.apiUrl}/api/mobile/merch/tasks/items/${itemId}/evidence?clientUploadId=${randomUUID()}`, {
    headers: { Authorization: `Bearer ${mobile}` }, multipart: { file: { name: 'synthetic.png', mimeType: 'image/png', buffer: photo } }, timeout: 40_000,
  })
  expect(upload.status()).toBe(200)
  task = await json(request, 'GET', `/api/mobile/merch/tasks/${task.id}`, mobile)
  await call(request, 'POST', `/api/mobile/merch/tasks/items/${itemId}/submit`, mobile, {
    expectedAttempt: task.items[0].currentAttempt!.number, completedChecklistIds: task.items[0].checklist.map(row => row.id), comment: 'Synthetic acceptance only',
  })
  task = await json(request, 'GET', `/api/admin/merch/tasks/${task.id}`, token())
  await call(request, 'POST', `/api/admin/merch/tasks/items/${itemId}/reviews`, token(), {
    attemptId: task.items[0].currentAttempt!.id, expectedItemVersion: task.items[0].version, decision: 'approve', reason: 'Synthetic independent review',
  })
  await xlsx(request, `/api/admin/merch/exports/tasks.xlsx?branchId=${fixture.ids.branchA}`, token())
  const receipt = `synthetic-${randomUUID()}`
  const body = { pharmacyId: fixture.pharmacyId, sessionId: receipt, cart: [{ sku: 'ACCEPT-TRIGGER', qty: 1 }] }
  const recommend = await request.post(fixture.apiUrl + '/api/posm/recommend', { headers, data: body, timeout: 40_000 })
  expect(recommend.status()).toBe(200)
  const events = (await recommend.json() as { recommendations: { eventId: string }[] }).recommendations
  expect(events.length).toBe(1)
  const repeat = await request.post(fixture.apiUrl + '/api/posm/recommend', { headers, data: body, timeout: 40_000 })
  expect(repeat.status()).toBe(200)
  expect((await repeat.json() as { recommendations: { eventId: string }[] }).recommendations[0].eventId === events[0].eventId).toBe(true)
  for (let n = 0; n < 2; n++) {
    const shown = await request.post(`${fixture.apiUrl}/api/posm/recommendations/${events[0].eventId}/shown`, { headers, data: { shownAt: new Date().toISOString() }, timeout: 40_000 })
    expect(shown.status()).toBe(200)
  }
  const outcome = await request.post(`${fixture.apiUrl}/api/posm/recommendations/${events[0].eventId}/outcome`, { headers, data: { outcome: 'rejected' }, timeout: 40_000 })
  expect(outcome.status()).toBe(200)
  expect(blocked).toEqual([])
})

test('revocation applies to the existing JWT without relogin; browser receives fresh denial', async ({ request, page, baseURL }) => {
  await call(request, 'PUT', `/api/admin/access/accounts/${scopedProfile.accountId}/grants`, token(), {
    expectedVersion: scopedProfile.version, currentPassword: fixture.hq.password, grants: [],
  })
  await call(request, 'GET', `/api/admin/merch/tasks/${task.id}`, scoped.tokens.accessToken, undefined, [403])
  const me = await json<{ canViewMerchTasks: boolean }>(request, 'GET', '/api/admin/access/me', scoped.tokens.accessToken)
  expect(me.canViewMerchTasks).toBe(false)
  const blocked = await wireBrowser(page, baseURL!)
  await page.addInitScript(value => {
    localStorage.setItem('epharm.auth.tokens', JSON.stringify(value.tokens)); localStorage.setItem('epharm.auth.user', JSON.stringify(value.user))
  }, scoped)
  await page.goto('/merch/tasks')
  await expect(page.getByRole('heading', { name: 'Нет доступа к заданиям мерча', exact: true })).toBeVisible({ timeout: 30_000 })
  expect(blocked).toEqual([])
})

test('D9 explicit scoped HQ proof downloads one protected workbook; ordinary registry and old HQ role expose no secret', async ({ request, page, baseURL }) => {
  const ordinary = await managed(request, 'HQ_HEAD', [])
  const original = await json<{ canExportLearnerCredentials: boolean }>(request, 'GET', '/api/admin/access/me', ordinary.session.tokens.accessToken)
  expect(original.canExportLearnerCredentials).toBe(false)
  const grant = (action: string): Grant => ({ module: 'pharmacists', subsection: 'registry', action,
    allPharmacies: false, pharmacyIds: [fixture.pharmacyId], allMerchBranches: false, merchBranchIds: [], allProducers: false, producerIds: [] })
  const owner = await managed(request, 'HQ_HEAD', [grant('view'), grant('personal_data'), grant('export_credentials')])
  const auth = owner.session.tokens.accessToken
  await call(request, 'POST', '/api/admin/learner-credentials/exports', auth, {
    pharmacistIds: ['accept-learner-foreign'], purpose: 'Synthetic foreign scope denial', currentPassword: fixture.hq.password,
  }, [403])
  const blocked = await wireBrowser(page, baseURL!)
  await page.addInitScript(value => {
    localStorage.setItem('epharm.auth.tokens', JSON.stringify(value.tokens)); localStorage.setItem('epharm.auth.user', JSON.stringify(value.user))
  }, owner.session)
  await page.goto('/pharmacists')
  await page.getByRole('checkbox', { name: 'Выбрать Synthetic learner', exact: true }).check({ timeout: 30_000 })
  await expect(page.getByRole('checkbox', { name: 'Выбрать Synthetic foreign learner', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Учётные данные ЛК', exact: true }).click()
  await page.getByLabel('Цель выгрузки', { exact: true }).fill('Synthetic general acceptance')
  await page.getByLabel('Текущий пароль администратора', { exact: true }).fill(fixture.hq.password)
  const preparedResponse = page.waitForResponse(response => response.url().endsWith('/api/admin/learner-credentials/exports') && response.request().method() === 'POST')
  const downloading = page.waitForEvent('download', { timeout: 60_000 })
  await page.getByRole('button', { name: 'Подтвердить и скачать', exact: true }).click()
  const download = await downloading
  const prepared = await (await preparedResponse).json() as { id: string }
  const destination = resolve(dirname(fixturePath!), `synthetic-credentials-${randomUUID()}.xlsx`)
  await download.saveAs(destination); chmodSync(destination, 0o600)
  expect(readFileSync(destination).subarray(0, 2).equals(Buffer.from('PK'))).toBe(true)
  const text = execFileSync('unzip', ['-p', destination, 'xl/sharedStrings.xml'], { encoding: 'utf8' })
  // Only boolean assertions; never print the synthetic password or workbook content.
  expect(text.includes(fixture.learner.password)).toBe(true)
  expect(text.includes('accept-learner-foreign')).toBe(false)
  await call(request, 'POST', `/api/admin/learner-credentials/exports/${prepared.id}/download`, auth, { currentPassword: fixture.hq.password }, [404, 410])
  const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))
  expect(storage.includes(fixture.hq.password)).toBe(false)
  expect(storage.includes('Synthetic general acceptance')).toBe(false)
  expect(blocked).toEqual([])
})

test('both installed POSM version heartbeat shapes and repeated synthetic sale use device authentication', async ({ request }) => {
  const headers = { 'X-Posm-Key': fixture.deviceKey }
  for (const appVersion of ['1.0.57', '1.0.66']) {
    // Legacy clients need not supply monitorCount/staffAuthStatus.
    const response = await request.post(`${fixture.apiUrl}/api/posm/heartbeat?deviceId=${fixture.deviceId}&pharmacyId=${fixture.pharmacyId}&appVersion=${appVersion}`, { headers, timeout: 40_000 })
    expect(response.status()).toBe(200)
    expect((await response.json() as { ok: boolean }).ok).toBe(true)
  }
  const body = { saleId: `synthetic-sale-${randomUUID()}`, pharmacyId: fixture.pharmacyId, sourceDocumentId: 101,
    totalAmount: 1000, items: [{ sku: 'ACCEPT-TRIGGER', name: 'Synthetic trigger', qty: 1, price: 1000, total: 1000 }], printedAt: new Date().toISOString() }
  for (let n = 0; n < 2; n++) {
    const response = await request.post(fixture.apiUrl + '/api/posm/sales', { headers, data: body, timeout: 40_000 })
    expect(response.status()).toBe(200)
  }
  const denied = await request.post(fixture.apiUrl + '/api/posm/sales', { headers, data: { ...body, pharmacyId: fixture.foreignPharmacyId }, timeout: 40_000 })
  expect([401, 403].includes(denied.status())).toBe(true)
})
