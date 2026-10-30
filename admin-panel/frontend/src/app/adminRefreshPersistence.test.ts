import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthTokens, UserDto } from '@/lib/api-types'

const userA: UserDto = {
  id: 'refresh-demo-a',
  name: 'Демо A',
  email: 'a@example.test',
  role: 'HQ_HEAD',
  company: 'DEMO',
}
const userB: UserDto = { ...userA, id: 'refresh-demo-b', name: 'Демо B', email: 'b@example.test' }
const tokens = (id: string): AuthTokens => ({
  accessToken: `synthetic-${id}`,
  refreshToken: `refresh-${id}`,
  accessTokenExpiresAt: '2099-01-01T00:00:00Z',
  refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
})
const response = (config: InternalAxiosRequestConfig, data: unknown): AxiosResponse => ({
  data,
  config,
  status: 200,
  statusText: 'OK',
  headers: {},
})
const unauthorized = (config: InternalAxiosRequestConfig) =>
  new AxiosError('Synthetic unauthorized', undefined, config, undefined, {
    data: {},
    config,
    status: 401,
    statusText: 'Unauthorized',
    headers: {},
  })
const clients: Array<typeof import('./queryClient')> = []
async function modules() {
  const [store, transport, session, persistence] = await Promise.all([
    import('./store'),
    import('@/lib/api'),
    import('./queryClient'),
    import('@/lib/tokenStore'),
  ])
  clients.push(session)
  store.useUiStore.getState().init()
  return { ...store, ...transport, session, ...persistence }
}
beforeEach(() => {
  vi.resetModules()
  localStorage.clear()
})
afterEach(() => {
  clients.splice(0).forEach((client) => client.resetQuerySession(null))
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('Real same-session refresh persistence', () => {
  it('401 rotation updates store and persisted tokens synchronously, survives a real module reload and keeps its query session', async () => {
    const current = await modules()
    vi.spyOn(current.authApi, 'login').mockResolvedValue({ user: userA, tokens: tokens('a') })
    await current.useUiStore.getState().login(userA.email, 'synthetic-test-only')
    const client = current.session.queryClient
    const epoch = current.useUiStore.getState().querySessionEpoch
    client.setQueryData(['ordinary-query'], 'same-session-data')
    let calls = 0
    current.api.defaults.adapter = async (config) => {
      if (config.url === '/api/admin/auth/refresh')
        return response(config, { tokens: tokens('a-rotated') })
      if (++calls === 1) throw unauthorized(config)
      return response(config, { ok: true })
    }
    await current.api.get('/api/admin/synthetic-protected')
    expect(current.useUiStore.getState().tokens).toEqual(tokens('a-rotated'))
    expect(current.tokenStore.load()?.tokens).toEqual(tokens('a-rotated'))
    expect(current.session.queryClient).toBe(client)
    expect(current.useUiStore.getState().querySessionEpoch).toBe(epoch)
    expect(client.getQueryData(['ordinary-query'])).toBe('same-session-data')
    current.session.resetQuerySession(null)
    vi.resetModules()
    const reloaded = await modules()
    expect(reloaded.useUiStore.getState().authedUser?.id).toBe(userA.id)
    expect(reloaded.useUiStore.getState().tokens).toEqual(tokens('a-rotated'))
    expect(reloaded.getTokens()).toEqual(tokens('a-rotated'))
  })

  it('a late previous-account refresh cannot write the new account tokens into store or storage', async () => {
    const current = await modules()
    const login = vi
      .spyOn(current.authApi, 'login')
      .mockResolvedValue({ user: userA, tokens: tokens('a') })
    await current.useUiStore.getState().login(userA.email, 'synthetic-test-only')
    let resolve!: (value: AxiosResponse) => void
    let refreshConfig!: InternalAxiosRequestConfig
    const pending = new Promise<AxiosResponse>((yes) => {
      resolve = yes
    })
    current.api.defaults.adapter = async (config) => {
      if (config.url === '/api/admin/auth/refresh') {
        refreshConfig = config
        return pending
      }
      throw unauthorized(config)
    }
    const request = current.api.get('/api/admin/old-account').catch((error) => error)
    await vi.waitFor(() => expect(refreshConfig).toBeDefined())
    login.mockResolvedValueOnce({ user: userB, tokens: tokens('b') })
    await current.useUiStore.getState().login(userB.email, 'synthetic-test-only')
    resolve(response(refreshConfig, { tokens: tokens('a-late') }))
    expect(axios.isCancel(await request)).toBe(true)
    expect(current.useUiStore.getState().tokens).toEqual(tokens('b'))
    expect(current.tokenStore.load()).toMatchObject({ user: userB, tokens: tokens('b') })
    expect(localStorage.getItem('epharm.auth.tokens')).not.toContain('a-late')
  })
})
