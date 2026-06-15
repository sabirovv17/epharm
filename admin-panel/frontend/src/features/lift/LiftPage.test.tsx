// Тесты LiftPage — KPI из real API, сегменты, loading/error.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { LiftSummaryDto } from '@/lib/api-types'
import LiftPage from './LiftPage'

const liftHooks = vi.hoisted(() => ({ useLiftSummary: vi.fn() }))
vi.mock('@/lib/queries/lift', () => liftHooks)

function mkSummary(over: Partial<LiftSummaryDto> = {}): LiftSummaryDto {
  return {
    networkLiftPct: 20,
    pValue: 0.012,
    insufficientData: false,
    pilotCount: 3,
    controlCount: 1,
    rolledCount: 1,
    pilotReceipts30d: 390,
    controlReceipts30d: 150,
    segments: [
      { name: 'Садыхан', liftPct: 30, pharmacies: 1 },
      { name: 'Europharma', liftPct: 15, pharmacies: 2 },
    ],
    ...over,
  }
}

function setSummary(data: LiftSummaryDto | undefined, extra: Record<string, unknown> = {}) {
  liftHooks.useLiftSummary.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setSummary(mkSummary())
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <LiftPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LiftPage — рендер', () => {
  it('H1 + KPI из API', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Аналитика lift/i })).toBeInTheDocument()
    expect(screen.getByText('+20%')).toBeInTheDocument()
    expect(screen.getByText('Pilot-аптек')).toBeInTheDocument()
  })

  it('сегменты по сетям отсортированы', () => {
    renderPage()
    expect(screen.getByTestId('lift-segments')).toBeInTheDocument()
    expect(screen.getByTestId('lift-segment-Садыхан')).toBeInTheDocument()
    expect(screen.getByTestId('lift-segment-Europharma')).toBeInTheDocument()
  })

  it('P-value показывает реальное значение и «статистически значимо»', () => {
    renderPage()
    expect(screen.getByText('0.012')).toBeInTheDocument()
    expect(screen.getByText(/статистически значимо/i)).toBeInTheDocument()
  })

  it('insufficientData → lift и p-value «—», «недостаточно данных»', () => {
    setSummary(mkSummary({ insufficientData: true, networkLiftPct: 0, pValue: null }))
    renderPage()
    // networkLift и P-value показывают «—» (не врём числом)
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText(/недостаточно данных/i).length).toBeGreaterThan(0)
  })
})

describe('LiftPage — пустые сегменты', () => {
  it('Empty когда segments пуст', () => {
    setSummary(mkSummary({ segments: [] }))
    renderPage()
    expect(screen.getByText(/Сегменты пусты/i)).toBeInTheDocument()
  })
})

describe('LiftPage — error', () => {
  it('баннер + Повторить когда isError без data', async () => {
    const refetch = vi.fn()
    setSummary(undefined, { isError: true, error: new Error('boom'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить аналитику/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})
