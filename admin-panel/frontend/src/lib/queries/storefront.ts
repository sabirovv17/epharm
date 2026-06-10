// Витрина каталога (Medusa) — read-only TanStack Query хук для админки.
// Бэкенд проксирует Medusa Store API: GET /api/admin/storefront/products (admin-JWT).
// Поиск и пагинация — серверные (q/limit/offset). keepPreviousData → плавность.

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { StorefrontPageDto } from '@/lib/api-types'

export const storefrontKeys = {
  all: ['storefront'] as const,
  list: (q: string, offset: number, limit: number) =>
    [...storefrontKeys.all, 'list', q, offset, limit] as const,
}

export function useStorefront(q: string, offset: number, limit = 50) {
  return useQuery<StorefrontPageDto>({
    queryKey: storefrontKeys.list(q, offset, limit),
    queryFn: () =>
      api
        .get<StorefrontPageDto>('/api/admin/storefront/products', {
          params: { q: q || undefined, limit, offset },
        })
        .then((r) => r.data),
    staleTime: 60 * 1000,
    placeholderData: (prev) => prev,
  })
}
