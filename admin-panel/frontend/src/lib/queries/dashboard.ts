// Dashboard — TanStack Query хук для read-only сводки KPI (Этап 3.6).

import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { DashboardSummaryDto, RecommendationAnalyticsDto } from '@/lib/api-types'

export const dashboardKeys = {
  all: ['dashboard'] as const,
  summary: () => [...dashboardKeys.all, 'summary'] as const,
  recommendations: (page: number, size: number) =>
    [...dashboardKeys.all, 'recommendations', page, size] as const,
}

export function useDashboardSummary() {
  return useQuery<DashboardSummaryDto>({
    queryKey: dashboardKeys.summary(),
    queryFn: () => api.get<DashboardSummaryDto>('/api/admin/dashboard/summary').then((r) => r.data),
  })
}

/** Аналитика показ→продажа + постраничный журнал показов и продаж (V032/V037). */
export function useRecommendationAnalytics(page = 0, size = 50) {
  return useQuery<RecommendationAnalyticsDto>({
    queryKey: dashboardKeys.recommendations(page, size),
    queryFn: () =>
      api
        .get<RecommendationAnalyticsDto>('/api/admin/dashboard/recommendations', {
          params: { page, size },
        })
        .then((r) => r.data),
    placeholderData: keepPreviousData,
    // Авто-обновление: новые показы/продажи с кассы появляются в разделе без ручного F5.
    refetchInterval: 15_000,
  })
}
