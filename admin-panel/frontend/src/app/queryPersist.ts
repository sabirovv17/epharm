// Lightweight QueryClient persister — кладёт cache в localStorage, восстанавливает
// при загрузке. Без зависимости от @tanstack/react-query-persist-client.
//
// Bug P fix: после Cmd+R пользователь видит закэшированные данные мгновенно,
// фон делает background refetch. Если backend упал — данные остаются видны.
//
// Архитектура:
//  - При configureQueryPersistence: восстанавливаем кэш из storage в QueryClient.
//  - Подписываемся на queryCache changes → дебаунс 250ms → запись в storage.
//  - maxAge: если запись старше 24h, не восстанавливаем (защита от stale data).
//  - clearPersistedCache: вызывается на logout.

import type { QueryClient } from '@tanstack/react-query'
import { hashKey, type Query } from '@tanstack/react-query'

export interface PersistableStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem: (key: string) => void
}

const STORAGE_KEY = 'epharm.query.cache'
const DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000 // 24h

interface PersistedEnvelope {
  timestamp: number
  entries: Array<{ key: string; queryKey: readonly unknown[]; data: unknown }>
}

interface ConfigureOptions {
  maxAgeMs?: number
  debounceMs?: number
}

// Глобальный контроль pause-после-clear: clearPersistedCache ставит флаг,
// который subscriber видит и игнорирует одну volna writes (защита от race'а
// «storage.removeItem → subscriber дописывает обратно»).
let paused = false
let pauseTimer: ReturnType<typeof setTimeout> | null = null
let writeTimer: ReturnType<typeof setTimeout> | null = null

function pauseWrites(durationMs: number) {
  paused = true
  if (pauseTimer) clearTimeout(pauseTimer)
  if (writeTimer) {
    clearTimeout(writeTimer)
    writeTimer = null
  }
  pauseTimer = setTimeout(() => {
    paused = false
    pauseTimer = null
  }, durationMs)
}

export function configureQueryPersistence(
  qc: QueryClient,
  storage: PersistableStorage,
  opts: ConfigureOptions = {},
): () => void {
  const maxAgeMs = opts.maxAgeMs ?? DEFAULT_MAX_AGE_MS
  const debounceMs = opts.debounceMs ?? 50

  // ── Restore ───────────────────────────────────────────────────────────
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (raw) {
      const env = JSON.parse(raw) as PersistedEnvelope
      const age = Date.now() - (env.timestamp ?? 0)
      if (age >= 0 && age < maxAgeMs && Array.isArray(env.entries)) {
        for (const e of env.entries) {
          // setQueryData принимает queryKey + data. Это создаёт записи в кэше.
          if (Array.isArray(e.queryKey) && e.data !== undefined) {
            qc.setQueryData(e.queryKey, e.data)
          }
        }
      } else {
        // Истёкший кэш — чистим, чтобы не висел в storage.
        storage.removeItem(STORAGE_KEY)
      }
    }
  } catch {
    // Битый JSON / quota error — игнорим, начинаем с пустого кэша
    try {
      storage.removeItem(STORAGE_KEY)
    } catch {
      /* noop */
    }
  }

  // ── Subscribe ─────────────────────────────────────────────────────────
  const cache = qc.getQueryCache()
  const persist = () => {
    if (paused) return // во время clear (logout) не дописываем обратно
    if (writeTimer) clearTimeout(writeTimer)
    writeTimer = setTimeout(() => {
      if (paused) return
      try {
        const envelope: PersistedEnvelope = {
          timestamp: Date.now(),
          entries: cache
            .getAll()
            // Сохраняем только успешные queries с данными (error-state не нужен).
            .filter((q: Query) => q.state.status === 'success' && q.state.data !== undefined)
            .map((q: Query) => ({
              key: hashKey(q.queryKey),
              queryKey: q.queryKey as readonly unknown[],
              data: q.state.data,
            })),
        }
        storage.setItem(STORAGE_KEY, JSON.stringify(envelope))
      } catch {
        // QuotaExceededError или private-mode — игнорим
      }
    }, debounceMs)
  }

  const unsubscribe = cache.subscribe(() => persist())
  // Сразу пытаемся записать (на случай если restore тоже что-то добавил).
  persist()

  return () => {
    if (writeTimer) clearTimeout(writeTimer)
    writeTimer = null
    unsubscribe()
  }
}

export function clearPersistedCache(storage: PersistableStorage): void {
  // Пауза > debounceMs чтобы subscriber-эвенты от unmount'а компонентов
  // на logout flow не дописали кэш обратно.
  pauseWrites(500)
  try {
    storage.removeItem(STORAGE_KEY)
  } catch {
    /* noop */
  }
}
