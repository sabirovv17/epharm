// Reproduction-тест Bug P: cache TanStack Query теряется на Cmd+R.
//
// Сценарий бага: user заходит на /promo → данные загружаются → user жмёт Cmd+R →
// QueryClient пересоздаётся пустым → blank UI пока fetch не отработает.
// При downed backend — vечный blank.
//
// Senior fix: persister кладёт cache в localStorage. На next mount — restore.

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import {
  configureQueryPersistence as configurePersistence,
  clearPersistedCache,
} from './queryPersist'

const disposers: Array<() => void> = []
function configureQueryPersistence(...args: Parameters<typeof configurePersistence>) {
  const stop = configurePersistence(...args)
  disposers.push(stop)
  return stop
}
afterEach(() => disposers.splice(0).forEach((stop) => stop()))

// Простой synchronous storage mock — vitest jsdom не даёт настоящий localStorage
const memoryStorage: Record<string, string> = {}
const storage = {
  getItem: vi.fn((k: string) => memoryStorage[k] ?? null),
  setItem: vi.fn((k: string, v: string) => {
    memoryStorage[k] = v
  }),
  removeItem: vi.fn((k: string) => {
    delete memoryStorage[k]
  }),
  clear: vi.fn(() => {
    Object.keys(memoryStorage).forEach((k) => delete memoryStorage[k])
  }),
}

beforeEach(() => {
  storage.clear()
  vi.clearAllMocks()
})

describe('Bug P REPRO — query cache persistence в localStorage', () => {
  it('после setQueryData — данные сохранены в storage', async () => {
    const qc = new QueryClient()
    configureQueryPersistence(qc, storage, { principal: 'demo-a' })

    qc.setQueryData(['promo', 'list'], [{ id: 'pr_1', title: 'A' }])
    // Persister дебаунсит запись — даём ему таймаут
    await new Promise((r) => setTimeout(r, 100))

    const dumped = storage.getItem('epharm.query.cache')
    expect(dumped).toBeTruthy()
    expect(dumped).toContain('pr_1')
  })

  it('новый QueryClient восстанавливает данные из storage', async () => {
    // Первый сеанс: пишем данные
    const qc1 = new QueryClient()
    configureQueryPersistence(qc1, storage, { principal: 'demo-a' })
    qc1.setQueryData(['promo', 'list'], [{ id: 'pr_1', title: 'Кэшировано' }])
    await new Promise((r) => setTimeout(r, 100))

    // Второй сеанс: эмуляция Cmd+R — новый QC, та же storage
    const qc2 = new QueryClient()
    configureQueryPersistence(qc2, storage, { principal: 'demo-a' })
    await new Promise((r) => setTimeout(r, 50))

    const restored = qc2.getQueryData(['promo', 'list'])
    expect(restored).toEqual([{ id: 'pr_1', title: 'Кэшировано' }])
    expect(qc2.getQueryState(['promo', 'list'])?.dataUpdatedAt).toBe(0)
  })

  it('clearPersistedCache очищает storage (вызывается из logout)', async () => {
    const qc = new QueryClient()
    configureQueryPersistence(qc, storage, { principal: 'demo-a' })
    qc.setQueryData(['x'], 'y')
    await new Promise((r) => setTimeout(r, 100))
    expect(storage.getItem('epharm.query.cache')).toBeTruthy()

    clearPersistedCache(storage)
    expect(storage.getItem('epharm.query.cache')).toBeNull()
  })

  it('corrupted JSON в storage → graceful fallback (no crash)', async () => {
    storage.setItem('epharm.query.cache', 'not-valid-json{')
    const qc = new QueryClient()
    // Не должен бросить
    expect(() => configureQueryPersistence(qc, storage, { principal: 'demo-a' })).not.toThrow()
    // Cache пуст после corrupted restore
    expect(qc.getQueryData(['anything'])).toBeUndefined()
  })

  it('expired cache (older than maxAge) — не восстанавливает', async () => {
    // Записываем cache «из прошлого»
    const ancient = {
      timestamp: Date.now() - 48 * 60 * 60 * 1000, // 48 часов назад
      data: { promo: { list: [{ id: 'old' }] } },
    }
    storage.setItem('epharm.query.cache', JSON.stringify(ancient))

    const qc = new QueryClient()
    configureQueryPersistence(qc, storage, { principal: 'demo-a', maxAgeMs: 24 * 60 * 60 * 1000 })

    // expired → не восстановили
    expect(qc.getQueryData(['promo', 'list'])).toBeUndefined()
  })
})

describe('Permission cache and principal boundary', () => {
  it('never writes prepared campaign bodies or access capabilities', async () => {
    const client = new QueryClient()
    const stop = configureQueryPersistence(client, storage, { principal: 'demo-a', debounceMs: 1 })
    for (const key of ['promo-changes', 'access', 'admin-access', 'unified-admin-access']) {
      client.setQueryData([key, 'same-id'], { secret: 'PRIVATE-PENDING-ONLY', canApply: true })
    }
    client.setQueryData(['promo', 'list'], { title: 'ordinary-authorized-cache' })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(storage.getItem('epharm.query.cache')).toContain('ordinary-authorized-cache')
    expect(storage.getItem('epharm.query.cache')).not.toContain('PRIVATE-PENDING-ONLY')
    stop()
  })

  it('filters and immediately scrubs old permission-bearing entries during same-owner restoration', async () => {
    storage.setItem(
      'epharm.query.cache',
      JSON.stringify({
        version: 2,
        principal: 'demo-a',
        timestamp: Date.now(),
        entries: [
          {
            key: 'pending',
            queryKey: ['promo-changes', 'same-id'],
            data: { private: 'OLD-PENDING' },
          },
          { key: 'access', queryKey: ['access', 'me'], data: { canManage: true } },
          { key: 'normal', queryKey: ['promo', 'list'], data: 'same-principal-offline-data' },
        ],
      }),
    )
    const client = new QueryClient()
    const stop = configureQueryPersistence(client, storage, { principal: 'demo-a' })
    expect(client.getQueryData(['promo-changes', 'same-id'])).toBeUndefined()
    expect(client.getQueryData(['access', 'me'])).toBeUndefined()
    expect(client.getQueryData(['promo', 'list'])).toBe('same-principal-offline-data')
    expect(storage.getItem('epharm.query.cache')).toBeNull()
    stop()
  })

  it('does not restore another owner or an old envelope with no verified cache owner', async () => {
    for (const owner of [{ version: 2, principal: 'demo-a' }, {}]) {
      storage.setItem(
        'epharm.query.cache',
        JSON.stringify({
          ...owner,
          timestamp: Date.now(),
          entries: [{ key: 'x', queryKey: ['promo', 'list'], data: 'OTHER-ACCOUNT' }],
        }),
      )
      const client = new QueryClient()
      const stop = configureQueryPersistence(client, storage, { principal: 'demo-b' })
      expect(client.getQueryData(['promo', 'list'])).toBeUndefined()
      expect(storage.getItem('epharm.query.cache')).toBeNull()
      stop()
    }
  })

  it('a detached persister cannot write a scheduled or late previous-account result', async () => {
    const client = new QueryClient()
    const stop = configureQueryPersistence(client, storage, { principal: 'demo-a', debounceMs: 5 })
    client.setQueryData(['promo', 'list'], 'OLD-ACCOUNT')
    stop()
    clearPersistedCache(storage)
    client.setQueryData(['late'], 'OLD-ACCOUNT')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(storage.getItem('epharm.query.cache')).toBeNull()
  })

  it('anonymous sessions neither restore nor write an authenticated cache', async () => {
    storage.setItem('epharm.query.cache', 'old-private-cache')
    const client = new QueryClient()
    configureQueryPersistence(client, storage)
    client.setQueryData(['promo'], 'anonymous')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(storage.getItem('epharm.query.cache')).toBeNull()
  })
})
