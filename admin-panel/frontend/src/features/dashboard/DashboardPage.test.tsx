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
    log: [
      {
        id: 'rec_1',
        type: 'show',
        at: '2026-06-26T10:00:00Z',
        title: 'SelfieLab Zen',
        triggerName: 'Супрастин',
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 4500,
        converted: true,
        secondsToSale: 65,
        units: null,
      },
      {
        id: 'sale_1',
        type: 'sale',
        at: '2026-06-26T10:01:05Z',
        title: 'Аквамарис и ещё 1',
        triggerName: null,
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 3200,
        converted: false,
        secondsToSale: 65, // этот чек закрыл показ → длительность видна и на строке продажи
        units: 3, // продано 3 единицы в чеке
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

describe('DashboardPage — Журнал показов и продаж (V032)', () => {
  it('KPI + единый лог: строка показа и строка продажи', () => {
    renderPage()
    expect(screen.getByTestId('recan-kpis')).toBeInTheDocument()
    // показ
    expect(screen.getByTestId('recan-row-rec_1')).toBeInTheDocument()
    expect(screen.getByText('SelfieLab Zen')).toBeInTheDocument() // что рекомендовали
    expect(screen.getByText(/выбрали: Супрастин/)).toBeInTheDocument() // что выбрал покупатель
    // продажа (из второй таблицы) в том же логе
    expect(screen.getByTestId('recan-row-sale_1')).toBeInTheDocument()
    expect(screen.getByText('Аквамарис и ещё 1')).toBeInTheDocument()
    // количество проданных единиц в чеке — «3 шт» (а не двусмысленный «+N»)
    expect(screen.getByTestId('recan-units-sale_1')).toHaveTextContent('3 шт')
    // конвертированный показ → chip «Продано · 1 мин 5 с» (65 сек)
    expect(screen.getByText(/Продано · 1 мин 5 с/)).toBeInTheDocument()
    // на строке продажи — длительность «через 1 мин 5 с после показа»
    expect(screen.getByText(/через 1 мин 5 с после показа/)).toBeInTheDocument()
  })

  it('пустой период → Empty', () => {
    setAnalytics(mkAnalytics({ log: [], shown: 0, converted: 0 }))
    renderPage()
    expect(screen.getByText(/Пока нет событий за период/i)).toBeInTheDocument()
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
