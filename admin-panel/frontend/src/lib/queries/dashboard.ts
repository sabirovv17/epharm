// Dashboard — TanStack Query хук для read-only сводки KPI (Этап 3.6).

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { DashboardSummaryDto } from '@/lib/api-types'

export const dashboardKeys = {
  all: ['dashboard'] as const,
  summary: () => [...dashboardKeys.all, 'summary'] as const,
}

export function useDashboardSummary() {
  return useQuery<DashboardSummaryDto>({
    queryKey: dashboardKeys.summary(),
    queryFn: () => api.get<DashboardSummaryDto>('/api/admin/dashboard/summary').then((r) => r.data),
  })
}
