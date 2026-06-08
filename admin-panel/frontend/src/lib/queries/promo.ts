// Promo-кампании — TanStack Query хуки.
// Каждая mutation инвалидирует список — таблица обновляется без локального state.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { CreatePromoRequest, PromoDto, PromoStatus, UpdatePromoRequest } from '@/lib/api-types'

export interface PromoListFilter {
  status?: PromoStatus
}

export const promoKeys = {
  all: ['promo'] as const,
  list: (filter?: PromoListFilter) => [...promoKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...promoKeys.all, 'detail', id] as const,
}

export function usePromos(filter: PromoListFilter = {}) {
  return useQuery<PromoDto[]>({
    queryKey: promoKeys.list(filter),
    queryFn: () => api.get<PromoDto[]>('/api/admin/promo', { params: filter }).then((r) => r.data),
  })
}

export function usePromo(id: string | null) {
  return useQuery<PromoDto>({
    queryKey: promoKeys.detail(id ?? ''),
    queryFn: () => api.get<PromoDto>(`/api/admin/promo/${id}`).then((r) => r.data),
    enabled: !!id,
  })
}

export function useCreatePromo() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreatePromoRequest) =>
      api.post<PromoDto>('/api/admin/promo', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: promoKeys.all }),
  })
}

export function useUpdatePromo() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdatePromoRequest }) =>
      api.patch<PromoDto>(`/api/admin/promo/${id}`, patch).then((r) => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: promoKeys.all })
      qc.setQueryData(promoKeys.detail(data.id), data)
    },
  })
}

export function useArchivePromo() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<PromoDto>(`/api/admin/promo/${id}/archive`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: promoKeys.all }),
  })
}

export function useRestorePromo() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<PromoDto>(`/api/admin/promo/${id}/restore`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: promoKeys.all }),
  })
}
