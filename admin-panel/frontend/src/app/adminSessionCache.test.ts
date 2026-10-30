import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, authApi } from '@/lib/api'
import type { LoginResponse, UserDto } from '@/lib/api-types'
import * as session from './queryClient'
import { useUiStore } from './store'
import { QueryObserver } from '@tanstack/react-query'

const userA: UserDto = {
  id: 'admin-demo-a',
  name: 'Демо A',
  email: 'a@example.test',
  role: 'HQ_HEAD',
  company: 'DEMO',
}
const userB: UserDto = { ...userA, id: 'admin-demo-b', name: 'Демо B', email: 'b@example.test' }
const response = (user: UserDto): LoginResponse => ({
  user,
  tokens: {
    accessToken: `synthetic-${user.id}`,
    refreshToken: `refresh-${user.id}`,
    accessTokenExpiresAt: '2099-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2099-01-02T00:00:00Z',
  },
})
const initialAdapter = api.defaults.adapter
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function denied(status: number, config = {} as InternalAxiosRequestConfig) {
  return new AxiosError('Synthetic HTTP failure', undefined, config, undefined, {
    status,
    statusText: '',
    headers: {},
    config,
    data: { code: 'FORBIDDEN' },
  })
}
async function login(user = userA) {
  vi.spyOn(authApi, 'login').mockResolvedValue(response(user))
  return useUiStore.getState().login(user.email, 'synthetic-test-only')
}
beforeEach(async () => {
  vi.spyOn(authApi, 'logout').mockResolvedValue(undefined)
  await useUiStore.getState().logout()
  localStorage.clear()
})
afterEach(() => {
  api.defaults.adapter = initialAdapter
  vi.restoreAllMocks()
})

describe('Real QueryClient, persister and admin store boundary', () => {
  it('clears memory and local state before waiting for server logout, preserving learner tokens', async () => {
    await login()
    const previous = session.queryClient
    previous.setQueryData(['promo-changes', 'same-id'], { private: 'PENDING-A', canApply: true })
    previous.setQueryData(['promo', 'list'], 'ordinary-A')
    localStorage.setItem('epharm.learner.tokens', 'separate-learner-session')
    sessionStorage.setItem('epharm.commercial.draft.v1.admin.a.snapshot', 'PRIVATE-ADMIN-DRAFT')
    sessionStorage.setItem(
      'epharm.commercial.draft.v1.producer.b.snapshot',
      'separate-producer-draft',
    )
    sessionStorage.setItem('learner-unrelated', 'separate-learner-data')
    const revoke = deferred<void>()
    vi.mocked(authApi.logout).mockReturnValueOnce(revoke.promise)
    const pendingLogout = useUiStore.getState().logout()
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(previous.getQueryCache().getAll()).toHaveLength(0)
    expect(session.queryClient).not.toBe(previous)
    expect(session.queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(session.queryClient.getMutationCache().getAll()).toHaveLength(0)
    expect(localStorage.getItem('epharm.query.cache')).toBeNull()
    expect(localStorage.getItem('epharm.learner.tokens')).toBe('separate-learner-session')
    expect(sessionStorage.getItem('epharm.commercial.draft.v1.admin.a.snapshot')).toBeNull()
    expect(sessionStorage.getItem('epharm.commercial.draft.v1.producer.b.snapshot')).toBe(
      'separate-producer-draft',
    )
    expect(sessionStorage.getItem('learner-unrelated')).toBe('separate-learner-data')
    await login(userB)
    revoke.resolve()
    await pendingLogout
    expect(useUiStore.getState().authedUser?.id).toBe(userB.id)
  })

  it('cancels old reads and detaches late mutation callbacks so they cannot repopulate the next account or storage', async () => {
    await login()
    const previous = session.queryClient
    const queryResult = deferred<string>()
    const mutationResult = deferred<string>()
    const read = previous
      .fetchQuery({ queryKey: ['private-old-read'], queryFn: () => queryResult.promise })
      .catch(() => undefined)
    const mutation = previous.getMutationCache().build(previous, {
      mutationFn: () => mutationResult.promise,
      onSuccess: (value) => {
        previous.setQueryData(['private-old-write'], value)
      },
    })
    const write = mutation.execute(undefined)
    await login(userB)
    expect(previous.getQueryCache().getAll()).toHaveLength(0)
    expect(previous.getMutationCache().getAll()).toHaveLength(0)
    queryResult.resolve('PRIVATE-A-READ')
    mutationResult.resolve('PRIVATE-A-WRITE')
    await Promise.all([read, write])
    await new Promise((resolve) => setTimeout(resolve, 70))
    expect(session.queryClient.getQueryData(['private-old-read'])).toBeUndefined()
    expect(session.queryClient.getQueryData(['private-old-write'])).toBeUndefined()
    expect(localStorage.getItem('epharm.query.cache')).not.toContain('PRIVATE-A')
  })

  it.each([401, 403])(
    'a real query %i drops its previous projection without refetch loops',
    async (status) => {
      await login()
      const client = session.queryClient
      const key = ['pharmacists', 'sensitive-test']
      client.setQueryData(key, { private: 'OLD-PROJECTION' })
      const request = vi.fn().mockRejectedValue(denied(status))
      const observer = new QueryObserver(client, {
        queryKey: key,
        queryFn: request,
        staleTime: 0,
        retry: false,
      })
      const stop = observer.subscribe(() => {})
      await vi.waitFor(() => expect(observer.getCurrentResult().isError).toBe(true))
      expect(client.getQueryData(key)).toBeUndefined()
      expect(observer.getCurrentResult().data).toBeUndefined()
      expect(client.getQueryState(key)?.status).toBe('error')
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(request).toHaveBeenCalledOnce()
      stop()
    },
  )

  it('same-principal ordinary 500/offline data stays cached, while a role change starts an empty client', async () => {
    await login()
    const previous = session.queryClient
    previous.setQueryData(['promo', 'list'], 'same-owner-offline-snapshot')
    await expect(
      previous.fetchQuery({
        queryKey: ['promo', 'list'],
        queryFn: () => Promise.reject(denied(500)),
        staleTime: 0,
        retry: false,
      }),
    ).rejects.toThrow()
    expect(previous.getQueryData(['promo', 'list'])).toBe('same-owner-offline-snapshot')
    useUiStore.getState().setActiveRole({ ...userA, role: 'FINANCE_REVIEWER' })
    expect(session.queryClient).not.toBe(previous)
    expect(session.queryClient.getQueryCache().getAll()).toHaveLength(0)
  })

  it('forced logout from the actual axios refresh failure clears real memory and keeps learner authentication', async () => {
    await login()
    useUiStore.getState().init()
    const previous = session.queryClient
    previous.setQueryData(['promo-changes', 'same-id'], { private: 'PENDING-A' })
    localStorage.setItem('epharm.learner.tokens', 'learner-remains')
    api.defaults.adapter = (async (config) => {
      throw denied(401, config)
    }) satisfies AxiosAdapter
    await expect(api.get('/api/admin/synthetic-protected')).rejects.toThrow()
    expect(useUiStore.getState().authedUser).toBeNull()
    expect(previous.getQueryCache().getAll()).toHaveLength(0)
    expect(session.queryClient).not.toBe(previous)
    expect(localStorage.getItem('epharm.learner.tokens')).toBe('learner-remains')
  })

  it('a late older login cannot restore an account after logout or replace a newer login', async () => {
    const oldLogin = deferred<LoginResponse>()
    vi.spyOn(authApi, 'login').mockReturnValueOnce(oldLogin.promise)
    const first = useUiStore.getState().login(userA.email, 'synthetic-test-only')
    await useUiStore.getState().logout()
    await login(userB)
    oldLogin.resolve(response(userA))
    expect(await first).toMatchObject({ ok: false })
    expect(useUiStore.getState().authedUser?.id).toBe(userB.id)
  })
})
