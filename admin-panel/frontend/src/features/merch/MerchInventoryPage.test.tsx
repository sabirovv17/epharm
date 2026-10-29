import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { keepPreviousData, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import MerchInventoryPage from './MerchInventoryPage'
import { inventoryApi, type MerchBranch, type MerchCalendar } from './inventory-api'

vi.mock('@/app/useAdministrativeCapabilities', () => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('./inventory-api', () => ({
  inventoryApi: { branches: vi.fn(), inventory: vi.fn(), calendar: vi.fn(), change: vi.fn() },
}))
const branch: MerchBranch = {
  id: 'branch-demo-01',
  organizationId: 'org-demo',
  organizationName: 'Демо',
  code: '01',
  name: 'Демо-аптека',
  address: 'Демо-адрес, 1',
  city: 'Демо',
  division: null,
  active: true,
  epharmPharmacyId: null,
  source: { system: 'crm', origin: 'demo', id: 'branch-demo-01' },
}
const calendar: MerchCalendar = {
  branch,
  startMonth: '2026-10-01',
  monthCount: 6,
  canManage: true,
  canViewPrices: false,
  months: [
    { month: '2026-10-01', availableCount: 1, blockedCount: 0, heldCount: 1, bookedCount: 0 },
  ],
  shelves: [
    {
      id: 'shelf-free',
      code: 'FREE',
      category: 'Демо',
      width: 40,
      price: null,
      active: true,
      months: [
        {
          month: '2026-10-01',
          inventoryId: null,
          status: 'available',
          reason: null,
          note: null,
          canEdit: true,
          placement: null,
        },
      ],
    },
    {
      id: 'shelf-held',
      code: 'HELD',
      category: 'Демо',
      width: 40,
      price: null,
      active: true,
      months: [
        {
          month: '2026-10-01',
          inventoryId: 'inventory-demo',
          status: 'held',
          reason: null,
          note: null,
          canEdit: false,
          placement: {
            status: 'held',
            portion: 'whole',
            portionLabel: 'Целиком',
            heldUntil: null,
            hasAdminAssignment: true,
            items: [],
          },
        },
      ],
    },
  ],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAdministrativeCapabilities).mockReturnValue({
    isPending: false,
    isError: false,
    canViewMerchInventory: true,
  } as ReturnType<typeof useAdministrativeCapabilities>)
  vi.mocked(inventoryApi.branches).mockResolvedValue({
    items: [branch],
    total: 1,
    limit: 50,
    offset: 0,
  })
  vi.mocked(inventoryApi.inventory).mockResolvedValue({ branch, containers: [], shelves: [] })
  vi.mocked(inventoryApi.calendar).mockResolvedValue(calendar)
  vi.mocked(inventoryApi.change).mockResolvedValue({
    branchId: branch.id,
    action: 'block',
    fromMonth: '2026-10-01',
    toMonth: '2026-10-01',
    targetCount: 1,
    changedCount: 1,
    protectedCount: 0,
    unchangedCount: 0,
    auditId: 'audit-demo',
  })
})
function setup() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, placeholderData: keepPreviousData },
      mutations: { retry: false },
    },
  })
  render(
    <QueryClientProvider client={client}>
      <MerchInventoryPage />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
async function chooseBranch() {
  const user = setup()
  await screen.findAllByRole('option', { name: /Демо-аптека/ })
  await user.selectOptions(screen.getByRole('combobox', { name: 'Аптека мерча' }), branch.id)
  await screen.findByLabelText('Выбрать полку FREE')
  return user
}
describe('native merch inventory boundary', () => {
  it('shows verification rather than a false denial while refreshing a mounted capability', () => {
    vi.mocked(useAdministrativeCapabilities).mockReturnValue({
      isPending: false,
      isFetching: true,
      isFetchedAfterMount: false,
      isError: false,
      canViewMerchInventory: false,
    } as ReturnType<typeof useAdministrativeCapabilities>)
    setup()
    expect(screen.getByRole('status')).toHaveTextContent('Проверяем доступ')
    expect(screen.queryByText('Мерчендайзинг недоступен')).not.toBeInTheDocument()
    expect(inventoryApi.branches).not.toHaveBeenCalled()
  })
  it('does not fetch branch data without the fresh server capability', () => {
    vi.mocked(useAdministrativeCapabilities).mockReturnValue({
      isPending: false,
      isError: false,
      canViewMerchInventory: false,
    } as ReturnType<typeof useAdministrativeCapabilities>)
    setup()
    expect(screen.getByText('Мерчендайзинг недоступен')).toBeInTheDocument()
    expect(inventoryApi.branches).not.toHaveBeenCalled()
    expect(inventoryApi.calendar).not.toHaveBeenCalled()
  })
  it('withheld prices are not zero; occupied cells cannot be selected', async () => {
    await chooseBranch()
    expect(screen.getByLabelText('Выбрать полку HELD')).toBeDisabled()
    expect(screen.getByText('Цены недоступны с вашими правами.')).toBeInTheDocument()
    expect(screen.queryByText(/0 ₸/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Проверить изменение' })).toBeDisabled()
  })
  it('requires explicit confirmation and sends only the selected branch and shelf', async () => {
    const user = await chooseBranch()
    await user.click(screen.getByLabelText('Выбрать полку FREE'))
    await user.click(screen.getByRole('button', { name: 'Проверить изменение' }))
    expect(inventoryApi.change).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Подтвердить' }))
    await waitFor(() =>
      expect(inventoryApi.change).toHaveBeenCalledWith(branch.id, {
        shelfIds: ['shelf-free'],
        fromMonth: '2026-10-01',
        toMonth: '2026-10-01',
        action: 'block',
        reason: 'repair',
        note: null,
      }),
    )
    expect(await screen.findByText(/Изменено: 1; защищено: 0/)).toBeInTheDocument()
  })
  it('does not carry the previous branch calendar into a different branch while loading', async () => {
    const second = { ...branch, id: 'branch-demo-02', name: 'Демо-аптека 02' }
    vi.mocked(inventoryApi.branches).mockResolvedValue({
      items: [branch, second],
      total: 2,
      limit: 50,
      offset: 0,
    })
    vi.mocked(inventoryApi.calendar).mockImplementation((id) =>
      id === branch.id ? Promise.resolve(calendar) : new Promise(() => {}),
    )
    vi.mocked(inventoryApi.inventory).mockImplementation((id) =>
      Promise.resolve({ branch: id === branch.id ? branch : second, containers: [], shelves: [] }),
    )
    const user = await chooseBranch()
    await user.click(screen.getByLabelText('Выбрать полку FREE'))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Аптека мерча' }), second.id)
    expect(screen.queryByLabelText('Выбрать полку FREE')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Проверить изменение' })).not.toBeInTheDocument()
    expect(inventoryApi.change).not.toHaveBeenCalled()
  })
  it('does not display or mutate cached calendar after permission failure', async () => {
    vi.mocked(inventoryApi.calendar).mockRejectedValue(new Error('Forbidden synthetic'))
    const user = setup()
    await screen.findByRole('option', { name: /Демо-аптека/ })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Аптека мерча' }), branch.id)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByLabelText('Выбрать полку FREE')).not.toBeInTheDocument()
    expect(inventoryApi.change).not.toHaveBeenCalled()
    expect(inventoryApi.calendar).toHaveBeenCalledTimes(1)
  })
})
