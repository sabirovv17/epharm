// Фармацевты — TanStack Query хуки.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  ActivatePharmacistRequest,
  CreatePharmacistRequest,
  PharmacistDto,
  PharmacistStatus,
  PosmPharmacistMappingDto,
  UnmappedPosmSellerDto,
  UpdatePharmacistRequest,
  UpsertPosmPharmacistMappingRequest,
} from '@/lib/api-types'

export interface PharmacistListFilter {
  status?: PharmacistStatus
  pharmacyId?: string
}

export const pharmacistKeys = {
  all: ['pharmacists'] as const,
  list: (filter?: PharmacistListFilter) => [...pharmacistKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...pharmacistKeys.all, 'detail', id] as const,
  standardNMappings: ['posm', 'pharmacist-mappings'] as const,
  unmappedStandardNSellers: ['posm', 'pharmacist-mappings', 'unmapped'] as const,
}

export function useStandardNPharmacistMappings() {
  return useQuery<PosmPharmacistMappingDto[]>({
    queryKey: pharmacistKeys.standardNMappings,
    queryFn: () =>
      api
        .get<PosmPharmacistMappingDto[]>('/api/admin/posm/pharmacist-mappings')
        .then((r) => r.data),
  })
}

export function useUnmappedStandardNSellers() {
  return useQuery<UnmappedPosmSellerDto[]>({
    queryKey: pharmacistKeys.unmappedStandardNSellers,
    queryFn: () =>
      api
        .get<UnmappedPosmSellerDto[]>('/api/admin/posm/pharmacist-mappings/unmapped')
        .then((r) => r.data),
  })
}

export function useUpsertStandardNPharmacistMapping() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: UpsertPosmPharmacistMappingRequest) =>
      api
        .post<PosmPharmacistMappingDto>('/api/admin/posm/pharmacist-mappings', req)
        .then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: pharmacistKeys.standardNMappings })
      qc.invalidateQueries({ queryKey: pharmacistKeys.unmappedStandardNSellers })
    },
  })
}

export function useRevokeStandardNPharmacistMapping() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete(`/api/admin/posm/pharmacist-mappings/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: pharmacistKeys.standardNMappings })
      qc.invalidateQueries({ queryKey: pharmacistKeys.unmappedStandardNSellers })
    },
  })
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
