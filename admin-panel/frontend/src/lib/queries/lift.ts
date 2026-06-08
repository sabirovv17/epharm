// Lift — TanStack Query хук для read-only pilot/control аналитики (Этап 3.6).

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { LiftSummaryDto } from '@/lib/api-types'

export const liftKeys = {
  all: ['lift'] as const,
  summary: () => [...liftKeys.all, 'summary'] as const,
}

export function useLiftSummary() {
  return useQuery<LiftSummaryDto>({
    queryKey: liftKeys.summary(),
    queryFn: () => api.get<LiftSummaryDto>('/api/admin/lift').then((r) => r.data),
  })
}
