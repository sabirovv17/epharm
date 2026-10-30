import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { USERS } from '@/mocks/fixtures'
import { useUiStore } from '@/app/store'
import AdminStaffAccessPage from './AdminStaffAccessPage'
vi.mock('@/lib/api', () => ({ api: { get: vi.fn() } }))
afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
  vi.resetAllMocks()
})
it('staff_access/view-only enters its own surface without calling task or inventory APIs', async () => {
  useUiStore.setState({ authedUser: USERS.bauyrzhan })
  vi.mocked(api.get).mockImplementation(async (path) => ({
    data:
      path === '/api/admin/access/me'
        ? { canViewMerchStaffAccess: true, canViewMerchTasks: false, canViewMerchInventory: false }
        : { organizations: [] },
  }))
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AdminStaffAccessPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('Коды доступа сотрудников')).toBeInTheDocument()
  expect(vi.mocked(api.get).mock.calls.map(([path]) => path)).toEqual([
    '/api/admin/access/me',
    '/api/admin/merch/staff-access/options',
  ])
})
it('old role alone never opens even the options endpoint', async () => {
  useUiStore.setState({ authedUser: USERS.bauyrzhan })
  vi.mocked(api.get).mockResolvedValue({ data: { canViewMerchStaffAccess: false } })
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <AdminStaffAccessPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('Нет доступа к кодам сотрудников')).toBeInTheDocument()
  expect(api.get).toHaveBeenCalledOnce()
})
