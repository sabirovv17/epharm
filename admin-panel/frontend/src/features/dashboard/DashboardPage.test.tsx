// Тесты DashboardPage — KPI из real API, топ-списки, loading/error.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
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
    page: 0,
    pageSize: 50,
    totalElements: 3,
    totalPages: 1,
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
        saleId: null,
      },
      // Один чек (sale_1) = две позиции = две строки журнала, связанные saleId.
      {
        id: 'sale_1#0',
        type: 'sale',
        at: '2026-06-26T10:01:05Z',
        title: 'Аквамарис',
        triggerName: null,
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        pharmacistSource: 'standardn_name_match',
        amount: 2100,
        converted: false,
        secondsToSale: 65, // позиция рекомендованного товара — чип «через … после показа»
        units: 2,
        saleId: 'sale_1',
      },
      {
        id: 'sale_1#1',
        type: 'sale',
        at: '2026-06-26T10:01:05Z',
        title: 'Цитрамон-П табл №10',
        triggerName: null,
        pharmacyId: 'ph_1',
        pharmacyName: 'Аптека Центр',
        pharmacistId: 'u_1',
        pharmacistName: 'Иван',
        amount: 1100,
        converted: false,
        secondsToSale: null,
        units: 1,
        saleId: 'sale_1',
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
    // продажа: каждая позиция чека — отдельной строкой со своим кол-вом и суммой
    const pos0 = screen.getByTestId('recan-row-sale_1#0')
    const pos1 = screen.getByTestId('recan-row-sale_1#1')
    expect(screen.getByText('Фармацевт / продавец')).toBeInTheDocument()
    expect(within(pos0).getByText('Аквамарис')).toBeInTheDocument()
    expect(within(pos0).getByText('Иван')).toBeInTheDocument()
    expect(within(pos0).getByText('Сопоставлено по ФИО Standard-N')).toBeInTheDocument()
    expect(within(pos1).getByText('Цитрамон-П табл №10')).toBeInTheDocument()
    expect(screen.getByTestId('recan-units-sale_1#0')).toHaveTextContent('2 шт')
    expect(screen.getByTestId('recan-units-sale_1#1')).toHaveTextContent('1 шт')
    // конвертированный показ → chip «Продано · 1 мин 5 с» (65 сек)
    expect(screen.getByText(/Продано · 1 мин 5 с/)).toBeInTheDocument()
    // чип «через 1 мин 5 с после показа» — ровно на ОДНОЙ позиции (рекомендованный товар)
    expect(screen.getAllByText(/через 1 мин 5 с после показа/)).toHaveLength(1)
  })

  it('пустой период → Empty', () => {
    setAnalytics(mkAnalytics({ log: [], shown: 0, converted: 0, totalElements: 0, totalPages: 0 }))
    renderPage()
    expect(screen.getByText(/Пока нет событий за период/i)).toBeInTheDocument()
  })

  it('переключает страницы и показывает полный размер журнала', async () => {
    setAnalytics(mkAnalytics({ totalElements: 123, totalPages: 3 }))
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByTestId('recan-pagination')).toHaveTextContent('Показано 1–50 из 123')
    await user.click(screen.getByRole('button', { name: 'Перейти на страницу 2' }))
    expect(dashHooks.useRecommendationAnalytics).toHaveBeenLastCalledWith(1, 50)
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
