// Каталог продуктов — read-only TanStack Query хуки.
// Используются в RulesPage (productById), RuleBuilder (выбор товара/бренда),
// CreateRuleModal (выбор recommend-продукта).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  BrandDto,
  CreateProductRequest,
  MnnGroupDto,
  ProductDto,
  UpdateProductRequest,
} from '@/lib/api-types'

export const catalogKeys = {
  all: ['catalog'] as const,
  products: () => [...catalogKeys.all, 'products'] as const,
  brands: () => [...catalogKeys.all, 'brands'] as const,
  mnnGroups: () => [...catalogKeys.all, 'mnn-groups'] as const,
}

export function useProducts() {
  return useQuery({
    queryKey: catalogKeys.products(),
    queryFn: () => api.get<ProductDto[]>('/api/admin/catalog/products').then((r) => r.data),
    staleTime: 5 * 60 * 1000, // продукты редко меняются — 5 мин кэш
  })
}

export function useBrands() {
  return useQuery({
    queryKey: catalogKeys.brands(),
    queryFn: () => api.get<BrandDto[]>('/api/admin/catalog/brands').then((r) => r.data),
    staleTime: 5 * 60 * 1000,
  })
}

export function useMnnGroups() {
  return useQuery({
    queryKey: catalogKeys.mnnGroups(),
    queryFn: () => api.get<MnnGroupDto[]>('/api/admin/catalog/mnn-groups').then((r) => r.data),
    staleTime: 5 * 60 * 1000,
  })
}

// ─── Мутации (Блок 2 — CRUD-полнота) ───────────────────────────────────────
// Инвалидируем весь catalog-namespace: products + brands + mnn-groups
// агрегируются из одного источника, любое изменение товара затрагивает все три.

export function useCreateProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateProductRequest) =>
      api.post<ProductDto>('/api/admin/catalog/products', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogKeys.all }),
  })
}

export function useUpdateProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateProductRequest }) =>
      api.patch<ProductDto>(`/api/admin/catalog/products/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogKeys.all }),
  })
}

export function useDeleteProduct() {
  const qc = useQueryClient()
  return useMutation({
    // DELETE → 204 No Content (без тела). Бэк отдаёт 409 если товар
    // связан с правилом — ошибка прокидывается в onError у вызывающего.
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/catalog/products/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogKeys.all }),
  })
}

/**
 * Helper для синхронного lookup внутри компонентов. Берёт уже закэшированные
 * продукты из useProducts() и возвращает productById. Используется как замена
 * старого fixtures.productById, но требует загруженного списка.
 */
export function buildProductIndex(products: ProductDto[] | undefined) {
  const index = new Map<string, ProductDto>()
  products?.forEach((p) => index.set(p.id, p))
  return (id: string | undefined): ProductDto | undefined => (id ? index.get(id) : undefined)
}

/**
 * React-hook вариант для использования в компонентах: возвращает
 * `(id) => Product | undefined` с актуальным кэшем. Перед загрузкой —
 * возвращает функцию, отдающую `undefined`.
 */
export function useProductLookup(): (id: string | undefined) => ProductDto | undefined {
  const { data } = useProducts()
  return buildProductIndex(data)
}
