import { hashKey, type QueryClient } from '@tanstack/react-query'

export interface PersistableStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem: (key: string) => void
}

const STORAGE_KEY = 'epharm.query.cache'
const DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000

interface PersistedEnvelope {
  version: 2
  principal: string
  timestamp: number
  entries: Array<{ key: string; queryKey: readonly unknown[]; data: unknown }>
}

interface ConfigureOptions {
  principal?: string | null
  maxAgeMs?: number
  debounceMs?: number
}

export function mayPersistQuery(key: readonly unknown[]): boolean {
  const first = key[0]
  return (
    typeof first === 'string' &&
    first !== 'promo-changes' &&
    !/^(access|admin-access|unified-access|unified-admin-access)(?:$|[-/:.])/.test(first)
  )
}

export function configureQueryPersistence(
  client: QueryClient,
  storage: PersistableStorage,
  { principal = null, maxAgeMs = DEFAULT_MAX_AGE_MS, debounceMs = 50 }: ConfigureOptions = {},
): () => void {
  if (!principal) {
    clearPersistedCache(storage)
    return () => {}
  }
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (raw) {
      const envelope = JSON.parse(raw) as PersistedEnvelope
      const age = Date.now() - envelope.timestamp
      if (
        envelope.version === 2 &&
        envelope.principal === principal &&
        age >= 0 &&
        age < maxAgeMs &&
        Array.isArray(envelope.entries)
      ) {
        for (const entry of envelope.entries) {
          if (
            Array.isArray(entry.queryKey) &&
            mayPersistQuery(entry.queryKey) &&
            entry.data !== undefined
          ) {
            client.setQueryData(entry.queryKey, entry.data, { updatedAt: 0 })
          }
        }
        if (
          envelope.entries.some(
            (entry) => Array.isArray(entry.queryKey) && !mayPersistQuery(entry.queryKey),
          )
        )
          clearPersistedCache(storage)
      } else clearPersistedCache(storage)
    }
  } catch {
    clearPersistedCache(storage)
  }

  let disposed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const persist = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      if (disposed) return
      const envelope: PersistedEnvelope = {
        version: 2,
        principal,
        timestamp: Date.now(),
        entries: client
          .getQueryCache()
          .getAll()
          .filter(
            (query) =>
              query.state.status === 'success' &&
              query.state.data !== undefined &&
              mayPersistQuery(query.queryKey),
          )
          .map((query) => ({
            key: hashKey(query.queryKey),
            queryKey: query.queryKey,
            data: query.state.data,
          })),
      }
      try {
        storage.setItem(STORAGE_KEY, JSON.stringify(envelope))
      } catch {
        /* quota/private mode */
      }
    }, debounceMs)
  }
  const unsubscribe = client.getQueryCache().subscribe(persist)
  persist()
  return () => {
    disposed = true
    clearTimeout(timer)
    unsubscribe()
  }
}

export function clearPersistedCache(storage: PersistableStorage): void {
  try {
    storage.removeItem(STORAGE_KEY)
  } catch {
    /* private mode */
  }
}
