// Reconcile — сверка чеков (ТЗ §3.5). Очередь + ручные действия модератора.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  ExcelImportResultDto,
  ReceiptDto,
  ReceiptStatus,
  ReconcileSummaryDto,
} from '@/lib/api-types'

export const reconcileKeys = {
  all: ['reconcile'] as const,
  list: (status?: ReceiptStatus) => [...reconcileKeys.all, 'list', status ?? 'all'] as const,
  summary: () => [...reconcileKeys.all, 'summary'] as const,
}

export function useReceipts(status?: ReceiptStatus) {
  return useQuery<ReceiptDto[]>({
    queryKey: reconcileKeys.list(status),
    queryFn: () =>
      api
        .get<ReceiptDto[]>('/api/admin/reconcile', { params: status ? { status } : {} })
        .then((r) => r.data),
  })
}

export function useReconcileSummary() {
  return useQuery<ReconcileSummaryDto>({
    queryKey: reconcileKeys.summary(),
    queryFn: () => api.get<ReconcileSummaryDto>('/api/admin/reconcile/summary').then((r) => r.data),
  })
}

export function useApproveReceipt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<ReceiptDto>(`/api/admin/reconcile/${id}/approve`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: reconcileKeys.all }),
  })
}

export function useRejectReceipt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      api.post<ReceiptDto>(`/api/admin/reconcile/${id}/reject`, { reason }).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: reconcileKeys.all }),
  })
}

/** Импорт Excel-выгрузки Стандарт-Н (источник №2). */
export function useImportExcel() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api
        .post<ExcelImportResultDto>('/api/admin/reconcile/import-excel', form)
        .then((r) => r.data)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: reconcileKeys.all }),
  })
}
