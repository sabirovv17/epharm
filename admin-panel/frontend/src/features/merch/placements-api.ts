export type PlacementStatus = 'draft' | 'pending' | 'approved' | 'rejected' | 'cancelled'
export type PlacementAction = 'submit' | 'approve' | 'reject' | 'cancel'
export type Portion = 'whole' | 'left' | 'right'
export interface PlaceSelection {
  shelfId: string
  portion: Portion
}
export interface Placement {
  id: string
  producerId: string | null
  producerName: string | null
  advertiserName: string | null
  placementMonth: string
  status: PlacementStatus
  origin: string
  revision: number
  totalAmount: number | null
  heldUntil: string | null
  reservationState: string
  reservationBusinessDays: number
  reservationTimezone: string
  reviewNote: string | null
  note: string | null
  items: {
    id: string
    shelfId: string
    branchId: string
    shelfCode: string
    placementMonth: string
    portion: Portion
    status: PlacementStatus
    monthlyPrice: number | null
  }[]
  canEdit: boolean
  canSubmit: boolean
  canApprove: boolean
  canReject: boolean
  canCancel: boolean
  createdAt: string
  updatedAt: string
  producerBindingStatus: 'verified' | 'unresolved'
  canBindProducer?: boolean
}
export interface PlacementPage {
  items: Placement[]
  total: number
  limit: number
  offset: number
}
export interface ProducerOption {
  id: string
  name: string | null
  active: boolean
  profileComplete?: boolean
}
export interface BranchOption {
  id: string
  name: string
  address: string
  city: string
}
export interface PlaceOffer {
  price: number | null
  available: boolean
}
export interface AvailablePlace {
  shelfId: string
  code: string
  saleMode: string
  whole: PlaceOffer
  left: PlaceOffer | null
  right: PlaceOffer | null
}
export interface CatalogBranch extends BranchOption {
  canCreate: boolean
  canAssign: boolean
  canViewPrices: boolean
  items: AvailablePlace[]
}
export interface PlacementCatalog {
  producerId: string
  producerName: string | null
  month: string
  branches: CatalogBranch[]
  total: number
  limit: number
  offset: number
  canCreate: boolean
  canAssign: boolean
}
export interface DraftBody {
  producerId?: string
  placementMonth: string
  items: PlaceSelection[]
  note: string | null
}
export interface PlacementGateway {
  realm: 'admin' | 'producer'
  list(
    filter: { producerId?: string; status?: string; offset: number },
    signal: AbortSignal,
  ): Promise<PlacementPage>
  view(id: string, signal: AbortSignal): Promise<Placement>
  create(body: DraftBody, assignment?: boolean): Promise<Placement>
  save(
    id: string,
    expectedRevision: number,
    items: PlaceSelection[],
    note: string | null,
  ): Promise<Placement>
  transition(
    id: string,
    action: PlacementAction,
    expectedRevision: number,
    reason: string | null,
  ): Promise<Placement>
  producers(signal: AbortSignal): Promise<ProducerOption[]>
  catalog(
    producerId: string,
    month: string,
    offset: number,
    signal: AbortSignal,
    branchId?: string,
  ): Promise<PlacementCatalog>
}
type Requester = <T>(path: string, init?: RequestInit) => Promise<T>
const query = (values: Record<string, string | number | undefined>) => {
  const params = new URLSearchParams()
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value))
  })
  return `?${params}`
}

/** All external identity comes from the producer principal. It is never a body selector. */
export function placementGateway(
  realm: 'admin' | 'producer',
  request: Requester,
): PlacementGateway {
  const base = realm === 'admin' ? '/api/admin/merch' : '/api/producer'
  return {
    realm,
    list: (filter, signal) =>
      request(
        `${base}/placements${query({ ...filter, limit: 50, producerId: realm === 'admin' ? filter.producerId : undefined })}`,
        { signal },
      ),
    view: (id, signal) => request(`${base}/placements/${encodeURIComponent(id)}`, { signal }),
    create: (body, assignment = false) => {
      if (realm === 'producer' && assignment)
        return Promise.reject(new Error('Действие доступно только HQ.'))
      const payload =
        realm === 'admin'
          ? body
          : { placementMonth: body.placementMonth, items: body.items, note: body.note }
      return request(`${base}/${assignment ? 'admin-assignments' : 'placements'}`, {
        method: 'POST',
        body: JSON.stringify(payload),
      })
    },
    save: (id, expectedRevision, items, note) =>
      request(`${base}/placements/${encodeURIComponent(id)}/draft`, {
        method: 'PUT',
        body: JSON.stringify({ expectedRevision, items, note }),
      }),
    transition: (id, action, expectedRevision, reason) => {
      if (realm === 'producer' && !['submit', 'cancel'].includes(action))
        return Promise.reject(new Error('Действие доступно только HQ.'))
      return request(`${base}/placements/${encodeURIComponent(id)}/${action}`, {
        method: 'POST',
        body: JSON.stringify({ expectedRevision, reason }),
      })
    },
    producers: (signal) =>
      realm === 'admin' ? request(`${base}/placements/producers`, { signal }) : Promise.resolve([]),
    catalog: async (producerId, month, offset, signal, branchId) => {
      if (realm === 'admin')
        return request(
          `${base}/placements/catalog${query({ producerId, month, limit: 50, offset })}`,
          { signal },
        )
      const page = await request<{
        items: BranchOption[]
        total: number
        limit: number
        offset: number
      }>(`${base}/branches${query({ limit: 50, offset })}`, { signal })
      const branches = await Promise.all(
        page.items.map(async (branch) => {
          if (branch.id !== branchId)
            return { ...branch, canCreate: true, canAssign: false, canViewPrices: true, items: [] }
          const availability = await request<{ items: AvailablePlace[] }>(
            `${base}/branches/${encodeURIComponent(branch.id)}/availability${query({ month })}`,
            { signal },
          )
          return {
            ...branch,
            canCreate: true,
            canAssign: false,
            canViewPrices: true,
            items: availability.items,
          }
        }),
      )
      return {
        producerId,
        producerName: '',
        month,
        ...page,
        branches,
        canCreate: true,
        canAssign: false,
      }
    },
  }
}
