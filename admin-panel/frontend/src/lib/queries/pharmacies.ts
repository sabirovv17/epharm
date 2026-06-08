// Аптеки + сети — TanStack Query хуки.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  ChainDto,
  CreateChainRequest,
  CreatePharmacyRequest,
  PharmacyDto,
  PharmacyGroup,
  UpdateChainRequest,
  UpdatePharmacyRequest,
} from '@/lib/api-types'

export interface PharmacyListFilter {
  group?: PharmacyGroup
  chainId?: string
}

export const pharmacyKeys = {
  all: ['pharmacies'] as const,
  list: (filter?: PharmacyListFilter) => [...pharmacyKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...pharmacyKeys.all, 'detail', id] as const,
  chains: () => [...pharmacyKeys.all, 'chains'] as const,
}

export function useChains() {
  return useQuery<ChainDto[]>({
    queryKey: pharmacyKeys.chains(),
    queryFn: () => api.get<ChainDto[]>('/api/admin/pharmacies/chains').then((r) => r.data),
    staleTime: 5 * 60 * 1000,
  })
}

export function usePharmacies(filter: PharmacyListFilter = {}) {
  return useQuery<PharmacyDto[]>({
    queryKey: pharmacyKeys.list(filter),
    queryFn: () =>
      api.get<PharmacyDto[]>('/api/admin/pharmacies', { params: filter }).then((r) => r.data),
  })
}

export function usePharmacy(id: string | null) {
  return useQuery<PharmacyDto>({
    queryKey: pharmacyKeys.detail(id ?? ''),
    queryFn: () => api.get<PharmacyDto>(`/api/admin/pharmacies/${id}`).then((r) => r.data),
    enabled: !!id,
  })
}

export function useCreatePharmacy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreatePharmacyRequest) =>
      api.post<PharmacyDto>('/api/admin/pharmacies', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacyKeys.all }),
  })
}

export function useUpdatePharmacy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdatePharmacyRequest }) =>
      api.patch<PharmacyDto>(`/api/admin/pharmacies/${id}`, patch).then((r) => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: pharmacyKeys.all })
      qc.setQueryData(pharmacyKeys.detail(data.id), data)
    },
  })
}

export function useDeletePharmacy() {
  const qc = useQueryClient()
  return useMutation({
    // 204 No Content. Бэк отдаёт 409 если у аптеки есть фармацевты —
    // вызывающий ловит ошибку через onError.
    mutationFn: (id: string) => api.delete<void>(`/api/admin/pharmacies/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacyKeys.all }),
  })
}

// ─── Сети (chains) — мутации (Блок 2). Управляющего UI пока нет (сети
// показываются как label/фильтр), но контракт зеркалим полностью. ───────────

export function useCreateChain() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateChainRequest) =>
      api.post<ChainDto>('/api/admin/pharmacies/chains', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacyKeys.all }),
  })
}

export function useUpdateChain() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateChainRequest }) =>
      api.patch<ChainDto>(`/api/admin/pharmacies/chains/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacyKeys.all }),
  })
}

export function useDeleteChain() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/pharmacies/chains/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacyKeys.all }),
  })
}
