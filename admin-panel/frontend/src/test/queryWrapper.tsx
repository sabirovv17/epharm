// Test helper — оборачивает render в QueryClientProvider + MemoryRouter + ToastHost.
// Каждый вызов создаёт свежий QueryClient (изоляция между тестами).

import { type ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ToastHost } from '@/ui'

export function makeTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        // Не используем кэш между тестами.
        gcTime: 0,
        staleTime: 0,
      },
      mutations: { retry: false },
    },
  })
}

interface WrapOptions {
  client?: QueryClient
  initialRoute?: string
  withToastHost?: boolean
}

export function AppProviders({
  children,
  client,
  initialRoute = '/',
  withToastHost = true,
}: WrapOptions & { children: ReactNode }) {
  const qc = client ?? makeTestQueryClient()
  const body = withToastHost ? <ToastHost>{children}</ToastHost> : <>{children}</>
  return (
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialRoute]}>{body}</MemoryRouter>
    </QueryClientProvider>
  )
}
