import { describe, expect, it, vi } from 'vitest'
import {
  ProducerClient,
  producerError,
  ProducerApiError,
  type ProducerSession,
} from './producer-client'

const session = (id = 'producer-a', token = 'a'): ProducerSession => ({
  accessToken: token,
  refreshToken: `refresh-${token}`,
  tokenType: 'Bearer',
  expiresIn: 900,
  sessionExpiresAt: '2099-01-01T00:00:00Z',
  customer: { id, name: 'Демо', phoneMasked: '+7 *** 0000' },
})
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function setup() {
  const values = new Map<string, string>()
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
    removeItem: (key: string) => {
      values.delete(key)
    },
  }
  const transport = vi.fn<typeof fetch>()
  const client = new ProducerClient(transport, storage)
  return { client, transport, storage, values }
}
describe('dedicated producer realm', () => {
  it('stores only its own namespace and never sends admin/mobile credentials or cookies', async () => {
    const { client, transport, values } = setup()
    values.set('epharm.auth.tokens', 'unchanged-admin')
    transport.mockResolvedValueOnce(json(session())).mockResolvedValueOnce(json([]))
    await client.verify('challenge', '1234')
    await client.request('/api/producer/placements')
    expect([...values.keys()].sort()).toEqual(['epharm.auth.tokens', 'epharm.producer.session.v1'])
    const init = transport.mock.calls[1][1]!
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer a')
    expect(init.credentials).toBe('omit')
    await expect(client.request('/api/admin/merch/placements')).rejects.toThrow(
      'Invalid producer API path',
    )
    expect(transport).toHaveBeenCalledTimes(2)
  })
  it('rotates once for parallel 401s and persists new tokens without changing the principal epoch', async () => {
    const { client, transport, storage } = setup()
    const refresh = deferred<Response>()
    transport.mockImplementation(async (path, init) => {
      if (String(path).endsWith('/sms/verify')) return json(session())
      if (String(path).endsWith('/refresh')) return refresh.promise
      return new Headers(init?.headers).get('Authorization') === 'Bearer rotated'
        ? json({ ok: true })
        : json({}, 401)
    })
    await client.verify('challenge', '1234')
    const epoch = client.snapshot().epoch
    const first = client.request('/api/producer/placements')
    const second = client.request('/api/producer/auth/me')
    await vi.waitFor(() =>
      expect(
        transport.mock.calls.filter(([path]) => String(path).endsWith('/refresh')),
      ).toHaveLength(1),
    )
    refresh.resolve(json(session('producer-a', 'rotated')))
    await expect(first).resolves.toEqual({ ok: true })
    await expect(second).resolves.toEqual({ ok: true })
    expect(client.snapshot().epoch).toBe(epoch)
    expect(new ProducerClient(transport, storage).snapshot().session?.refreshToken).toBe(
      'refresh-rotated',
    )
  })
  it('drops late refresh after logout/new login; local logout happens before network completion', async () => {
    const { client, transport } = setup()
    const refresh = deferred<Response>()
    let verifyCount = 0
    transport.mockImplementation(async (path) => {
      if (String(path).endsWith('/sms/verify'))
        return json(verifyCount++ ? session('producer-b', 'b') : session())
      if (String(path).endsWith('/refresh')) return refresh.promise
      if (String(path).endsWith('/logout')) return new Promise(() => {})
      return json({}, 401)
    })
    await client.verify('challenge-a', '1234')
    const pending = client.request('/api/producer/placements').catch((error) => error)
    await vi.waitFor(() =>
      expect(transport.mock.calls.some(([path]) => String(path).endsWith('/refresh'))).toBe(true),
    )
    void client.logout()
    expect(client.snapshot().session).toBeNull()
    await client.verify('challenge-b', '1234')
    refresh.resolve(json(session('producer-a', 'rotated-a')))
    expect((await pending).name).toBe('AbortError')
    expect(client.snapshot().session?.customer.id).toBe('producer-b')
  })
  it('surfaces disabled auth and does not create a fake session', async () => {
    const { client, transport } = setup()
    transport.mockResolvedValue(json({}, 503))
    await expect(client.requestCode('+70000000000')).rejects.toBeInstanceOf(ProducerApiError)
    expect(producerError(new ProducerApiError(503, 'disabled'))).toContain('временно недоступен')
    expect(client.snapshot().session).toBeNull()
  })
  it('rejects an old successful response after a different producer signs in', async () => {
    const { client, transport } = setup()
    const old = deferred<Response>()
    transport
      .mockResolvedValueOnce(json(session()))
      .mockImplementationOnce(() => old.promise)
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json(session('producer-b', 'b')))
    await client.verify('challenge-a', '1234')
    const pending = client.request('/api/producer/placements').catch((error) => error)
    await client.logout()
    await client.verify('challenge-b', '1234')
    old.resolve(json({ privateOldProducerData: 'synthetic-only' }))
    expect((await pending).name).toBe('AbortError')
    expect(client.snapshot().session?.customer.id).toBe('producer-b')
  })
})
