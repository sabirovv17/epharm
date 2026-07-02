// Тесты PharmaciesPage — пустой/loading/error/полный список + filter + search.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ChainDto, PharmacyDto } from '@/lib/api-types'
import PharmaciesPage from './PharmaciesPage'

const pharmacyHooks = vi.hoisted(() => ({
  useChains: vi.fn(),
  usePharmacies: vi.fn(),
  usePharmacy: vi.fn(),
  useCreatePharmacy: vi.fn(),
  useUpdatePharmacy: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

function mkPharmacy(over: Partial<PharmacyDto> = {}): PharmacyDto {
  return {
    id: 'ph_1',
    name: 'Аптека №1',
    chainId: 'europharma',
    chainName: 'Europharma',
    city: 'Алматы',
    district: 'Бостандыкский',
    addr: 'ул. Абая, 10',
    group: 'pilot',
    pharmacists: 3,
    receipts30d: 500,
    gmv30d: 1_500_000,
    liftPct: 15.5,
    rulesAccepted: 42,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

function mkChain(over: Partial<ChainDto> = {}): ChainDto {
  return {
    id: 'europharma',
    name: 'Europharma',
    color: '#BE5A38',
    points: 100,
    group: 'pilot',
    ...over,
  }
}

function setPharmacies(list: PharmacyDto[] = []) {
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setPharmacies([])
  pharmacyHooks.useChains.mockReturnValue({ data: [], isLoading: false })
  pharmacyHooks.useCreatePharmacy.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <PharmaciesPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PharmaciesPage — рендер', () => {
  it('H1 и 4 metrics', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Сеть аптек/i })).toBeInTheDocument()
    expect(screen.getByText('Всего аптек')).toBeInTheDocument()
    // Пилот/Контроль/Развёрнутые встречаются в Metrics + в Tabs
    expect(screen.getAllByText('Пилот').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Контроль').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Развёрнутые').length).toBeGreaterThan(0)
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Аптек пока нет/i)).toBeInTheDocument()
  })
})

describe('PharmaciesPage — loading/error', () => {
  it('isLoading → loading state', () => {
    pharmacyHooks.usePharmacies.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем аптеки…/i)).toBeInTheDocument()
  })

  it('isError → ошибка + Повторить', async () => {
    const refetch = vi.fn()
    pharmacyHooks.usePharmacies.mockReturnValue({
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

describe('PharmaciesPage — список', () => {
  it('таблица показывает строки + правильные значения', () => {
    setPharmacies([
      mkPharmacy({ id: 'ph_a', name: 'Europharma №100' }),
      mkPharmacy({ id: 'ph_b', name: 'Зерде №101', chainName: 'Зерде', group: 'control' }),
    ])
    renderPage()
    expect(screen.getByTestId('pharmacies-table')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacy-row-ph_a')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacy-row-ph_b')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacy-posm-id-ph_a')).toHaveTextContent('ph_a')
    expect(screen.getByRole('columnheader', { name: /POSM pharmacyId/i })).toBeInTheDocument()
    expect(screen.getByText('Europharma №100')).toBeInTheDocument()
  })

  it('counts метрик корректны', () => {
    setPharmacies([
      mkPharmacy({ id: '1', group: 'pilot' }),
      mkPharmacy({ id: '2', group: 'pilot' }),
      mkPharmacy({ id: '3', group: 'control' }),
      mkPharmacy({ id: '4', group: 'rolled' }),
    ])
    pharmacyHooks.useChains.mockReturnValue({ data: [mkChain()], isLoading: false })
    renderPage()
    // "Всего аптек" = 4, табы показывают тоже counts
    expect(screen.getAllByText('4').length).toBeGreaterThan(0)
    expect(screen.getAllByText('2').length).toBeGreaterThan(0)
  })

  it('фильтр по табу "Пилот"', async () => {
    setPharmacies([
      mkPharmacy({ id: 'p1', name: 'Pilot-1', group: 'pilot' }),
      mkPharmacy({ id: 'c1', name: 'Control-1', group: 'control' }),
    ])
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText('Pilot-1')).toBeInTheDocument()
    expect(screen.getByText('Control-1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Пилот/ }))
    expect(screen.getByText('Pilot-1')).toBeInTheDocument()
    expect(screen.queryByText('Control-1')).not.toBeInTheDocument()
  })

  it('поиск фильтрует список', async () => {
    setPharmacies([
      mkPharmacy({ id: '1', name: 'Аквалор', city: 'Алматы' }),
      mkPharmacy({ id: '2', name: 'Зерде', city: 'Астана' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByPlaceholderText(/Поиск по аптеке/), 'Зерде')
    expect(screen.queryByText('Аквалор')).not.toBeInTheDocument()
    expect(screen.getByText('Зерде')).toBeInTheDocument()
  })

  it('поиск находит аптеку по POSM pharmacyId', async () => {
    setPharmacies([
      mkPharmacy({ id: 'ph_medusa_abc', name: 'Аквалор', city: 'Алматы' }),
      mkPharmacy({ id: 'ph_other', name: 'Зерде', city: 'Астана' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByPlaceholderText(/Поиск по аптеке, ID/), 'medusa_abc')
    expect(screen.getByText('Аквалор')).toBeInTheDocument()
    expect(screen.queryByText('Зерде')).not.toBeInTheDocument()
  })
})

describe('PharmaciesPage — CRUD (Блок 2)', () => {
  it('кнопка «Новая аптека» открывает модалку создания', async () => {
    pharmacyHooks.useChains.mockReturnValue({ data: [mkChain()], isLoading: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('pharmacy-create-btn'))
    // модалка → поле «Название» + кнопка «Добавить аптеку»
    expect(screen.getByRole('button', { name: /Добавить аптеку/ })).toBeInTheDocument()
  })

  it('клик по строке → навигация на /pharmacies/:id', async () => {
    setPharmacies([mkPharmacy({ id: 'ph_nav', name: 'Navi-аптека' })])
    const user = userEvent.setup()
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter initialEntries={['/pharmacies']}>
          <ToastHost>
            <Routes>
              <Route path="/pharmacies" element={<PharmaciesPage />} />
              <Route
                path="/pharmacies/:id"
                element={<div>ДЕТАЛЬ {window.location.pathname}</div>}
              />
            </Routes>
          </ToastHost>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await user.click(screen.getByTestId('pharmacy-row-ph_nav'))
    expect(screen.getByText(/ДЕТАЛЬ/)).toBeInTheDocument()
  })
})
