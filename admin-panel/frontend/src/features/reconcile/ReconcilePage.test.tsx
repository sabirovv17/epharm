// Тесты ReconcilePage — очередь сверки чеков + approve/reject.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ReceiptDto, ReconcileSummaryDto } from '@/lib/api-types'
import ReconcilePage from './ReconcilePage'

const hooks = vi.hoisted(() => ({
  useReceipts: vi.fn(),
  useReconcileSummary: vi.fn(),
  useApproveReceipt: vi.fn(),
  useRejectReceipt: vi.fn(),
  useImportExcel: vi.fn(),
}))
vi.mock('@/lib/queries/reconcile', () => hooks)

// Каталог — чтобы resolve SKU→имя не ходил в сеть.
vi.mock('@/lib/queries/catalog', () => ({
  useProducts: () => ({ data: [{ id: 'p_aql_norm_s', name: 'Аквалор Норм спрей' }] }),
  buildProductIndex:
    (list: Array<{ id: string; name: string }> | undefined) => (id: string | undefined) =>
      list?.find((p) => p.id === id),
}))

const approveMutate = vi.fn()
const rejectMutate = vi.fn()

function mkReceipt(over: Partial<ReceiptDto> = {}): ReceiptDto {
  return {
    id: 'rcp_1',
    pharmacistId: 'u_1',
    pharmacistName: 'Тест Фарм',
    pharmacyId: 'ph_1',
    pharmacyName: 'Аптека 1',
    photoUrl: null,
    parsedSku: 'p_aql_norm_s',
    parsedAmount: 1620,
    parsedCashier: 'Кассир №1',
    parsedAt: '2026-05-14T09:00:00Z',
    ocrScore: 0.74,
    status: 'pending',
    autoApproved: false,
    flagReason: null,
    pendingBonusId: 'pb_1',
    expectedAmount: 1620,
    bonus: 520,
    bonusCredited: 0,
    source: 'photo',
    confirmedByLog: false,
    confirmedByExcel: false,
    reviewer: null,
    reviewedAt: null,
    createdAt: '2026-05-14T09:00:00Z',
    ...over,
  }
}

const SUMMARY: ReconcileSummaryDto = {
  queue: 2,
  moderationRequired: 1,
  flagged: 1,
  approved: 5,
  rejected: 1,
  autoApproved: 4,
}

function setReceipts(list: ReceiptDto[] = [], extra: Record<string, unknown> = {}) {
  hooks.useReceipts.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setReceipts([])
  hooks.useReconcileSummary.mockReturnValue({ data: SUMMARY })
  hooks.useApproveReceipt.mockReturnValue({ mutate: approveMutate, isPending: false })
  hooks.useRejectReceipt.mockReturnValue({ mutate: rejectMutate, isPending: false })
  hooks.useImportExcel.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <ReconcilePage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReconcilePage', () => {
  it('H1 + метрики из summary', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Сверка чеков/i })).toBeInTheDocument()
    // autoApproved=4 в метрике «Авто-одобрено»
    expect(screen.getAllByText('4').length).toBeGreaterThan(0)
  })

  it('Empty когда чеков нет', () => {
    renderPage()
    expect(screen.getByText(/Чеков пока нет/i)).toBeInTheDocument()
  })

  it('рендерит строки чеков', () => {
    setReceipts([mkReceipt({ id: 'rcp_a' }), mkReceipt({ id: 'rcp_b', pharmacistName: 'Другой' })])
    renderPage()
    expect(screen.getByTestId('reconcile-table')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-rcp_a')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-rcp_b')).toBeInTheDocument()
  })

  it('одобрение с подтверждением → useApproveReceipt', async () => {
    setReceipts([mkReceipt({ id: 'rcp_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-approve-rcp_x'))
    expect(approveMutate).toHaveBeenCalledWith('rcp_x', expect.anything())
  })

  it('отклонение с причиной (prompt) → useRejectReceipt', async () => {
    setReceipts([mkReceipt({ id: 'rcp_x' })])
    vi.spyOn(window, 'prompt').mockReturnValue('поддельный')
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-reject-rcp_x'))
    expect(rejectMutate).toHaveBeenCalled()
    expect(rejectMutate.mock.calls[0][0]).toMatchObject({ id: 'rcp_x', reason: 'поддельный' })
  })

  it('approved-чек: действий нет', () => {
    setReceipts([
      mkReceipt({ id: 'rcp_ok', status: 'approved', autoApproved: true, bonusCredited: 520 }),
    ])
    renderPage()
    expect(screen.queryByTestId('receipt-approve-rcp_ok')).not.toBeInTheDocument()
  })

  it('кнопка импорта Excel вызывает useImportExcel при выборе файла', async () => {
    const importMutate = vi.fn()
    hooks.useImportExcel.mockReturnValue({ mutate: importMutate, isPending: false })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByTestId('import-excel')).toBeInTheDocument()
    const input = screen.getByTestId('excel-input') as HTMLInputElement
    const file = new File(['x'], 'sales.xlsx', { type: 'application/vnd.ms-excel' })
    await user.upload(input, file)
    expect(importMutate).toHaveBeenCalled()
  })

  it('чек на ручной проверке (moderation_required) можно одобрить', () => {
    setReceipts([mkReceipt({ id: 'rcp_m', status: 'moderation_required', confirmedByLog: true })])
    renderPage()
    expect(screen.getByTestId('receipt-approve-rcp_m')).toBeInTheDocument()
    // источник «Лог» виден
    expect(screen.getByText('Лог')).toBeInTheDocument()
  })

  it('оба источника подтвердили → чипы Лог + Excel', () => {
    setReceipts([
      mkReceipt({
        id: 'rcp_both',
        status: 'approved',
        confirmedByLog: true,
        confirmedByExcel: true,
      }),
    ])
    renderPage()
    expect(screen.getByText('Лог')).toBeInTheDocument()
    expect(screen.getByText('Excel')).toBeInTheDocument()
  })

  it('error state + Повторить', async () => {
    const refetch = vi.fn()
    setReceipts([], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить очередь/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })

  it('клик по строке открывает drawer с фото и именем товара из каталога', async () => {
    setReceipts([
      mkReceipt({ id: 'rcp_d', photoUrl: 'http://localhost:9000/epharm-receipts/x.jpg' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-rcp_d'))
    expect(screen.getByTestId('receipt-detail')).toBeInTheDocument()
    expect(screen.getByTestId('receipt-photo')).toHaveAttribute(
      'src',
      'http://localhost:9000/epharm-receipts/x.jpg',
    )
    // имя товара резолвится из каталога (p_aql_norm_s → Аквалор Норм спрей)
    expect(screen.getAllByText('Аквалор Норм спрей').length).toBeGreaterThan(0)
  })

  it('drawer без фото показывает заглушку', async () => {
    setReceipts([mkReceipt({ id: 'rcp_np', photoUrl: null })])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-rcp_np'))
    expect(screen.getByTestId('receipt-nophoto')).toBeInTheDocument()
    expect(screen.queryByTestId('receipt-photo')).not.toBeInTheDocument()
  })

  it('approve из drawer вызывает useApproveReceipt', async () => {
    setReceipts([mkReceipt({ id: 'rcp_da' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-rcp_da'))
    await user.click(screen.getByTestId('drawer-approve'))
    expect(approveMutate).toHaveBeenCalledWith('rcp_da', expect.anything())
  })

  it('клик по inline-кнопке approve не открывает drawer (stopPropagation)', async () => {
    setReceipts([mkReceipt({ id: 'rcp_inl' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('receipt-approve-rcp_inl'))
    expect(approveMutate).toHaveBeenCalledWith('rcp_inl', expect.anything())
    expect(screen.queryByTestId('receipt-detail')).not.toBeInTheDocument()
  })
})
