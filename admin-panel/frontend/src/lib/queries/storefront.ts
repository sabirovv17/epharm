// Витрина каталога (Medusa) — read-only TanStack Query хук для админки.
// Бэкенд проксирует Medusa Store API: GET /api/admin/storefront/products (admin-JWT).
// Поиск и пагинация — серверные (q/limit/offset).
//
// БАГ-ФИКС (поиск): раньше тут стоял placeholderData: (prev) => prev. При смене
// запроса react-query отдавал ДАННЫЕ ПРЕДЫДУЩЕГО запроса как placeholder, а
// накопитель в ProductSearchList коммитил их как результат нового запроса —
// в выдаче «витамин» висели первые 50 из всех 27975 (Везилют и т.п.), хотя
// Medusa по q=витамин корректно отдаёт 528 релевантных. Убрали placeholderData:
// на время загрузки список пустой (спиннер), после ответа — отфильтрованные.

import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type { StorefrontPageDto, StorefrontProductDetailDto } from '@/lib/api-types'

export const storefrontKeys = {
  all: ['storefront'] as const,
  list: (q: string, offset: number, limit: number) =>
    [...storefrontKeys.all, 'list', q, offset, limit] as const,
  detail: (id: string) => [...storefrontKeys.all, 'detail', id] as const,
}

/**
 * Деталь одного товара витрины (описание + характеристики из Medusa, с наложенными
 * override кампании). Используется на странице кампании, чтобы показать/предзаполнить.
 */
export function useStorefrontProduct(id: string | null) {
  return useQuery<StorefrontProductDetailDto>({
    queryKey: storefrontKeys.detail(id ?? ''),
    queryFn: () =>
      api
        .get<StorefrontProductDetailDto>(`/api/admin/storefront/products/${id}`)
        .then((r) => r.data),
    enabled: !!id,
    staleTime: 60 * 1000,
  })
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
  })
}
