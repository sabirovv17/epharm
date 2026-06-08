// Глобальный TanStack Query client. Создаём один раз и переиспользуем.
// retry: false — мы хотим видеть ошибки сразу, а не ждать exponential backoff.
// staleTime: 30s — типичные таблицы админки не дёргаются чаще.
//
// Bug P fix: подключён queryPersist — cache в localStorage, restore на Cmd+R.
// Bug Q fix: keepPreviousData по дефолту (placeholderData) — при refetch'е и
// при ошибке последние данные остаются видны.

import { keepPreviousData, QueryClient } from '@tanstack/react-query'
import { configureQueryPersistence } from './queryPersist'

export const queryClient = new QueryClient({
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

// Подключаем persister один раз при module load. Storage = window.localStorage
// если доступен (browser); в SSR/jsdom без localStorage — no-op.
const storage =
  typeof globalThis !== 'undefined' && 'localStorage' in globalThis
    ? (globalThis as { localStorage: Storage }).localStorage
    : null

if (storage) {
  configureQueryPersistence(queryClient, storage)
}
