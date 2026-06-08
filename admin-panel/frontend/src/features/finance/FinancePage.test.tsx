// Тесты FinancePage — pending/history Tabs, approve flow, items.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PayoutBatchDto, PayoutItemDto } from '@/lib/api-types'
import FinancePage from './FinancePage'

const financeHooks = vi.hoisted(() => ({
  usePayoutBatches: vi.fn(),
  usePayoutBatch: vi.fn(),
  usePayoutItems: vi.fn(),
  useApproveBatch: vi.fn(),
  useGeneratePayout: vi.fn(),
}))

vi.mock('@/lib/queries/finance', () => financeHooks)

function mkBatch(over: Partial<PayoutBatchDto> = {}): PayoutBatchDto {
  return {
    id: 'pb_pending',
    period: '1–15 мая 2026',
    status: 'pending',
    pharmacists: 100,
    amount: 1_000_000,
    items: 100,
    reviewerId: null,
    reviewerName: null,
    approvedAt: null,
    createdAt: '2026-05-15T00:00:00Z',
    updatedAt: '2026-05-15T00:00:00Z',
    ...over,
  }
}

function mkItem(over: Partial<PayoutItemDto> = {}): PayoutItemDto {
  return {
    id: 'pi_1',
    batchId: 'pb_pending',
    pharmacistId: 'u_1',
    pharmacistName: 'Айгерим',
    pharmacy: 'Europharma №100',
    city: 'Алматы',
    receipts: 50,
    rules: 20,
    amount: 15_000,
    flag: null,
    ...over,
  }
}

function setBatches(list: PayoutBatchDto[] = []) {
  financeHooks.usePayoutBatches.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

function setItems(list: PayoutItemDto[] = []) {
  financeHooks.usePayoutItems.mockReturnValue({ data: list, isLoading: false, isError: false })
}

beforeEach(() => {
  vi.clearAllMocks()
  setBatches([])
  setItems([])
  financeHooks.useApproveBatch.mockReturnValue({ mutate: vi.fn(), isPending: false })
  financeHooks.useGeneratePayout.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <FinancePage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FinancePage — рендер', () => {
  it('H1 + 4 metrics', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Финансы/i })).toBeInTheDocument()
    expect(screen.getByText('К выплате текущий период')).toBeInTheDocument()
    expect(screen.getByText('Выплачено всего')).toBeInTheDocument()
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Нет открытых батчей/i)).toBeInTheDocument()
  })
})

describe('FinancePage — loading/error', () => {
  it('isLoading', () => {
    financeHooks.usePayoutBatches.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем батчи…/i)).toBeInTheDocument()
  })

  it('isError', async () => {
    const refetch = vi.fn()
    financeHooks.usePayoutBatches.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить батчи/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('FinancePage — pending tab', () => {
  it('показывает текущий pending батч + items', () => {
    setBatches([mkBatch()])
    setItems([
      mkItem({ id: 'pi_a', pharmacistName: 'Айгерим', amount: 15_000 }),
      mkItem({ id: 'pi_b', pharmacistName: 'Бауыржан', amount: 30_000, flag: 'Высокий бонус' }),
    ])
    renderPage()
    expect(screen.getByTestId('payout-items-table')).toBeInTheDocument()
    expect(screen.getByTestId('payout-item-pi_a')).toBeInTheDocument()
    expect(screen.getByTestId('payout-item-pi_b')).toBeInTheDocument()
    expect(screen.getByText('Высокий бонус')).toBeInTheDocument()
  })

  it('кнопка «Утвердить» вызывает useApproveBatch', async () => {
    const mutate = vi.fn()
    financeHooks.useApproveBatch.mockReturnValue({ mutate, isPending: false })
    setBatches([mkBatch({ id: 'pb_x' })])
    setItems([])
    // confirm() возвращает true в тестах если мы его мокаем
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Утвердить выплату/ }))
    expect(mutate).toHaveBeenCalledWith('pb_x', expect.any(Object))
  })

  it('кнопка disabled и текст «Утверждаем…» при approving', () => {
    financeHooks.useApproveBatch.mockReturnValue({ mutate: vi.fn(), isPending: true })
    setBatches([mkBatch()])
    renderPage()
    const btn = screen.getByRole('button', { name: /Утверждаем…/ })
    expect(btn).toBeDisabled()
  })

  it('кнопка «Сформировать выплату» вызывает useGeneratePayout', async () => {
    const mutate = vi.fn()
    financeHooks.useGeneratePayout.mockReturnValue({ mutate, isPending: false })
    setBatches([])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('generate-payout'))
    expect(mutate).toHaveBeenCalled()
  })
})

describe('FinancePage — history tab', () => {
  it('переключение на «История» показывает approved батчи', async () => {
    setBatches([
      mkBatch({
        id: 'pb_1',
        status: 'approved',
        period: '1–15 апреля',
        reviewerName: 'Айгерим',
        approvedAt: '2026-04-17T00:00:00Z',
      }),
      mkBatch({
        id: 'pb_2',
        status: 'approved',
        period: '16–31 марта',
        reviewerName: 'Айгерим',
        approvedAt: '2026-04-02T00:00:00Z',
      }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /История/ }))
    expect(screen.getByTestId('payout-history-table')).toBeInTheDocument()
    expect(screen.getByTestId('payout-batch-pb_1')).toBeInTheDocument()
    expect(screen.getByTestId('payout-batch-pb_2')).toBeInTheDocument()
  })

  it('Empty state когда нет approved', async () => {
    setBatches([mkBatch()]) // только pending
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /История/ }))
    expect(screen.getByText(/История пуста/i)).toBeInTheDocument()
  })
})
