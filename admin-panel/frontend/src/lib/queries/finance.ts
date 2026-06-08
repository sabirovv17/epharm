// Finance — TanStack Query хуки для payout batches.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { PayoutBatchDto, PayoutBatchStatus, PayoutItemDto } from '@/lib/api-types'

export interface BatchListFilter {
  status?: PayoutBatchStatus
}

export const payoutKeys = {
  all: ['payouts'] as const,
  list: (filter?: BatchListFilter) => [...payoutKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...payoutKeys.all, 'detail', id] as const,
  items: (id: string) => [...payoutKeys.all, 'items', id] as const,
}

export function usePayoutBatches(filter: BatchListFilter = {}) {
  return useQuery<PayoutBatchDto[]>({
    queryKey: payoutKeys.list(filter),
    queryFn: () =>
      api.get<PayoutBatchDto[]>('/api/admin/payouts', { params: filter }).then((r) => r.data),
  })
}

export function usePayoutBatch(id: string | null) {
  return useQuery<PayoutBatchDto>({
    queryKey: payoutKeys.detail(id ?? ''),
    queryFn: () => api.get<PayoutBatchDto>(`/api/admin/payouts/${id}`).then((r) => r.data),
    enabled: !!id,
  })
}

export function usePayoutItems(batchId: string | null) {
  return useQuery<PayoutItemDto[]>({
    queryKey: payoutKeys.items(batchId ?? ''),
    queryFn: () =>
      api.get<PayoutItemDto[]>(`/api/admin/payouts/${batchId}/items`).then((r) => r.data),
    enabled: !!batchId,
  })
}

export function useApproveBatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<PayoutBatchDto>(`/api/admin/payouts/${id}/approve`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: payoutKeys.all }),
  })
}

/** Сформировать батч выплат из накопленных бонусов (тот же механизм, что cron 1-го числа). */
export function useGeneratePayout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (period?: string) =>
      api
        .post<PayoutBatchDto>('/api/admin/payouts/generate', null, {
          params: period ? { period } : {},
        })
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: payoutKeys.all }),
  })
}
