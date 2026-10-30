import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STAFF_SESSION_KEY, staffClient, storedStaffToken } from './staff-access-api'
const token = 'synthetic_limited_staff_session_000000000000000000'
const scope = {
  sessionId: 'session',
  codeId: 'code',
  organizationId: 'org',
  placementMonth: '2099-01-01',
  branchIds: ['branch'],
  deadlineAt: null,
  sessionExpiresAt: '2099-02-01T00:00:00Z',
  readOnly: false,
}
const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
})
afterEach(() => vi.unstubAllGlobals())
it('persists only the issued opaque staff session and restores it without a second one-time code', async () => {
  sessionStorage.setItem('epharm.learner.tokens', JSON.stringify({ accessToken: 'learner-secret' }))
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(reply({ token, expiresAt: scope.sessionExpiresAt, scope }))
    .mockResolvedValueOnce(reply(scope))
  vi.stubGlobal('fetch', fetcher)
  const client = staffClient(vi.fn())
  await client.code('123456')
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).toBe(token)
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).not.toContain('123456')
  const restored = staffClient(vi.fn())
  expect(restored.hasSession()).toBe(true)
  await restored.me(new AbortController().signal)
  expect(fetcher.mock.calls[1][1]).toEqual(
    expect.objectContaining({
      credentials: 'omit',
      cache: 'no-store',
      headers: { 'X-Staff-Session': token },
    }),
  )
  expect(JSON.stringify(fetcher.mock.calls[1])).not.toContain('learner-secret')
  expect(sessionStorage.getItem('epharm.learner.tokens')).toContain('learner-secret')
})
it('revoke denial clears only this realm and never reflects a response secret', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  sessionStorage.setItem('unrelated', 'keep')
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(reply({ message: 'raw-private-token', code: 'UNAUTHORIZED' }, 401)),
  )
  const closed = vi.fn()
  const client = staffClient(closed)
  await expect(client.me(new AbortController().signal)).rejects.toThrow('Доступ завершён')
  expect(storedStaffToken()).toBeNull()
  expect(closed).toHaveBeenCalledOnce()
  expect(sessionStorage.getItem('unrelated')).toBe('keep')
})
it('auth/me forbidden still closes the session rather than retaining an old workspace', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({}, 403)))
  const closed = vi.fn()
  const client = staffClient(closed)
  await expect(client.me(new AbortController().signal)).rejects.toThrow()
  expect(storedStaffToken()).toBeNull()
  expect(closed).toHaveBeenCalledOnce()
})
it('an invalid empty scope closes the session rather than retaining an earlier workspace', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply({ ...scope, branchIds: [] })))
  const closed = vi.fn()
  const client = staffClient(closed)
  await expect(client.me(new AbortController().signal)).rejects.toThrow()
  expect(closed).toHaveBeenCalledOnce()
  expect(storedStaffToken()).toBeNull()
})
it('a mutation denied at the deadline refreshes readonly scope without consuming the valid limited session', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(reply({}, 403))
    .mockResolvedValueOnce(reply({ ...scope, readOnly: true }))
  vi.stubGlobal('fetch', fetcher)
  const closed = vi.fn()
  const refreshed = vi.fn()
  const client = staffClient(closed, refreshed)
  await expect(client.submit('visit', 1, 'Synthetic author', '')).rejects.toThrow()
  expect(storedStaffToken()).toBe(token)
  expect(closed).not.toHaveBeenCalled()
  expect(refreshed).toHaveBeenCalledExactlyOnceWith({ ...scope, readOnly: true })
  expect(fetcher.mock.calls[1][0]).toBe('/api/staff/auth/me')
})
it('a denied foreign branch does not erase a session that still permits its original branches or another realm', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  sessionStorage.setItem('unrelated', 'keep')
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(reply({}, 403)).mockResolvedValueOnce(reply(scope)),
  )
  const closed = vi.fn()
  const refreshed = vi.fn()
  const client = staffClient(closed, refreshed)
  await expect(client.open('foreign', scope.placementMonth, 'Synthetic author')).rejects.toThrow()
  expect(storedStaffToken()).toBe(token)
  expect(sessionStorage.getItem('unrelated')).toBe('keep')
  expect(closed).not.toHaveBeenCalled()
  expect(refreshed).toHaveBeenCalledExactlyOnceWith(scope)
})
it('a late code response after leaving the form cannot persist or reopen a session', async () => {
  let resolve!: (value: Response) => void
  vi.stubGlobal(
    'fetch',
    vi.fn(
      () =>
        new Promise<Response>((done) => {
          resolve = done
        }),
    ),
  )
  const controller = new AbortController()
  const client = staffClient(vi.fn())
  const pending = client.code('123456', controller.signal)
  controller.abort()
  resolve(reply({ token, expiresAt: scope.sessionExpiresAt, scope }))
  await expect(pending).rejects.toThrow()
  expect(storedStaffToken()).toBeNull()
})
it('logout204 and a late workspace result cannot retain authority', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  let resolve!: (value: Response) => void
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          resolve = done
        }),
    )
    .mockResolvedValueOnce(reply(null, 204))
  vi.stubGlobal('fetch', fetcher)
  const client = staffClient(vi.fn())
  const pending = client.workspace('2099-01-01', 0, new AbortController().signal)
  await client.logout()
  resolve(reply({ branches: [{ name: 'private branch' }] }))
  await expect(pending).rejects.toThrow()
  expect(storedStaffToken()).toBeNull()
})
