import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  FulfillmentActionRequest,
  FulfillmentAssignRequest,
  FulfillmentDeviceDto,
  FulfillmentFeatureStatusDto,
  FulfillmentOrderDto,
  FulfillmentOrderPageDto,
  FulfillmentPharmacyLinkDto,
  FulfillmentPharmacyLinkRequest,
  FulfillmentStatus,
} from '@/lib/api-types'

export interface FulfillmentOrderFilter {
  pharmacyId?: string
  status?: FulfillmentStatus
  unassigned?: boolean
  offset?: number
  limit?: number
}

export const fulfillmentKeys = {
  all: ['fulfillment'] as const,
  status: () => [...fulfillmentKeys.all, 'status'] as const,
  orders: (filter: FulfillmentOrderFilter) => [...fulfillmentKeys.all, 'orders', filter] as const,
  links: () => [...fulfillmentKeys.all, 'links'] as const,
  devices: () => [...fulfillmentKeys.all, 'devices'] as const,
}

export function useFulfillmentFeatureStatus() {
  return useQuery<FulfillmentFeatureStatusDto>({
    queryKey: fulfillmentKeys.status(),
    queryFn: () =>
      api.get<FulfillmentFeatureStatusDto>('/api/admin/fulfillment/status').then((r) => r.data),
  })
}

export function useFulfillmentOrders(filter: FulfillmentOrderFilter) {
  return useQuery<FulfillmentOrderPageDto>({
    queryKey: fulfillmentKeys.orders(filter),
    queryFn: () =>
      api
        .get<FulfillmentOrderPageDto>('/api/admin/fulfillment/orders', { params: filter })
        .then((r) => r.data),
    refetchInterval: 10_000,
  })
}

export function useFulfillmentAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ orderId, request }: { orderId: string; request: FulfillmentActionRequest }) =>
      api
        .post<FulfillmentOrderDto>(
          `/api/admin/fulfillment/orders/${encodeURIComponent(orderId)}/actions`,
          request,
        )
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: fulfillmentKeys.all }),
  })
}

export function useAssignFulfillmentOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ orderId, request }: { orderId: string; request: FulfillmentAssignRequest }) =>
      api
        .post<FulfillmentOrderDto>(
          `/api/admin/fulfillment/orders/${encodeURIComponent(orderId)}/assign`,
          request,
        )
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: fulfillmentKeys.all }),
  })
}

export function useFulfillmentLinks() {
  return useQuery<FulfillmentPharmacyLinkDto[]>({
    queryKey: fulfillmentKeys.links(),
    queryFn: () =>
      api
        .get<FulfillmentPharmacyLinkDto[]>('/api/admin/fulfillment/pharmacy-links')
        .then((r) => r.data),
  })
}

export function useSaveFulfillmentLink() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (request: FulfillmentPharmacyLinkRequest) =>
      api
        .put<FulfillmentPharmacyLinkDto>('/api/admin/fulfillment/pharmacy-links', request)
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: fulfillmentKeys.all }),
  })
}

export function useFulfillmentDevices() {
  return useQuery<FulfillmentDeviceDto[]>({
    queryKey: fulfillmentKeys.devices(),
    queryFn: () =>
      api.get<FulfillmentDeviceDto[]>('/api/admin/fulfillment/devices').then((r) => r.data),
    refetchInterval: 30_000,
  })
}

export function useRevokeFulfillmentDevice() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete(`/api/admin/fulfillment/devices/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: fulfillmentKeys.devices() }),
  })
}
