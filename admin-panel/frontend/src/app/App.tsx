// App root — QueryClientProvider + BrowserRouter + AppRouter + auth-store init.
import { useEffect } from 'react'
import { BrowserRouter } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { AppRouter } from './router'
import { ErrorBoundary } from './ErrorBoundary'
import { useUiStore } from './store'
import { queryClient } from './queryClient'

export function App() {
  // 1 раз при mount'е: гидрируем токены из localStorage + подписываемся на forced-logout
  // от axios-interceptor'а (401 при провале refresh).
  useEffect(() => {
    useUiStore.getState().init()
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <ErrorBoundary>
          <AppRouter />
        </ErrorBoundary>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
