// Фармацевты — TanStack Query хуки.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  ActivatePharmacistRequest,
  CreatePharmacistRequest,
  PharmacistDto,
  PharmacistStatus,
  UpdatePharmacistRequest,
} from '@/lib/api-types'

export interface PharmacistListFilter {
  status?: PharmacistStatus
  pharmacyId?: string
}

export const pharmacistKeys = {
  all: ['pharmacists'] as const,
  list: (filter?: PharmacistListFilter) => [...pharmacistKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...pharmacistKeys.all, 'detail', id] as const,
}

export function usePharmacists(filter: PharmacistListFilter = {}) {
  return useQuery<PharmacistDto[]>({
    queryKey: pharmacistKeys.list(filter),
    queryFn: () =>
      api.get<PharmacistDto[]>('/api/admin/pharmacists', { params: filter }).then((r) => r.data),
  })
}

export function usePharmacist(id: string | null) {
  return useQuery<PharmacistDto>({
    queryKey: pharmacistKeys.detail(id ?? ''),
    queryFn: () => api.get<PharmacistDto>(`/api/admin/pharmacists/${id}`).then((r) => r.data),
    enabled: !!id,
  })
}

export function useCreatePharmacist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreatePharmacistRequest) =>
      api.post<PharmacistDto>('/api/admin/pharmacists', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacistKeys.all }),
  })
}

export function useUpdatePharmacist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdatePharmacistRequest }) =>
      api.patch<PharmacistDto>(`/api/admin/pharmacists/${id}`, patch).then((r) => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: pharmacistKeys.all })
      qc.setQueryData(pharmacistKeys.detail(data.id), data)
    },
  })
}

export function useActivatePharmacist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, request }: { id: string; request: ActivatePharmacistRequest }) =>
      api.post<PharmacistDto>(`/api/admin/pharmacists/${id}/activate`, request).then((r) => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: pharmacistKeys.all })
      qc.setQueryData(pharmacistKeys.detail(data.id), data)
    },
  })
}

export function useBlockPharmacist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<PharmacistDto>(`/api/admin/pharmacists/${id}/block`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacistKeys.all }),
  })
}

export function useUnblockPharmacist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<PharmacistDto>(`/api/admin/pharmacists/${id}/unblock`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: pharmacistKeys.all }),
  })
}
