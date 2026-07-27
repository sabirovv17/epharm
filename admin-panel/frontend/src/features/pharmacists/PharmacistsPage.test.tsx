// Тесты PharmacistsPage — пустой/loading/error/полный список + block/unblock.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PharmacistDto } from '@/lib/api-types'
import PharmacistsPage from './PharmacistsPage'

const pharmacistHooks = vi.hoisted(() => ({
  usePharmacists: vi.fn(),
  usePharmacist: vi.fn(),
  useCreatePharmacist: vi.fn(),
  useUpdatePharmacist: vi.fn(),
  useActivatePharmacist: vi.fn(),
  useBlockPharmacist: vi.fn(),
  useUnblockPharmacist: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacists', () => pharmacistHooks)

const pharmacyHooks = vi.hoisted(() => ({
  usePharmacies: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

function mkPharmacist(over: Partial<PharmacistDto> = {}): PharmacistDto {
  return {
    id: 'u_1',
    name: 'Айгерим Касенова',
    iin: '950101000001',
    phone: '+7 (701) 100-10-20',
    pharmacyId: 'ph_1',
    pharmacyName: 'Europharma №100',
    city: 'Алматы',
    tier: 'Gold',
    receipts30d: 50,
    rulesAccepted30d: 15,
    earned30d: 80_000,
    balance: 50_000,
    coursesDone: 3,
    coursesTotal: 9,
    status: 'active',
    joinedAt: '2026-01-15',
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
    ...over,
  }
}

function setPharmacists(list: PharmacistDto[] = []) {
  pharmacistHooks.usePharmacists.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setPharmacists([])
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      {
        id: 'ph_1',
        name: 'Ауэзова 134',
        chainId: 'inkar',
        chainName: 'Inkar',
        city: 'Алматы',
        district: '',
        addr: '',
        group: 'pilot',
        pharmacists: 0,
        receipts30d: 0,
        gmv30d: 0,
        liftPct: 0,
        rulesAccepted: 0,
        active: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ],
  })
  pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useBlockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <PharmacistsPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PharmacistsPage — рендер', () => {
  it('H1 + 4 metrics', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Фармацевты/i })).toBeInTheDocument()
    expect(screen.getByText('Всего фармацевтов')).toBeInTheDocument()
    // Активные / Pending встречаются в Metrics + Tabs
    expect(screen.getAllByText('Активные').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Pending').length).toBeGreaterThan(0)
    expect(screen.getByText('Текущие балансы')).toBeInTheDocument()
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Фармацевтов пока нет/i)).toBeInTheDocument()
  })
})

describe('PharmacistsPage — loading/error', () => {
  it('isLoading', () => {
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем фармацевтов/i)).toBeInTheDocument()
  })

  it('isError', async () => {
    const refetch = vi.fn()
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('PharmacistsPage — список и actions', () => {
  it('таблица показывает строки', () => {
    setPharmacists([
      mkPharmacist({ id: 'u_a', name: 'Айгерим' }),
      mkPharmacist({ id: 'u_b', name: 'Бауыржан', status: 'blocked' }),
    ])
    renderPage()
    expect(screen.getByTestId('pharmacists-table')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_a')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_b')).toBeInTheDocument()
  })

  it('фильтр active скрывает blocked', async () => {
    setPharmacists([
      mkPharmacist({ id: 'a', name: 'Active-X', status: 'active' }),
      mkPharmacist({ id: 'b', name: 'Blocked-X', status: 'blocked' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Активные/ }))
    expect(screen.getByText('Active-X')).toBeInTheDocument()
    expect(screen.queryByText('Blocked-X')).not.toBeInTheDocument()
  })

  it('кнопка «Разблок.» вызывает useUnblockPharmacist', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_b', status: 'blocked' })])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Разблок\./ }))
    expect(mutate).toHaveBeenCalledWith('u_b', expect.any(Object))
  })

  it('pending фармацевта можно назначить аптеке и активировать', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([
      mkPharmacist({
        id: 'u_pending',
        status: 'pending',
        pharmacyId: '',
        pharmacyName: '',
        city: '',
      }),
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Активировать' }))
    const dialog = screen.getByRole('dialog', { name: 'Активация фармацевта' })
    await user.selectOptions(within(dialog).getByRole('combobox'), 'ph_1')
    await user.click(within(dialog).getByRole('button', { name: 'Активировать' }))

    expect(mutate).toHaveBeenCalledWith(
      { id: 'u_pending', request: { pharmacyId: 'ph_1' } },
      expect.any(Object),
    )
  })
})
