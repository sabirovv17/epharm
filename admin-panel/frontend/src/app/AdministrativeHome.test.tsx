import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  accessApi,
  type AccessCapabilities,
  type AccessGrant,
} from '@/features/settings/access-api'
import { USERS } from '@/mocks/fixtures'
import AdministrativeHome from './AdministrativeHome'
import AdminStaffAccessPage from '@/features/merch/AdminStaffAccessPage'
import { administrativeHomePath } from './administrativeHomePath'
import { useUiStore } from './store'

vi.mock('@/features/settings/access-api', () => ({ accessApi: { me: vi.fn() } }))
const view: AccessGrant = {
  module: 'merchandising',
  subsection: 'staff_access',
  action: 'view',
  allPharmacies: false,
  pharmacyIds: [],
  allMerchBranches: true,
  merchBranchIds: [],
}
const soleStaff: AccessCapabilities = {
  managed: true,
  version: 1,
  roleCeiling: 'HQ_HEAD',
  grants: [view],
  canViewAccounts: false,
  canManageAccounts: false,
  canViewMerchStaffAccess: true,
}
beforeEach(() => {
  useUiStore.setState({ authedUser: USERS.bauyrzhan })
  vi.mocked(accessApi.me).mockReset()
})
afterEach(() => {
  useUiStore.setState({ authedUser: null, tokens: null })
})

function renderHome(cached?: AccessCapabilities) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  if (cached) client.setQueryData(['admin-access', 'me', USERS.bauyrzhan.id], cached)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<AdministrativeHome />} />
          <Route path="/merch/staff-access" element={<AdminStaffAccessPage />} />
          <Route path="/rules" element={<div>RulesWorkspace</div>} />
          <Route path="/lms" element={<div>LearningWorkspace</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it('routes a fresh historical sole-staff profile to the retired notice without code APIs', async () => {
  vi.mocked(accessApi.me).mockResolvedValue(soleStaff)
  renderHome()
  expect(await screen.findByText('Общие коды доступа отключены')).toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(accessApi.me).toHaveBeenCalledOnce()
})
it.each(['HQ_HEAD', 'TRAINING_MANAGER'] as const)(
  'preserves the existing %s default when another permission is present',
  (role) => {
    expect(
      administrativeHomePath(role, {
        ...soleStaff,
        grants: [view, { ...view, module: 'training', subsection: 'courses' }],
      }),
    ).toBe(role === 'HQ_HEAD' ? '/rules' : '/lms')
  },
)
it.each([
  { ...soleStaff, managed: false },
  { ...soleStaff, canViewMerchStaffAccess: false },
  { ...soleStaff, grants: [] },
  { ...soleStaff, grants: [{ ...view, action: 'unknown_future_action' }] },
  { ...soleStaff, grants: [view, { ...view, action: 'unknown_future_action' }] },
  { ...soleStaff, grants: [{ ...view, action: 'create' }] },
])('never infers sole access from an unverified or incomplete capability: %j', (access) => {
  expect(administrativeHomePath('HQ_HEAD', access)).toBe('/rules')
})
it('waits for the current request instead of following a cached positive staff capability', async () => {
  let resolve!: (value: AccessCapabilities) => void
  vi.mocked(accessApi.me).mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  renderHome(soleStaff)
  expect(screen.getByRole('status')).toHaveTextContent('Проверяем доступ')
  expect(screen.queryByText('Общие коды доступа отключены')).not.toBeInTheDocument()
  await act(async () => resolve({ ...soleStaff, canViewMerchStaffAccess: false }))
  expect(await screen.findByText('RulesWorkspace')).toBeInTheDocument()
})
it('a failed fresh check does not open either workspace even with a cached positive result', async () => {
  vi.mocked(accessApi.me).mockRejectedValue(new Error('denied'))
  renderHome(soleStaff)
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось проверить доступ')
  expect(screen.queryByText('Общие коды доступа отключены')).not.toBeInTheDocument()
  expect(screen.queryByText('RulesWorkspace')).not.toBeInTheDocument()
})
it('normal training accounts still reach LMS after the fresh check', async () => {
  useUiStore.setState({ authedUser: USERS.lms })
  vi.mocked(accessApi.me).mockResolvedValue({
    ...soleStaff,
    managed: false,
    canViewMerchStaffAccess: false,
    grants: [],
  })
  renderHome()
  await waitFor(() => expect(screen.getByText('LearningWorkspace')).toBeInTheDocument())
})
