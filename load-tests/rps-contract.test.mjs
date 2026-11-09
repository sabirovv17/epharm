import { test } from 'node:test'
import assert from 'node:assert/strict'
import { localOrigin, validateFixture, failureKind, sessionValid, recommendationValid, playlistValid, registrationVuBudget, publicLoadSummary } from './k6/rps-contract.mjs'

test('public summary never serializes setup grants, fixture credentials or unknown k6 fields', () => {
  const result = publicLoadSummary({ metrics: { checks: { values: { rate: 1 } } },
    state: { testRunDurationMs: 1000, secret: 'private' },
    setup_data: { sessionToken: 'private-grant', password: 'private-password' }, unknown: 'private-key',
  }, { mode: 'sessions' })
  assert.deepEqual(result, { mode: 'sessions', metrics: { checks: { values: { rate: 1 } } }, testRunDurationMs: 1000 })
  assert.equal(JSON.stringify(result).includes('private'), false)
})

test('only explicit local origins are accepted, never production or remote aliases', () => {
  for (const origin of ['http://127.0.0.1:18080', 'http://localhost:8080', 'http://[::1]:8080', 'http://host.docker.internal:18080']) assert.equal(localOrigin(origin), origin)
  for (const origin of ['', 'https://epharm.inkar.kz', 'http://10.10.1.76:8080', 'http://10.10.1.81:8080',
    'http://localhost.evil:8080', 'http://localhost:8080@epharm.inkar.kz', 'http://epharm.inkar.kz@localhost:8080',
    'http://localhost:0', 'http://localhost:65536', 'http://localhost:8080/path', 'http://localhost:8080?next=production',
    'http://127.1:8080', 'http://2130706433:8080', 'file:///tmp/test', 'https://LOCALHOST:8080']) assert.throws(() => localOrigin(origin))
})

function fixture() {
  return { kind: 'epharm-isolated-rps-load-v1', runId: 'test', expectedDatabase: 'epharm_rps_load_test', background: {
    ruleId: 'rps-load-test-rule', triggerSku: 'rps-load-test-trigger', triggerBarcode: '4870000000010',
    recommendSku: 'rps-load-test-recommend', recommendBarcode: '4870000000027', recommendPrice: 2000, bonus: 125,
    playlistId: 'rps-load-test-playlist', slideId: 'rps-load-test-slide', slideUrl: 'http://127.0.0.1:8080/synthetic/rps-load-test-slide.mp4',
  }, devices: [
    { pharmacyId: 'rps-load-test-001', deviceId: 'KASSA', key: 'x'.repeat(43), iin: '000101500011', phone: '+77000000001', name: 'RPS Synthetic 001', password: 'Synthetic123' },
    { pharmacyId: 'rps-load-test-002', deviceId: 'KASSA', key: 'y'.repeat(43), iin: '000101500020', phone: '+77000000002', name: 'RPS Synthetic 002', password: 'Synthetic234' },
  ] }
}
test('fixture preserves same device ID across pharmacies but rejects duplicate authenticated identities', () => {
  const value = fixture(); assert.equal(validateFixture(value), value)
  for (const field of ['pharmacyId', 'key', 'iin', 'phone']) {
    const bad = fixture(); bad.devices[1][field] = bad.devices[0][field]; assert.throws(() => validateFixture(bad))
  }
  const bad = fixture(); bad.expectedDatabase = 'epharm'; assert.throws(() => validateFixture(bad))
  assert.throws(() => validateFixture({ ...fixture(), devices: [] }))
  assert.throws(() => validateFixture({ ...fixture(), runId: '../../prod' }))
  assert.throws(() => validateFixture({ ...fixture(), background: undefined }))
})
test('429, transport, upstream and unexpected redirects remain distinct', () => {
  assert.equal(failureKind(429), 'rate_limited'); assert.equal(failureKind(503), 'upstream')
  assert.equal(failureKind(0), 'transport'); assert.equal(failureKind(400), 'client')
  assert.equal(failureKind(302), 'redirect'); assert.equal(failureKind(200), null)
})

test('minute arrivals have bounded generator headroom without exceeding fixture desks', () => {
  for (const preallocated of [10, 20, 25, 50]) assert.equal(registrationVuBudget(preallocated, 500), 50)
  assert.equal(registrationVuBudget(100, 500), 100)
  assert.equal(registrationVuBudget(500, 500), 500)
  assert.equal(registrationVuBudget(20, 25), 25)
  assert.equal(registrationVuBudget(1, 1), 1)
})
test('an HTTP 200 is insufficient without a real current grant and exact person/context', () => {
  const context = { processInstanceId: 'synthetic:123' }; const now = 1000
  const body = { status: 'authenticated', pharmacist: { id: 'test-person' }, deviceContext: context, sessionToken: 'x'.repeat(43), expiresAt: new Date(2000).toISOString() }
  assert.equal(sessionValid(body, context, null, true, now), true)
  for (const bad of [null, {}, { ...body, status: 'pending' }, { ...body, sessionToken: null }, { ...body, expiresAt: 'bad-date' },
    { ...body, deviceContext: { processInstanceId: 'old-process' } }, { ...body, expiresAt: new Date(900).toISOString() }]) assert.equal(sessionValid(bad, context, null, true, now), false)
  assert.equal(sessionValid(body, context, 'wrong-person', true, now), false)
  assert.equal(sessionValid({ ...body, sessionToken: null }, context, 'test-person', false, now), true)
})

test('background must return the seeded recommendation with real event and expected amounts', () => {
  const expected = fixture().background
  const offer = { eventId: 'rec_synthetic', ruleId: expected.ruleId, kind: 'substitution', triggerSku: expected.triggerSku,
    triggerBarcode: expected.triggerBarcode, recommendSku: expected.recommendSku, recommendPrice: 2000, bonus: 125, source: 'epharm' }
  const good = { sessionId: 'test', recommendations: [offer], conflicts: [] }
  assert.equal(recommendationValid(good, 'test', expected), true)
  for (const bad of [null, {}, { ...good, recommendations: [] }, { ...good, recommendations: [null] },
    { ...good, recommendations: [{ ...offer, ruleId: 'wrong-rule' }] }, { ...good, recommendations: [{ ...offer, recommendPrice: 0 }] },
    { ...good, recommendations: [{ ...offer, eventId: '' }] }, { ...good, conflicts: [{}] }]) assert.equal(recommendationValid(bad, 'test', expected), false)
})

test('playlist must contain the exact seeded video rather than a successful empty response', () => {
  const expected = fixture().background
  const slide = { id: expected.slideId, url: expected.slideUrl, kind: 'video', position: 0, durationSec: 15 }
  const good = { playlistId: expected.playlistId, slides: [slide] }
  assert.equal(playlistValid(good, expected), true)
  for (const bad of [null, {}, { ...good, slides: [] }, { ...good, slides: [null] },
    { ...good, slides: [{ ...slide, id: 'old-slide' }] }, { ...good, slides: [{ ...slide, url: 'https://wrong-origin/video.mp4' }] }]) assert.equal(playlistValid(bad, expected), false)
})
