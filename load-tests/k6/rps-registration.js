import http from 'k6/http'
import { check } from 'k6'
import exec from 'k6/execution'
import { SharedArray } from 'k6/data'
import { Counter } from 'k6/metrics'
import { localOrigin, validateFixture, failureKind, sessionValid, recommendationValid, playlistValid, registrationVuBudget } from './rps-contract.mjs'

const origin = localOrigin(__ENV.BASE_URL)
if (__ENV.ISOLATED_RPS_LOAD_TEST !== 'DISPOSABLE_DATABASE_NO_SMS') throw new Error('Explicit isolated-run acknowledgement required')
const fixturePath = __ENV.RPS_FIXTURE_FILE
if (!fixturePath) throw new Error('RPS_FIXTURE_FILE is required')
const metadata = validateFixture(JSON.parse(open(fixturePath)))
const devices = new SharedArray('synthetic RPS devices', () => metadata.devices)
const mode = __ENV.MODE || 'register'
if (!['register', 'login', 'sessions'].includes(mode)) throw new Error('MODE must be register, login or sessions')
const concurrency = Number(__ENV.VUS || 25)
const rounds = mode === 'login' ? Number(__ENV.LOGIN_ROUNDS || 1) : 1
const schedule = __ENV.SCHEDULE || 'minute'
if (!['minute', 'burst'].includes(schedule)) throw new Error('SCHEDULE must be minute or burst')
if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > devices.length ||
    !Number.isInteger(rounds) || rounds < 1 || rounds > 5) throw new Error('Invalid VUS or LOGIN_ROUNDS (1..5)')
const maxRpsVUs = mode === 'register' && schedule === 'minute' ? registrationVuBudget(concurrency, devices.length) : concurrency
const duration = __ENV.DURATION || '2m'
const output = __ENV.RPS_SUMMARY_FILE
if (!/^\/results\/rps-[a-z0-9_-]+\.json$/.test(output || '')) throw new Error('Unique RPS_SUMMARY_FILE under /results is required')
const errors = new Counter('rps_errors')
const rateLimited = new Counter('rps_rate_limited')
const upstream = new Counter('rps_5xx')
const transport = new Counter('rps_transport_errors')
const rejected = new Counter('rps_unexpected_4xx')
const redirected = new Counter('rps_unexpected_redirects')
const completed = new Counter('rps_completed')
const created = new Counter('rps_registration_created')
const sessionChecks = new Counter('rps_session_checks')

export const options = {
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '5m',
  teardownTimeout: '2m',
  scenarios: {
    ...(mode === 'sessions'
      ? { sessions: { executor: 'constant-arrival-rate', exec: 'sessions', rate: 500, timeUnit: '30s', duration, preAllocatedVUs: concurrency, maxVUs: concurrency } }
      : mode === 'register' && schedule === 'minute'
        ? { rps: { executor: 'constant-arrival-rate', exec: 'rps', rate: devices.length, timeUnit: '60s', duration: '60s', preAllocatedVUs: concurrency, maxVUs: maxRpsVUs } }
        : { rps: { executor: 'shared-iterations', exec: 'rps', vus: concurrency, iterations: devices.length * rounds, maxDuration: duration } }),
    heartbeat: { executor: 'constant-arrival-rate', exec: 'heartbeat', rate: 500, timeUnit: '60s', duration, preAllocatedVUs: 20, maxVUs: 50 },
    playlist: { executor: 'constant-arrival-rate', exec: 'playlist', rate: 500, timeUnit: '30s', duration, preAllocatedVUs: 40, maxVUs: 50 },
    recommendations: { executor: 'constant-arrival-rate', exec: 'recommendation', rate: 20, timeUnit: '1s', duration, preAllocatedVUs: 60, maxVUs: 100 },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count==0'],
    rps_errors: ['count==0'],
    rps_rate_limited: ['count==0'],
    rps_5xx: ['count==0'],
    rps_transport_errors: ['count==0'],
    rps_unexpected_4xx: ['count==0'],
    rps_unexpected_redirects: ['count==0'],
    ...(mode === 'sessions' ? { rps_session_checks: ['count>0'] } : { rps_completed: [`count==${devices.length * rounds}`] }),
    ...(mode === 'register' ? { rps_registration_created: [`count==${devices.length}`] } : {}),
    ...(mode === 'register' ? { 'http_req_duration{workload:rps-register}': ['p(95)<2000', 'p(99)<10000'] } : {}),
    'http_req_duration{workload:rps-login}': ['p(95)<2000', 'p(99)<10000'],
    'http_req_duration{workload:rps-check}': ['p(95)<1000', 'p(99)<3000'],
    'http_req_duration{workload:heartbeat}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{workload:playlist}': ['p(95)<750', 'p(99)<1500'],
    'http_req_duration{workload:recommendation}': ['p(95)<1000', 'p(99)<2000'],
  },
}

function params(target, workload, expected = 200, token = null) {
  return {
    headers: { 'X-Posm-Key': target.key, 'X-Device-Id': target.deviceId, 'Content-Type': 'application/json', ...(token ? { 'X-Staff-Session': token } : {}) },
    tags: { workload }, timeout: '15s', redirects: 0, responseCallback: http.expectedStatuses(expected),
  }
}
function body(response) { try { return response.json() } catch (_) { return null } }
function expect(response, status, workload, semantic = true) {
  const kind = failureKind(response.status)
  if (kind === 'rate_limited') rateLimited.add(1)
  if (kind === 'upstream') upstream.add(1)
  if (kind === 'transport') transport.add(1)
  if (kind === 'client' && response.status !== status) rejected.add(1)
  if (kind === 'redirect') redirected.add(1)
  const okay = check(response, { [`${workload}: expected status and payload`]: (r) => r.status === status && semantic })
  if (!okay) errors.add(1, { workload, status: String(response.status), kind: kind || 'semantic' })
  return okay
}
function post(target, path, value, workload, status = 200, token = null) {
  return http.post(`${origin}/api/posm/staff/${path}`, JSON.stringify(value), params(target, workload, status, token))
}
function validateSession(target, token, context, id) {
  const response = post(target, 'device/session/check', context, 'rps-check', 200, token)
  return expect(response, 200, 'rps-check', sessionValid(body(response), context, id, false))
}
export function setup() {
  // No credentials or mutation is sent before the isolated origin/fixture guards run.
  const health = http.get(`${origin}/api/health`, { redirects: 0, timeout: '15s' })
  if (health.status !== 200 || body(health)?.status !== 'ok') throw new Error('Isolated backend health check failed')
  for (const metric of [errors, rateLimited, upstream, transport, rejected, redirected, completed, created, sessionChecks]) metric.add(0)
  if (mode !== 'sessions') return null
  // Bootstrap once, outside sustained pressure; only synthetic credentials are used.
  return devices.map((desk, index) => {
    const context = { processInstanceId: `rps-session:${metadata.runId}:${index}` }
    const response = post(desk, 'device/login', { iin: desk.iin, password: desk.password, deviceContext: context }, 'rps-login')
    const grant = body(response)
    if (!expect(response, 200, 'rps-login', sessionValid(grant, context) && grant?.pharmacist?.name === desk.name)) {
      throw new Error('Synthetic session bootstrap failed; sustained scenario not started')
    }
    return { token: grant.sessionToken, id: grant.pharmacist.id, context }
  })
}
export function sessions(grants) {
  const index = exec.scenario.iterationInTest % devices.length
  const grant = grants[index]
  if (validateSession(devices[index], grant.token, grant.context, grant.id)) sessionChecks.add(1)
}
export function teardown(grants) {
  if (mode !== 'sessions' || !grants) return
  for (let index = 0; index < grants.length; index++) {
    expect(post(devices[index], 'device/logout', null, 'rps-logout', 204, grants[index].token), 204, 'rps-logout')
  }
}
export function rps() {
  const iteration = exec.scenario.iterationInTest
  // k6 can schedule a final boundary tick; never create/replay an extra profile.
  if (mode === 'register' && iteration >= devices.length) return
  const target = devices[iteration % devices.length]
  const context = { processInstanceId: `rps-load:${metadata.runId}:${iteration}` }
  const caps = http.get(`${origin}/api/posm/staff/capabilities`, params(target, 'rps-capabilities'))
  const capability = body(caps)
  if (!expect(caps, 200, 'rps-capabilities', capability?.enabled === true && capability.protocolVersion === 2 &&
      capability.deviceEmployeeAuthVersion === 1 && capability.registrationSmsRequired === false)) return
  let id = null
  if (mode === 'register') {
    const registration = { iin: target.iin, phone: target.phone, name: target.name, password: target.password, deviceContext: context }
    const response = post(target, 'device/register-without-sms', registration, 'rps-register')
    const grant = body(response)
    if (!expect(response, 200, 'rps-register', sessionValid(grant, context) && grant?.pharmacist?.name === target.name)) return
    created.add(1); id = grant.pharmacist.id
    if (!validateSession(target, grant.sessionToken, context, id)) return
    // Replaying a registration must reject the duplicate, never silently create/move/reset it.
    const duplicate = post(target, 'device/register-without-sms', registration, 'rps-duplicate', 400)
    if (!expect(duplicate, 400, 'rps-duplicate', body(duplicate)?.code === 'VALIDATION_FAILED')) return
  }
  const response = post(target, 'device/login', { iin: target.iin, password: target.password, deviceContext: context }, 'rps-login')
  const grant = body(response)
  if (!expect(response, 200, 'rps-login', sessionValid(grant, context, id) && grant?.pharmacist?.name === target.name)) return
  if (!validateSession(target, grant.sessionToken, context, grant.pharmacist.id)) return
  const logout = post(target, 'device/logout', null, 'rps-logout', 204, grant.sessionToken)
  if (expect(logout, 204, 'rps-logout')) completed.add(1)
}
function target() { return devices[exec.scenario.iterationInTest % devices.length] }
export function heartbeat() {
  const desk = target()
  const query = `deviceId=${encodeURIComponent(desk.deviceId)}&pharmacyId=${encodeURIComponent(desk.pharmacyId)}&monitorCount=2&appVersion=isolated-rps-load`
  const response = http.post(`${origin}/api/posm/heartbeat?${query}`, null, params(desk, 'heartbeat'))
  expect(response, 200, 'heartbeat')
}
export function playlist() {
  const desk = target()
  const response = http.get(`${origin}/api/posm/playlists/active?pharmacyId=${encodeURIComponent(desk.pharmacyId)}`, params(desk, 'playlist'))
  expect(response, 200, 'playlist', playlistValid(body(response), metadata.background))
}
export function recommendation() {
  const desk = target()
  const payload = { pharmacyId: desk.pharmacyId, pharmacistId: '', pharmacistName: 'RPS Synthetic', sessionId: `rps-load-${metadata.runId}-${exec.scenario.iterationInTest}`, scannedBarcode: metadata.background.triggerBarcode, cart: [{ barcode: metadata.background.triggerBarcode, name: 'RPS Synthetic trigger', qty: 1 }] }
  const response = http.post(`${origin}/api/posm/recommend`, JSON.stringify(payload), params(desk, 'recommendation'))
  expect(response, 200, 'recommendation', recommendationValid(body(response), payload.sessionId, metadata.background))
}
export function handleSummary(data) {
  return { stdout: JSON.stringify(data.metrics, null, 2), [output]: JSON.stringify({ fixture: { kind: metadata.kind, runId: metadata.runId, expectedDatabase: metadata.expectedDatabase, devices: devices.length }, mode, schedule, concurrency, maxRpsVUs, ...data }, null, 2) }
}
