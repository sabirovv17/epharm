// Dashboard — TanStack Query хук для read-only сводки KPI (Этап 3.6).

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { DashboardSummaryDto, RecommendationAnalyticsDto } from '@/lib/api-types'

export const dashboardKeys = {
  all: ['dashboard'] as const,
  summary: () => [...dashboardKeys.all, 'summary'] as const,
  recommendations: () => [...dashboardKeys.all, 'recommendations'] as const,
}

export function useDashboardSummary() {
  return useQuery<DashboardSummaryDto>({
    queryKey: dashboardKeys.summary(),
    queryFn: () => api.get<DashboardSummaryDto>('/api/admin/dashboard/summary').then((r) => r.data),
  })
}

/** Аналитика показ→продажа + время до продажи + «лог» последних показов (V032). */
export function useRecommendationAnalytics() {
  return useQuery<RecommendationAnalyticsDto>({
    queryKey: dashboardKeys.recommendations(),
    queryFn: () =>
      api
        .get<RecommendationAnalyticsDto>('/api/admin/dashboard/recommendations')
        .then((r) => r.data),
    // Авто-обновление: новые показы/продажи с кассы появляются в разделе без ручного F5.
    refetchInterval: 15_000,
  })
}
