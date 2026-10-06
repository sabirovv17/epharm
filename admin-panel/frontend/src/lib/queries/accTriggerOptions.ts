import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { AccTriggerOptionsDto, PromoTriggerKind } from '@/lib/api-types'

type TaxonomyKind = Exclude<PromoTriggerKind, 'product'>

export function useAccTriggerOptions(kind: TaxonomyKind | null, q: string) {
  return useQuery<AccTriggerOptionsDto>({
    queryKey: ['acc-trigger-options', kind, q],
    queryFn: () =>
      api
        .get<AccTriggerOptionsDto>('/api/admin/catalog/trigger-options', {
          params: { kind, q: q || undefined },
        })
        .then((response) => response.data),
    enabled: kind !== null,
    staleTime: 60_000,
    retry: 1,
  })
}
