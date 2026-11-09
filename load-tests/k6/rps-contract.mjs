// Shared by k6 and the dependency-free Node contract tests.
export function localOrigin(raw) {
  const match = /^(https?):\/\/(localhost|127\.0\.0\.1|\[::1\]|host\.docker\.internal)(?::([0-9]{1,5}))?$/.exec(raw || '')
  if (!match || (match[3] && (+match[3] < 1 || +match[3] > 65535))) {
    throw new Error('RPS load requires an explicit loopback/Docker-host origin; remote hosts, redirects and production overrides are forbidden')
  }
  return raw
}

export function validateFixture(value) {
  if (value?.kind !== 'epharm-isolated-rps-load-v1' || !/^[a-z0-9_]{1,32}$/.test(value.runId || '') ||
      value.expectedDatabase !== `epharm_rps_load_${value.runId}` || !Array.isArray(value.devices) ||
      value.devices.length < 1 || value.devices.length > 500) throw new Error('Invalid isolated RPS fixture contract')
  const distinct = (pick) => new Set(value.devices.map(pick)).size === value.devices.length
  if (!distinct((x) => `${x.pharmacyId}\n${x.deviceId}`) || !distinct((x) => x.key) ||
      !distinct((x) => x.iin) || !distinct((x) => x.phone)) throw new Error('Duplicate fixture identity/credential/profile')
  for (const x of value.devices) {
    if (!/^rps-load-[a-z0-9_-]+$/.test(x.pharmacyId || '') || x.pharmacyId.length > 64 ||
        !/^[A-Za-z0-9_-]{1,128}$/.test(x.deviceId || '') || !/^[A-Za-z0-9_-]{32,128}$/.test(x.key || '') ||
        !/^[0-9]{12}$/.test(x.iin || '') || !/^\+7700[0-9]{7}$/.test(x.phone || '') ||
        !/^RPS Synthetic [0-9]+$/.test(x.name || '') || !/^[A-Za-z0-9_-]{4,64}$/.test(x.password || '')) {
      throw new Error('Invalid synthetic device/profile fixture')
    }
  }
  positiveBackground(value)
  return value
}

export function positiveBackground(value) {
  if (!/^[a-z0-9_]{1,32}$/.test(value?.runId || '')) throw new Error('Invalid positive-background fixture ID')
  const background = value.background
  const prefix = `rps-load-${value.runId}`
  if (!background || background.ruleId !== `${prefix}-rule` || background.triggerSku !== `${prefix}-trigger` ||
      background.recommendSku !== `${prefix}-recommend` || background.playlistId !== `${prefix}-playlist` ||
      background.slideId !== `${prefix}-slide` || background.slideUrl !== `http://127.0.0.1:8080/synthetic/${prefix}-slide.mp4` ||
      !/^[0-9]{13}$/.test(background.triggerBarcode || '') || !/^[0-9]{13}$/.test(background.recommendBarcode || '') ||
      background.triggerBarcode === background.recommendBarcode || background.recommendPrice !== 2000 || background.bonus !== 125) {
    throw new Error('Positive recommendation/playlist fixture is required')
  }
  return background
}

export function recommendationValid(value, sessionId, expected) {
  const recommendations = value?.recommendations
  const offer = recommendations?.[0]
  return value?.sessionId === sessionId && Array.isArray(value.conflicts) && value.conflicts.length === 0 &&
    Array.isArray(recommendations) && recommendations.length === 1 && typeof offer?.eventId === 'string' && offer.eventId.startsWith('rec_') &&
    offer.ruleId === expected.ruleId && offer.kind === 'substitution' && offer.triggerSku === expected.triggerSku &&
    offer.triggerBarcode === expected.triggerBarcode && offer.recommendSku === expected.recommendSku &&
    offer.recommendPrice === expected.recommendPrice && offer.bonus === expected.bonus && offer.source === 'epharm'
}

export function playlistValid(value, expected) {
  const slide = value?.slides?.[0]
  return value?.playlistId === expected.playlistId && Array.isArray(value.slides) && value.slides.length === 1 &&
    slide?.id === expected.slideId && slide.url === expected.slideUrl && slide.kind === 'video' &&
    slide.position === 0 && slide.durationSec === 15
}

export function failureKind(status) {
  if (status === 0) return 'transport'
  if (status === 429) return 'rate_limited'
  if (status >= 500) return 'upstream'
  if (status >= 400) return 'client'
  if (status >= 300) return 'redirect'
  return null
}

export function registrationVuBudget(preallocated, desks) {
  return Math.min(desks, Math.max(preallocated, 50))
}

// k6 includes setup_data (process-bound bearer grants) in its summary input.
// Persist only public aggregate metrics; never spread the original data object.
export function publicLoadSummary(data, context) {
  return { ...context, metrics: data.metrics, testRunDurationMs: data.state?.testRunDurationMs }
}

export function sessionValid(body, context, personId = null, requireToken = true, now = Date.now()) {
  return body?.status === 'authenticated' && typeof body.pharmacist?.id === 'string' && body.pharmacist.id.length > 0 &&
    (!personId || body.pharmacist.id === personId) && body.deviceContext?.processInstanceId === context.processInstanceId &&
    body.deviceContext?.standardNContext == null && Number.isFinite(Date.parse(body.expiresAt)) && Date.parse(body.expiresAt) > now &&
    (!requireToken || (typeof body.sessionToken === 'string' && /^[A-Za-z0-9_-]{32,128}$/.test(body.sessionToken)))
}
