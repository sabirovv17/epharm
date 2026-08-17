// Reproduction-тест Bug P: cache TanStack Query теряется на Cmd+R.
//
// Сценарий бага: user заходит на /promo → данные загружаются → user жмёт Cmd+R →
// QueryClient пересоздаётся пустым → blank UI пока fetch не отработает.
// При downed backend — vечный blank.
//
// Senior fix: persister кладёт cache в localStorage. На next mount — restore.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'

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
    const { configureQueryPersistence } = await import('./queryPersist')
    const qc = new QueryClient()
    configureQueryPersistence(qc, storage)

    qc.setQueryData(['promo', 'list'], [{ id: 'pr_1', title: 'A' }])
    // Persister дебаунсит запись — даём ему таймаут
    await new Promise((r) => setTimeout(r, 100))

    const dumped = storage.getItem('epharm.query.cache')
    expect(dumped).toBeTruthy()
    expect(dumped).toContain('pr_1')
  })

  it('новый QueryClient восстанавливает данные из storage', async () => {
    const { configureQueryPersistence } = await import('./queryPersist')

    // Первый сеанс: пишем данные
    const qc1 = new QueryClient()
    configureQueryPersistence(qc1, storage)
    qc1.setQueryData(['promo', 'list'], [{ id: 'pr_1', title: 'Кэшировано' }])
    await new Promise((r) => setTimeout(r, 100))

    // Второй сеанс: эмуляция Cmd+R — новый QC, та же storage
    const qc2 = new QueryClient()
    configureQueryPersistence(qc2, storage)
    await new Promise((r) => setTimeout(r, 50))

    const restored = qc2.getQueryData(['promo', 'list'])
    expect(restored).toEqual([{ id: 'pr_1', title: 'Кэшировано' }])
    expect(qc2.getQueryState(['promo', 'list'])?.dataUpdatedAt).toBe(0)
  })

  it('clearPersistedCache очищает storage (вызывается из logout)', async () => {
    const { configureQueryPersistence, clearPersistedCache } = await import('./queryPersist')
    const qc = new QueryClient()
    configureQueryPersistence(qc, storage)
    qc.setQueryData(['x'], 'y')
    await new Promise((r) => setTimeout(r, 100))
    expect(storage.getItem('epharm.query.cache')).toBeTruthy()

    clearPersistedCache(storage)
    expect(storage.getItem('epharm.query.cache')).toBeNull()
  })

  it('corrupted JSON в storage → graceful fallback (no crash)', async () => {
    storage.setItem('epharm.query.cache', 'not-valid-json{')
    const { configureQueryPersistence } = await import('./queryPersist')
    const qc = new QueryClient()
    // Не должен бросить
    expect(() => configureQueryPersistence(qc, storage)).not.toThrow()
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

    const { configureQueryPersistence } = await import('./queryPersist')
    const qc = new QueryClient()
    configureQueryPersistence(qc, storage, { maxAgeMs: 24 * 60 * 60 * 1000 })

    // expired → не восстановили
    expect(qc.getQueryData(['promo', 'list'])).toBeUndefined()
  })
})
