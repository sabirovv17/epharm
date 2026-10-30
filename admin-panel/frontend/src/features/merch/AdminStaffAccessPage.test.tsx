import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { USERS } from '@/mocks/fixtures'
import { useUiStore } from '@/app/store'
import RetiredAdminStaffAccessPage, {
  ArchivedAdminStaffAccessPage as AdminStaffAccessPage,
} from './AdminStaffAccessPage'
vi.mock('@/lib/api', () => ({ api: { get: vi.fn() } }))
afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
  vi.resetAllMocks()
})
describe('preserved unrouted shared-access admin component', () => {
  it('staff_access/view-only enters its own surface without calling task or inventory APIs', async () => {
    useUiStore.setState({ authedUser: USERS.bauyrzhan })
    vi.mocked(api.get).mockImplementation(async (path) => ({
      data:
        path === '/api/admin/access/me'
          ? {
              canViewMerchStaffAccess: true,
              canViewMerchTasks: false,
              canViewMerchInventory: false,
            }
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
})

it('the routed admin entry never offers issue or revoke even when the account previously had access', () => {
  useUiStore.setState({ authedUser: USERS.bauyrzhan })
  vi.mocked(api.get).mockResolvedValue({ data: { canViewMerchStaffAccess: true } })
  render(<RetiredAdminStaffAccessPage />)
  expect(screen.getByRole('heading', { name: 'Общие коды доступа отключены' })).toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Личный кабинет фармацевта' })).toHaveAttribute(
    'href',
    'https://epharm.inkar.kz/learn',
  )
  expect(api.get).not.toHaveBeenCalled()
})
