// One active administrator client; a new session gets an empty, detached cache.
// retry: false — мы хотим видеть ошибки сразу, а не ждать exponential backoff.
// staleTime: 30s — типичные таблицы админки не дёргаются чаще.
//
// Bug P fix: подключён queryPersist — cache в localStorage, restore на Cmd+R.
// Bug Q fix: keepPreviousData по дефолту (placeholderData) — при refetch'е и
// при ошибке последние данные остаются видны.

import { keepPreviousData, QueryCache, QueryClient } from '@tanstack/react-query'
import axios from 'axios'
import { clearPersistedCache, configureQueryPersistence } from './queryPersist'
import { tokenStore } from '@/lib/tokenStore'
import type { UserDto } from '@/lib/api-types'

const createClient = () =>
  new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (axios.isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0)) {
          query.setState({ data: undefined, dataUpdatedAt: 0 })
        }
      },
    }),
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        staleTime: 30_000,
        // Stale data остаётся видна пока refetch не отработает или не упадёт.
        placeholderData: keepPreviousData,
      },
      mutations: {
        retry: false,
      },
    },
  })

// Each session owns and disposes its persister. Storage = window.localStorage
// если доступен (browser); в SSR/jsdom без localStorage — no-op.
const storage =
  typeof globalThis !== 'undefined' && 'localStorage' in globalThis
    ? (globalThis as { localStorage: Storage }).localStorage
    : null

const principalKey = (user: Pick<UserDto, 'id' | 'role'> | null) =>
  user ? JSON.stringify([user.id, user.role]) : null
let principal: string | null = null
try {
  principal = principalKey(tokenStore.load()?.user ?? null)
} catch {
  /* unavailable storage */
}
export let queryClient = createClient()
export let querySessionEpoch = 0
let stopPersistence = storage
  ? configureQueryPersistence(queryClient, storage, { principal })
  : () => {}

/** A detached client contains any late callbacks; it can never become the next principal's cache. */
export function resetQuerySession(user: Pick<UserDto, 'id' | 'role'> | null): void {
  stopPersistence()
  void queryClient.cancelQueries()
  queryClient.clear()
  if (storage) clearPersistedCache(storage)
  principal = principalKey(user)
  queryClient = createClient()
  querySessionEpoch++
  stopPersistence = storage
    ? configureQueryPersistence(queryClient, storage, { principal })
    : () => {}
}
