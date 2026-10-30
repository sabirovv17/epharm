import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { PromoChangesBundle, PromoChangesViewDto } from '@/lib/api-types'
import { promoKeys } from './promo'
import { promoRulesKeys } from './promoRules'

export const promoChangesKeys = {
  detail: (id: string) => ['promo-changes', id] as const,
}

export function usePromoChanges(id: string | null) {
  return useQuery({
    queryKey: promoChangesKeys.detail(id ?? ''),
    queryFn: ({ signal }) =>
      api
        .get<PromoChangesViewDto>(`/api/admin/promo/${encodeURIComponent(id ?? '')}/changes`, {
          signal,
        })
        .then((response) => response.data),
    enabled: !!id,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    placeholderData: undefined,
    retry: false,
  })
}

export function useSavePromoChanges() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({
      id,
      expectedRevision,
      bundle,
      discard,
    }: {
      id: string
      expectedRevision: number
      bundle?: PromoChangesBundle
      discard?: true
    }) =>
      api
        .put<PromoChangesViewDto>(`/api/admin/promo/${encodeURIComponent(id)}/changes`, {
          expectedRevision,
          ...(discard ? { discard: true } : { bundle }),
        })
        .then((response) => response.data),
    onSuccess: (result) => client.setQueryData(promoChangesKeys.detail(result.promoId), result),
  })
}

export function useApplyPromoChanges() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, expectedRevision }: { id: string; expectedRevision: number }) =>
      api
        .post<PromoChangesViewDto>(`/api/admin/promo/${encodeURIComponent(id)}/changes/apply`, {
          expectedRevision,
        })
        .then((response) => response.data),
    onSuccess: (result) => {
      client.setQueryData(promoChangesKeys.detail(result.promoId), result)
      client.invalidateQueries({ queryKey: promoKeys.all })
      client.invalidateQueries({ queryKey: promoRulesKeys.detail(result.promoId) })
      client.invalidateQueries({ queryKey: ['rules'] })
    },
  })
}
