import http from 'k6/http'
import { check, sleep } from 'k6'
import exec from 'k6/execution'
import { Counter, Trend } from 'k6/metrics'

const baseUrl = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '')
const posmKey = __ENV.POSM_DEVICE_KEY || 'dev-posm-key'
const posmKeysFile = (__ENV.POSM_DEVICE_KEYS_FILE || '').trim()
const posmKeys = (posmKeysFile ? open(posmKeysFile) : posmKey)
  .split(/[\r\n,]+/)
  .map((value) => value.trim())
  .filter(Boolean)
const pharmacyIds = (__ENV.PHARMACY_IDS || 'ph_auezova_134')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean)
const adminToken = (__ENV.ADMIN_TOKEN || '').trim()
const runId = __ENV.RUN_ID || `${Date.now()}`
const videoSizeKb = Math.max(1, Math.min(Number(__ENV.VIDEO_SIZE_KB || 256), 60 * 1024))
const videoPayload = new Uint8Array(videoSizeKb * 1024)
const loadErrors = new Counter('epharm_load_errors')
const offlineReplayLatency = new Trend('epharm_offline_replay_latency', true)

if (baseUrl.includes('epharm.inkar.kz') && __ENV.CONFIRM_PRODUCTION_LOAD !== 'I_ACCEPT_PRODUCTION_IMPACT') {
  throw new Error('Production load tests require CONFIRM_PRODUCTION_LOAD=I_ACCEPT_PRODUCTION_IMPACT')
}
if (pharmacyIds.length === 0) throw new Error('PHARMACY_IDS must contain at least one existing pharmacy id')
if (!/^[0-9A-Za-z._-]{1,80}$/.test(runId)) throw new Error('RUN_ID contains unsafe characters')
if (posmKeys.length !== 1 && posmKeys.length !== 500) {
  throw new Error('POSM credentials must contain one staging fleet key or exactly 500 per-device keys')
}

const allScenarios = {
  heartbeat: {
    executor: 'constant-arrival-rate',
    exec: 'heartbeat',
    rate: 500,
    timeUnit: '60s',
    duration: __ENV.DURATION || '10m',
    preAllocatedVUs: 40,
    maxVUs: 150,
    tags: { workload: 'heartbeat' },
  },
  playlist_polling: {
    executor: 'constant-arrival-rate',
    exec: 'playlistPolling',
    rate: 500,
    timeUnit: '30s',
    duration: __ENV.DURATION || '10m',
    preAllocatedVUs: 80,
    maxVUs: 250,
    tags: { workload: 'playlist' },
  },
  recommendations: {
    executor: 'constant-arrival-rate',
    exec: 'recommendation',
    rate: Number(__ENV.RECOMMENDATIONS_PER_SECOND || 20),
    timeUnit: '1s',
    duration: __ENV.DURATION || '10m',
    preAllocatedVUs: 80,
    maxVUs: 300,
    tags: { workload: 'recommendation' },
  },
  sales: {
    executor: 'constant-arrival-rate',
    exec: 'sale',
    rate: Number(__ENV.SALES_PER_SECOND || 10),
    timeUnit: '1s',
    duration: __ENV.DURATION || '10m',
    preAllocatedVUs: 50,
    maxVUs: 200,
    tags: { workload: 'sales' },
  },
  offline_sync: {
    executor: 'per-vu-iterations',
    exec: 'offlineSync',
    vus: 500,
    iterations: Number(__ENV.OFFLINE_EVENTS_PER_DESK || 10),
    startTime: __ENV.OFFLINE_SYNC_START || '2m',
    maxDuration: '10m',
    tags: { workload: 'offline-sync' },
  },
  video_uploads: {
    executor: 'constant-arrival-rate',
    exec: 'videoUpload',
    rate: Number(__ENV.VIDEO_UPLOADS_PER_MINUTE || 12),
    timeUnit: '1m',
    duration: __ENV.UPLOAD_DURATION || '10m',
    preAllocatedVUs: 3,
    maxVUs: 20,
    tags: { workload: 'video-upload' },
  },
}

const selected = (__ENV.SCENARIOS || 'heartbeat,playlist_polling,recommendations,sales,offline_sync')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean)

export const options = {
  discardResponseBodies: true,
  scenarios: Object.fromEntries(
    selected.map((name) => {
      if (!allScenarios[name]) throw new Error(`Unknown scenario: ${name}`)
      if (name === 'video_uploads' && !adminToken) throw new Error('video_uploads requires ADMIN_TOKEN')
      return [name, allScenarios[name]]
    }),
  ),
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '1m' }],
    'http_req_duration{workload:heartbeat}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{workload:playlist}': ['p(95)<750', 'p(99)<1500'],
    'http_req_duration{workload:recommendation}': ['p(95)<1000', 'p(99)<2000'],
    'http_req_duration{workload:sales}': ['p(95)<1000', 'p(99)<2500'],
    'http_req_duration{workload:offline-sync}': ['p(95)<1500', 'p(99)<3000'],
    'http_req_duration{workload:video-upload}': ['p(95)<10000', 'p(99)<20000'],
    epharm_load_errors: ['count==0'],
  },
}

function desk(index = exec.scenario.iterationInTest) {
  // __VU is global across concurrent scenarios and can exceed 500, creating
  // credential/device collisions. Scenario iteration ids are stable and unique.
  const id = (index % 500) + 1
  return {
    ordinal: id,
    deviceId: `load-kassa-${String(id).padStart(3, '0')}`,
    pharmacyId: pharmacyIds[(id - 1) % pharmacyIds.length],
  }
}

function posmParams(workload, target, extra = {}) {
  return {
    headers: {
      'X-Posm-Key': posmKeys.length === 1 ? posmKeys[0] : posmKeys[target.ordinal - 1],
      'Content-Type': 'application/json',
      ...extra,
    },
    tags: { workload },
  }
}

function assertResponse(response, expected, name) {
  const ok = check(response, {
    [`${name}: status ${expected}`]: (res) => res.status === expected,
  })
  if (!ok) loadErrors.add(1, { workload: name, status: String(response.status) })
}

function assertSaleResponse(response, expectedAccepted, name) {
  let payload = null
  try {
    payload = response.json()
  } catch (_) {
    // The checks below report a malformed/empty response without aborting the run.
  }
  const ok = check(response, {
    [`${name}: status 200`]: (res) => res.status === 200,
    [`${name}: accepted=${expectedAccepted}`]: () => payload?.accepted === expectedAccepted,
  })
  if (!ok) loadErrors.add(1, { workload: name, status: String(response.status) })
}

function saleParams(workload, target) {
  return { ...posmParams(workload, target), responseType: 'text' }
}

export function heartbeat() {
  const target = desk()
  const query = `deviceId=${encodeURIComponent(target.deviceId)}&pharmacyId=${encodeURIComponent(target.pharmacyId)}&monitorCount=2&appVersion=load-test`
  const response = http.post(`${baseUrl}/api/posm/heartbeat?${query}`, null, posmParams('heartbeat', target))
  assertResponse(response, 200, 'heartbeat')
}

export function playlistPolling() {
  const target = desk()
  const response = http.get(
    `${baseUrl}/api/posm/playlists/active?pharmacyId=${encodeURIComponent(target.pharmacyId)}`,
    posmParams('playlist', target),
  )
  assertResponse(response, 200, 'playlist')
}

export function recommendation() {
  const target = desk()
  const payload = JSON.stringify({
    pharmacistId: '',
    pharmacistName: 'Load Test',
    pharmacyId: target.pharmacyId,
    sessionId: `${runId}-${target.deviceId}-recommend-${__ITER}`,
    scannedBarcode: __ENV.TRIGGER_BARCODE || '4603423004936',
    cart: [
      {
        sku: __ENV.TRIGGER_SKU || null,
        barcode: __ENV.TRIGGER_BARCODE || '4603423004936',
        name: 'Load test product',
        qty: 1,
      },
    ],
  })
  const response = http.post(`${baseUrl}/api/posm/recommend`, payload, posmParams('recommendation', target))
  assertResponse(response, 200, 'recommendation')
}

function salePayload(target, eventId) {
  return JSON.stringify({
    saleId: `load-${runId}-${target.deviceId}-${eventId}`,
    pharmacistId: '',
    pharmacistName: 'Load Test',
    pharmacyId: target.pharmacyId,
    sessionId: `load-session-${target.deviceId}-${eventId}`,
    captureSource: 'load-test',
    cashier: 'Load Test',
    shift: 'load',
    totalAmount: 1000,
    items: [
      {
        barcode: __ENV.TRIGGER_BARCODE || '4603423004936',
        name: 'Load test product',
        qty: 1,
        price: 1000,
        total: 1000,
      },
    ],
    printedAt: new Date().toISOString(),
  })
}

export function sale() {
  const target = desk()
  const response = http.post(
    `${baseUrl}/api/posm/sales`,
    salePayload(target, `sale-${__ITER}-${Date.now()}`),
    saleParams('sales', target),
  )
  assertSaleResponse(response, true, 'sales')
}

export function offlineSync() {
  const target = desk()
  const payload = salePayload(target, `offline-${__ITER}`)
  const started = Date.now()
  const first = http.post(`${baseUrl}/api/posm/sales`, payload, saleParams('offline-sync', target))
  assertSaleResponse(first, true, 'offline-sync')
  // Replay the same durable-outbox event: the API must remain idempotent by saleId.
  const replay = http.post(`${baseUrl}/api/posm/sales`, payload, saleParams('offline-sync', target))
  assertSaleResponse(replay, false, 'offline-sync-replay')
  offlineReplayLatency.add(Date.now() - started)
  sleep(Math.random() * 0.2)
}

export function videoUpload() {
  const response = http.post(
    `${baseUrl}/api/admin/screens/slides`,
    {
      file: http.file(videoPayload, `load-${runId}-${__VU}-${__ITER}.mp4`, 'video/mp4'),
      title: `Load test ${runId}-${__VU}-${__ITER}`,
      durationSec: '15',
    },
    {
      headers: { Authorization: `Bearer ${adminToken}` },
      tags: { workload: 'video-upload' },
      timeout: '30s',
    },
  )
  assertResponse(response, 201, 'video-upload')
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data.metrics, null, 2),
    [`/results/summary-${runId}.json`]: JSON.stringify(data, null, 2),
  }
}
