import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, authApi, getTokens, onForcedLogout, setTokens } from './api'
import type { AuthTokens } from './api-types'

const tokens = (id: string): AuthTokens => ({
  accessToken: `synthetic-${id}`,
  refreshToken: `refresh-${id}`,
  accessTokenExpiresAt: '2099-01-01T00:00:00Z',
  refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
})
const originalAdapter = api.defaults.adapter
const originalBareAdapter = axios.defaults.adapter
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
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
beforeEach(() => setTokens(tokens('a')))
afterEach(() => {
  api.defaults.adapter = originalAdapter
  axios.defaults.adapter = originalBareAdapter
  setTokens(null)
  vi.restoreAllMocks()
})

describe('Real axios administrator session epochs', () => {
  it.each([503, 429, 'timeout'] as const)(
    'keeps the session after a temporary refresh %s and can recover on the next request',
    async (failure) => {
      const forced = vi.fn()
      const unsubscribe = onForcedLogout(forced)
      let refreshCalls = 0
      let temporaryError!: AxiosError
      api.defaults.adapter = async (config) => {
        if (config.url === '/api/admin/auth/refresh') {
          refreshCalls++
          if (refreshCalls === 1) {
            temporaryError = new AxiosError(
              'Refresh temporarily unavailable',
              failure === 'timeout' ? 'ECONNABORTED' : undefined,
              config,
              undefined,
              failure === 'timeout'
                ? undefined
                : { ...response(config, {}), status: failure },
            )
            throw temporaryError
          }
          return response(config, { tokens: tokens('a-renewed') })
        }
        if (config.headers.get('Authorization') === 'Bearer synthetic-a')
          throw unauthorized(config)
        return response(config, { ok: true })
      }
      try {
        const error = await api.get('/api/admin/synthetic-protected').catch((error) => error)
        expect(error).toBe(temporaryError)
        expect(getTokens()).toEqual(tokens('a'))
        expect(forced).not.toHaveBeenCalled()
        expect((await api.get('/api/admin/synthetic-protected')).data).toEqual({ ok: true })
        expect(refreshCalls).toBe(2)
      } finally {
        unsubscribe()
      }
    },
  )

  it.each([401, 403])('expires the session when refresh is denied with %i', async (status) => {
    const forced = vi.fn()
    const unsubscribe = onForcedLogout(forced)
    api.defaults.adapter = async (config) => {
      if (config.url === '/api/admin/auth/refresh')
        throw new AxiosError('Refresh denied', undefined, config, undefined, {
          ...response(config, {}),
          status,
        })
      throw unauthorized(config)
    }
    try {
      await expect(api.get('/api/admin/synthetic-protected')).rejects.toThrow()
      expect(getTokens()).toBeNull()
      expect(forced).toHaveBeenCalledOnce()
    } finally {
      unsubscribe()
    }
  })

  it('renews and retries an ordinary 401 under the same principal without cancelling it', async () => {
    const calls: { url?: string; authorization: unknown }[] = []
    let protectedCalls = 0
    api.defaults.adapter = async (config) => {
      calls.push({ url: config.url, authorization: config.headers.get('Authorization') })
      if (config.url === '/api/admin/auth/refresh')
        return response(config, { tokens: tokens('a-renewed') })
      if (++protectedCalls === 1) throw unauthorized(config)
      return response(config, { ok: true })
    }
    expect((await api.get('/api/admin/synthetic-protected')).data).toEqual({ ok: true })
    expect(calls).toEqual([
      { url: '/api/admin/synthetic-protected', authorization: 'Bearer synthetic-a' },
      { url: '/api/admin/auth/refresh', authorization: 'Bearer synthetic-a' },
      { url: '/api/admin/synthetic-protected', authorization: 'Bearer synthetic-a-renewed' },
    ])
    expect(getTokens()).toEqual(tokens('a-renewed'))
  })

  it.each([200, 401])(
    'drops a late old-account HTTP %i without retrying it under the new token',
    async (status) => {
      const pending = deferred<AxiosResponse>()
      let config!: InternalAxiosRequestConfig
      const adapter = vi.fn(async (request) => {
        config = request
        return pending.promise
      })
      api.defaults.adapter = adapter
      const result = api.get('/api/admin/same-object').catch((error) => error)
      await vi.waitFor(() => expect(adapter).toHaveBeenCalledOnce())
      setTokens(tokens('b'))
      if (status === 200) pending.resolve(response(config, { private: 'OLD-A' }))
      else pending.reject(unauthorized(config))
      expect(axios.isCancel(await result)).toBe(true)
      expect(adapter).toHaveBeenCalledOnce()
      expect(getTokens()).toEqual(tokens('b'))
    },
  )

  it('captures the original request principal synchronously before an immediate account switch', async () => {
    const pending = deferred<AxiosResponse>()
    let captured!: InternalAxiosRequestConfig
    api.defaults.adapter = (config) => {
      captured = config
      return pending.promise
    }
    const result = api.get('/api/admin/queued-old-request').catch((error) => error)
    setTokens(tokens('b'))
    expect(captured.headers.get('Authorization')).toBe('Bearer synthetic-a')
    pending.resolve(response(captured, { private: 'OLD-A' }))
    expect(axios.isCancel(await result)).toBe(true)
    expect(getTokens()).toEqual(tokens('b'))
  })

  it.each(['success', 'failure'])(
    'an old refresh %s cannot replace a new login or force it out',
    async (outcome) => {
      const pending = deferred<AxiosResponse>()
      let refreshConfig!: InternalAxiosRequestConfig
      const forced = vi.fn()
      const unsubscribe = onForcedLogout(forced)
      const calls: string[] = []
      api.defaults.adapter = async (config) => {
        calls.push(config.url!)
        if (config.url === '/api/admin/auth/refresh') {
          refreshConfig = config
          return pending.promise
        }
        throw unauthorized(config)
      }
      const result = api.get('/api/admin/old-account').catch((error) => error)
      await vi.waitFor(() => expect(calls).toHaveLength(2))
      setTokens(tokens('b'))
      if (outcome === 'success')
        pending.resolve(response(refreshConfig, { tokens: tokens('a-late') }))
      else pending.reject(unauthorized(refreshConfig))
      expect(axios.isCancel(await result)).toBe(true)
      expect(getTokens()).toEqual(tokens('b'))
      expect(calls).toHaveLength(2)
      expect(forced).not.toHaveBeenCalled()
      unsubscribe()
    },
  )

  it('best-effort server logout captures only its original bearer and cannot affect the next login', async () => {
    let sentAuthorization: unknown
    axios.defaults.adapter = async (config) => {
      sentAuthorization = config.headers.get('Authorization')
      return response(config, {})
    }
    const logout = authApi.logout()
    setTokens(null)
    setTokens(tokens('b'))
    await logout
    expect(sentAuthorization).toBe('Bearer synthetic-a')
    expect(getTokens()).toEqual(tokens('b'))
  })
})
