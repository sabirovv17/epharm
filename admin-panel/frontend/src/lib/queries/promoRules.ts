// Campaign rules (T2) — авторинг правил замены/кросс-селла из кампании.
// GET /api/admin/promo/{id}/rules → PromoRulesViewDto
// PUT /api/admin/promo/{id}/rules (body PromoRulesConfigDto) → PromoRulesViewDto
// Сохранение перезаписывает кэш detail + инвалидирует ключ — счётчики (active/total)
// обновляются без локального state.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { PromoRulesConfigDto, PromoRulesViewDto } from '@/lib/api-types'

export const promoRulesKeys = {
  all: ['promo-rules'] as const,
  detail: (promoId: string) => [...promoRulesKeys.all, promoId] as const,
}

export function usePromoRules(promoId: string | null) {
  return useQuery<PromoRulesViewDto>({
    queryKey: promoRulesKeys.detail(promoId ?? ''),
    queryFn: () =>
      api.get<PromoRulesViewDto>(`/api/admin/promo/${promoId}/rules`).then((r) => r.data),
    enabled: !!promoId,
  })
}

export function useSavePromoRules() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ promoId, config }: { promoId: string; config: PromoRulesConfigDto }) =>
      api.put<PromoRulesViewDto>(`/api/admin/promo/${promoId}/rules`, config).then((r) => r.data),
    onSuccess: (data) => {
      qc.setQueryData(promoRulesKeys.detail(data.promoId), data)
      qc.invalidateQueries({ queryKey: promoRulesKeys.detail(data.promoId) })
      // Правила кампании влияют на общий Rules Engine — обновим и его список.
      qc.invalidateQueries({ queryKey: ['rules'] })
    },
  })
}
