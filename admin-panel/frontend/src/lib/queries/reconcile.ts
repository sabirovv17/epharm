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

// Чеки от фармацевтов прилетают постоянно, очередь живая. Раньше она «висела» до 5 мин:
// бэкенд пишет чек сразу, но persisted localStorage-кэш отдавал старый список, а refetch
// не триггерился (staleTime 30с + refetchOnWindowFocus=false глобально + gcTime 5 мин).
// Решение: persisted-кэш показываем мгновенно (нет «прыжка» в пустоту), но всегда делаем
// фоновый refetch на маунте, короткий поллинг и refetch при возврате на вкладку.
const LIVE_QUEUE_OPTS = {
  staleTime: 0,
  refetchOnMount: 'always',
  refetchOnWindowFocus: true,
  refetchInterval: 20_000,
} as const

export function useReceipts(status?: ReceiptStatus) {
  return useQuery<ReceiptDto[]>({
    queryKey: reconcileKeys.list(status),
    queryFn: () =>
      api
        .get<ReceiptDto[]>('/api/admin/reconcile', { params: status ? { status } : {} })
        .then((r) => r.data),
    ...LIVE_QUEUE_OPTS,
  })
}

export function useReconcileSummary() {
  return useQuery<ReconcileSummaryDto>({
    queryKey: reconcileKeys.summary(),
    queryFn: () => api.get<ReconcileSummaryDto>('/api/admin/reconcile/summary').then((r) => r.data),
    ...LIVE_QUEUE_OPTS,
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
