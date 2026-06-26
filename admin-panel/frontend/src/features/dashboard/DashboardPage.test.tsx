// Тесты DashboardPage — KPI из real API, топ-списки, loading/error.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { DashboardSummaryDto, RecommendationAnalyticsDto } from '@/lib/api-types'
import DashboardPage from './DashboardPage'

const dashHooks = vi.hoisted(() => ({
  useDashboardSummary: vi.fn(),
  useRecommendationAnalytics: vi.fn(),
}))

vi.mock('@/lib/queries/dashboard', () => dashHooks)

function mkSummary(over: Partial<DashboardSummaryDto> = {}): DashboardSummaryDto {
  return {
    impressions: 1500,
    accepts: 500,
    convRate: 33.3,
    activeRules: 4,
    totalRules: 6,
    paidOut: 800_000,
    pendingPayout: 300_000,
    pilotPharmacies: 8,
    avgLiftPct: 12.4,
    topRules: [
      { id: 'r_1', label: 'Аквамарис', convRate: 40, accepts: 400 },
      { id: 'r_2', label: 'Линекс', convRate: 20, accepts: 100 },
    ],
    topPharmacies: [{ id: 'ph_1', name: 'Аптека Лидер', city: 'Алматы', rulesAccepted: 300 }],
    topProducts: [{ id: 'p_1', name: 'Аквамарис', revenue: 500_000 }],
    ...over,
  }
}

function setSummary(data: DashboardSummaryDto | undefined, extra: Record<string, unknown> = {}) {
  dashHooks.useDashboardSummary.mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

function mkAnalytics(over: Partial<RecommendationAnalyticsDto> = {}): RecommendationAnalyticsDto {
  return {
    windowDays: 90,
    shown: 2,
    converted: 1,
    convRate: 50,
    convertedUnder2m: 1,
    avgSecondsToSale: 65,
    medianSecondsToSale: 65,
    attributedRevenue: 4500,
    buckets: [],
    events: [
      {
        id: 'rec_1',
        shownAt: '2026-06-26T10:00:00Z',
        kind: 'substitution',
        ruleId: 'r_1',
        triggerName: 'Bioderma',
        recommendName: 'SelfieLab Zen',
        recommendSku: 'p_zen',
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 4500,
        converted: true,
        soldAt: '2026-06-26T10:01:05Z',
        secondsToSale: 65,
      },
      {
        id: 'rec_2',
        shownAt: '2026-06-26T09:00:00Z',
        kind: 'crosssell',
        ruleId: 'r_2',
        triggerName: null,
        recommendName: 'Хьюмер аспиратор',
        recommendSku: 'p_hum',
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 3200,
        converted: false,
        soldAt: null,
        secondsToSale: null,
      },
    ],
    ...over,
  }
}

function setAnalytics(
  data: RecommendationAnalyticsDto | undefined,
  extra: Record<string, unknown> = {},
) {
  dashHooks.useRecommendationAnalytics.mockReturnValue({
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
  setAnalytics(mkAnalytics())
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <DashboardPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DashboardPage — рендер', () => {
  it('H1 + 4 KPI из API', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { level: 1, name: /Дашборд аналитики/i }),
    ).toBeInTheDocument()
    expect(screen.getByText('Показано рекомендаций')).toBeInTheDocument()
    expect(screen.getByText('33.3% конверсия')).toBeInTheDocument()
    expect(screen.getByText('из 6')).toBeInTheDocument()
  })

  it('топ-3 списка показывают реальные строки', () => {
    renderPage()
    expect(screen.getByTestId('dash-top-rules-r_1')).toBeInTheDocument()
    expect(screen.getByTestId('dash-top-pharmacies-ph_1')).toBeInTheDocument()
    expect(screen.getByTestId('dash-top-products-p_1')).toBeInTheDocument()
    expect(screen.getByText('Аптека Лидер')).toBeInTheDocument()
  })

  it('time-series блоки — Empty (данных нет)', () => {
    renderPage()
    expect(screen.getByText(/Недостаточно данных/i)).toBeInTheDocument()
    expect(screen.getByText(/Нет активности/i)).toBeInTheDocument()
  })
})

describe('DashboardPage — пустые топ-списки', () => {
  it('Empty в каждом топе когда массив пуст', () => {
    setSummary(mkSummary({ topRules: [], topPharmacies: [], topProducts: [] }))
    renderPage()
    expect(screen.getAllByText(/Нет данных/i).length).toBeGreaterThanOrEqual(3)
  })
})

describe('DashboardPage — Показано рекомендаций (V032)', () => {
  it('KPI конверсии + строки лога с резолвом имён', () => {
    renderPage()
    expect(screen.getByTestId('recan-kpis')).toBeInTheDocument()
    expect(screen.getByTestId('recan-row-rec_1')).toBeInTheDocument()
    expect(screen.getByText('SelfieLab Zen')).toBeInTheDocument()
    expect(screen.getByText('Bioderma')).toBeInTheDocument()
    // проданная рекомендация → chip «Продано · 1 мин 5 с» (65 сек)
    expect(screen.getByText(/Продано · 1 мин 5 с/)).toBeInTheDocument()
  })

  it('пустой период → Empty', () => {
    setAnalytics(mkAnalytics({ events: [], shown: 0, converted: 0 }))
    renderPage()
    expect(screen.getByText(/Пока нет показанных рекомендаций/i)).toBeInTheDocument()
  })
})

describe('DashboardPage — loading/error', () => {
  it('isLoading без data — метрики с нулями + «загрузка…»', () => {
    setSummary(undefined, { isLoading: true })
    renderPage()
    expect(screen.getByText(/загрузка…/i)).toBeInTheDocument()
  })

  it('isError без data — баннер + Повторить', async () => {
    const refetch = vi.fn()
    setSummary(undefined, { isError: true, error: new Error('boom'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить сводку/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})
