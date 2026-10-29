import { api } from '@/lib/api'

export interface MerchBranch {
  id: string
  organizationId: string
  organizationName: string
  code: string
  name: string
  address: string
  city: string
  division: string | null
  active: boolean
  epharmPharmacyId: string | null
  source: { system: string; origin: string; id: string }
}
export interface MerchBranchPage {
  items: MerchBranch[]
  total: number
  limit: number
  offset: number
}
export interface MerchInventory {
  branch: MerchBranch
  containers: {
    id: string
    code: string
    name: string
    type: string
    category: string | null
    position: number
    active: boolean
  }[]
  shelves: {
    id: string
    containerId: string | null
    code: string
    category: string
    title: string | null
    type: string
    width: number
    position: number
    active: boolean
    saleMode: string
    baseMonthlyPrice: number | null
    halfMonthlyPrice: number | null
  }[]
}
export type MerchStatus = 'available' | 'blocked' | 'held' | 'booked'
export type BlockReason = 'repair' | 'own_promo' | 'reserve'
export interface MerchCell {
  month: string
  inventoryId: string | null
  status: MerchStatus
  reason: BlockReason | null
  note: string | null
  canEdit: boolean
  placement: {
    status: MerchStatus
    portion: string
    portionLabel: string
    heldUntil: string | null
    hasAdminAssignment: boolean
    items: {
      id: string
      requestId: string
      portion: string
      advertiserName: string
      origin: string
      canRelease: boolean
    }[]
  } | null
}
export interface MerchCalendar {
  branch: MerchBranch
  startMonth: string
  monthCount: number
  months: {
    month: string
    availableCount: number
    blockedCount: number
    heldCount: number
    bookedCount: number
  }[]
  shelves: {
    id: string
    code: string
    category: string
    width: number
    price: number | null
    active: boolean
    months: MerchCell[]
  }[]
  canManage: boolean
  canViewPrices: boolean
}
export interface AvailabilityRequest {
  shelfIds: string[]
  fromMonth: string
  toMonth: string
  action: 'block' | 'unblock'
  reason: BlockReason | null
  note: string | null
}
export interface AvailabilityResult {
  branchId: string
  action: string
  fromMonth: string
  toMonth: string
  targetCount: number
  changedCount: number
  protectedCount: number
  unchangedCount: number
  auditId: string
}

export const inventoryApi = {
  branches: (offset: number, signal: AbortSignal) =>
    api
      .get<MerchBranchPage>('/api/admin/merch/branches', { params: { limit: 50, offset }, signal })
      .then((r) => r.data),
  inventory: (id: string, signal: AbortSignal) =>
    api
      .get<MerchInventory>(`/api/admin/merch/branches/${encodeURIComponent(id)}/inventory`, {
        signal,
      })
      .then((r) => r.data),
  calendar: (id: string, startMonth: string, signal: AbortSignal) =>
    api
      .get<MerchCalendar>(`/api/admin/merch/branches/${encodeURIComponent(id)}/availability`, {
        params: { startMonth, monthCount: 6 },
        signal,
      })
      .then((r) => r.data),
  change: (id: string, request: AvailabilityRequest) =>
    api
      .post<AvailabilityResult>(
        `/api/admin/merch/branches/${encodeURIComponent(id)}/availability`,
        request,
      )
      .then((r) => r.data),
}
