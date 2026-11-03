import * as Sentry from '@sentry/react'
import { createElement } from 'react'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from '@/app/ErrorBoundary'
import { privateMonitoringOptions } from './monitoring'

const PASSWORD = 'synthetic-fixture-password'
const IIN = '000000000001'
const PHONE = '+70000000001'
const GRANT = 'private-synthetic-reset-grant'
const AUTH = 'private-synthetic-learner-bearer'
const DEBUG_ID = '6e95fdce-001b-4f16-a69b-4df5fcd7b104'
const request = vi.fn<typeof fetch>()
const asset = () => `${window.location.origin}/assets/PromoPage-AbCd1234.js`

function dirtyEvent(): Sentry.Event {
  return {
    event_id: '1234567890abcdef1234567890abcdef',
    message: `${PASSWORD} ${IIN} ${PHONE} ${GRANT}`,
    release: PASSWORD,
    environment: PHONE,
    transaction: `/learn/${IIN}`,
    request: {
      url: `${window.location.origin}/learn?iin=${IIN}&password=${PASSWORD}#${GRANT}`,
      headers: { Authorization: AUTH },
      data: { password: PASSWORD },
    },
    user: {
      id: IIN,
      email: 'private@example.invalid',
      username: PASSWORD,
      ip_address: '192.0.2.11',
    },
    contexts: { learner: { password: PASSWORD, phone: PHONE }, react: { componentStack: GRANT } },
    tags: { iin: IIN },
    extra: { phone: PHONE, password: PASSWORD },
    fingerprint: [GRANT],
    breadcrumbs: [{ message: PHONE, data: { token: AUTH, password: PASSWORD } }],
    exception: {
      values: [
        {
          type: 'TypeError',
          value: `${PASSWORD} ${IIN}`,
          stacktrace: {
            frames: [
              {
                filename: `${asset()}?phone=${encodeURIComponent(PHONE)}#${GRANT}`,
                abs_path: `${asset()}?password=${PASSWORD}`,
                lineno: 37,
                colno: 14,
                function: PASSWORD,
                debug_id: DEBUG_ID,
                vars: { password: PASSWORD, iin: IIN },
                context_line: PHONE,
                pre_context: [GRANT],
                post_context: [AUTH],
              },
            ],
          },
        },
      ],
    },
    debug_meta: {
      images: [
        { type: 'sourcemap', debug_id: DEBUG_ID, code_file: `${asset()}?iin=${IIN}#${GRANT}` },
      ],
    },
  }
}

function wireEvents() {
  return request.mock.calls.flatMap(([, init]) => {
    expect(init?.credentials).toBe('omit')
    expect(init?.referrerPolicy).toBe('no-referrer')
    expect(init?.headers).toBeUndefined()
    const body =
      typeof init?.body === 'string'
        ? init.body
        : new TextDecoder().decode(init?.body as Uint8Array)
    for (const secret of [
      PASSWORD,
      IIN,
      PHONE,
      GRANT,
      AUTH,
      'private@example.invalid',
      '192.0.2.11',
    ])
      expect(body).not.toContain(secret)
    const lines = body.split('\n').map((line) => JSON.parse(line))
    expect(lines[0]).not.toHaveProperty('trace')
    expect(lines).toHaveLength(3)
    expect(lines[1]).toEqual({ type: 'event' })
    return [lines[2] as Sentry.Event]
  })
}

beforeEach(() => {
  request.mockReset().mockResolvedValue(new Response('', { status: 200 }))
  vi.stubGlobal('fetch', request)
  Sentry.getCurrentScope().clear()
  Sentry.getIsolationScope().clear()
  Sentry.init(
    privateMonitoringOptions({
      dsn: 'https://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@sentry.example.invalid/1',
      releaseId: 'v0.2.5',
      environment: 'production',
    }),
  )
})

afterEach(async () => {
  await Sentry.close(1000)
  Sentry.getCurrentScope().clear()
  Sentry.getIsolationScope().clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('private Sentry wire envelopes', () => {
  it('keeps source-map positions and debug IDs while removing identity, credentials and query data from the actual serialized request', async () => {
    Sentry.withScope((scope) => {
      scope.addAttachment({ filename: `${PASSWORD}.txt`, data: `${IIN} ${PHONE} ${GRANT}` })
      Sentry.captureEvent(dirtyEvent())
    })
    expect(await Sentry.flush(1000)).toBe(true)
    expect(request).toHaveBeenCalledOnce()
    const [event] = wireEvents()
    expect(event.release).toBe('epharm-admin@v0.2.5')
    expect(event.environment).toBe('production')
    expect(event.exception?.values?.[0].type).toBe('TypeError')
    expect(event.exception?.values?.[0].stacktrace?.frames).toEqual([
      { filename: asset(), abs_path: asset(), lineno: 37, colno: 14, in_app: true },
    ])
    expect(event.debug_meta?.images).toEqual([
      { type: 'sourcemap', code_file: asset(), debug_id: DEBUG_ID },
    ])
    for (const field of [
      'message',
      'user',
      'request',
      'extra',
      'tags',
      'contexts',
      'breadcrumbs',
      'fingerprint',
      'transaction',
    ])
      expect(event).not.toHaveProperty(field)
  })

  it('filters every envelope item and header, including SDK paths that bypass beforeSend', async () => {
    await Sentry.getClient()!.sendEnvelope([
      { trace: { user_id: IIN, release: PASSWORD }, event_id: GRANT },
      [
        [{ type: 'session' }, { did: IIN }],
        [{ type: 'transaction' }, { transaction: PHONE }],
        [{ type: 'attachment', filename: PASSWORD }, new TextEncoder().encode(AUTH)],
        [{ type: 'log' }, { body: PASSWORD }],
        [{ type: 'event' }, dirtyEvent()],
      ],
    ])
    expect(await Sentry.flush(1000)).toBe(true)
    expect(wireEvents()).toHaveLength(1)
  })

  it('sends no sessions, messages, replay, metrics or transaction payloads', async () => {
    await Sentry.getClient()!.sendEnvelope([
      {},
      [
        [{ type: 'session' }, { did: IIN }],
        [{ type: 'transaction' }, { transaction: PHONE }],
        [{ type: 'replay_event' }, { message: PASSWORD }],
        [{ type: 'metric_buckets' }, { value: AUTH }],
        [{ type: 'event' }, { message: PASSWORD }],
      ],
    ])
    expect(await Sentry.flush(1000)).toBe(true)
    expect(request).not.toHaveBeenCalled()
    expect(Sentry.getClient()!.getIntegrationNames()).not.toContain('BrowserSession')
    expect(Sentry.getClient()!.getIntegrationNames()).not.toContain('BrowserTracing')
  })

  it('reports a real React render failure without leaking the error message or pharmacist session', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    Sentry.setUser({ id: IIN, username: PASSWORD })
    Sentry.setContext('learn', { token: AUTH, phone: PHONE })
    const error = new TypeError(`${PASSWORD} ${IIN} ${PHONE}`)
    error.stack = `TypeError: ${error.message}\n    at submit (${asset()}?iin=${IIN}#${GRANT}:45:9)`
    function Crashes() {
      throw error
    }
    render(createElement(ErrorBoundary, { children: createElement(Crashes) }))
    expect(screen.getByText('Что-то пошло не так')).toBeInTheDocument()
    expect(await Sentry.flush(1000)).toBe(true)
    const [event] = wireEvents()
    expect(event.exception?.values?.[0].stacktrace?.frames?.[0]).toMatchObject({
      filename: asset(),
      lineno: 45,
      colno: 9,
    })
  })

  it('drops URLs outside compiled assets and sensitive or malformed frame fields', async () => {
    const event = dirtyEvent()
    event.exception!.values![0].type = PASSWORD
    event.exception!.values![0].stacktrace!.frames!.push(
      { filename: `${window.location.origin}/learn/${IIN}?password=${PASSWORD}`, lineno: 1 },
      { filename: 'https://external.example.invalid/assets/PromoPage-AbCd1234.js', lineno: 2 },
      { filename: `${window.location.origin}/assets/${IIN}-AbCd1234.js`, lineno: 3 },
      { filename: asset(), lineno: Number.NaN, colno: IIN as unknown as number },
    )
    Sentry.captureEvent(event)
    expect(await Sentry.flush(1000)).toBe(true)
    const [safe] = wireEvents()
    expect(safe.exception?.values?.[0].type).toBe('Error')
    expect(safe.exception?.values?.[0].stacktrace?.frames).toHaveLength(2)
    expect(safe.exception?.values?.[0].stacktrace?.frames?.[1]).toEqual({
      filename: asset(),
      abs_path: asset(),
      in_app: true,
    })
  })

  it('fails closed on invalid event identity or malformed diagnostic payloads without reporting a new SDK error', async () => {
    for (const event of [
      { ...dirtyEvent(), event_id: IIN },
      { ...dirtyEvent(), debug_meta: { images: PASSWORD } },
    ]) {
      await Sentry.getClient()!.sendEnvelope([{}, [[{ type: 'event' }, event as Sentry.Event]]])
    }
    expect(await Sentry.flush(1000)).toBe(true)
    expect(request).not.toHaveBeenCalled()
  })
})
