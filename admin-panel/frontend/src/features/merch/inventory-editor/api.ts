import { api } from '@/lib/api'
import type { MerchBranch, MerchInventory } from '../inventory-api'

export interface Organization {
  id: string
  revision: number
  name: string
  legalName: string
  bin: string | null
  active: boolean
}
export interface FloorObject {
  id: string
  type: string
  label: string
  x: number
  y: number
  width: number
  depth: number
  orientation: string
}
export interface CabinetPosition {
  cabinetCode: string
  x: number
  y: number
  width: number
  depth: number
  orientation: string
  zone: string
}
export interface FloorPlan {
  version: number
  room: { width: number; depth: number }
  fixtures: FloorObject[]
  cabinetPositions: CabinetPosition[]
}
export interface EditorPhoto {
  id: string
  shelfId: string | null
  angle: string
  caption: string
  mimeType: string
  archived: boolean
}
export interface BranchTraffic {
  otsPerDay: number | null
  vehicleTrafficPerDay: number | null
  pedestrianTrafficPerDay: number | null
  source: string | null
  measuredAt: string | null
}
export interface InventoryEditorState {
  branch: MerchBranch
  revision: number
  latitude: number | null
  longitude: number | null
  containers: MerchInventory['containers']
  shelves: { shelf: MerchInventory['shelves'][number]; maxProducts: number }[]
  monthlyPrices: { shelfId: string; month: string; status: string; price: number | null }[]
  photos: EditorPhoto[]
  floorPlan: FloorPlan | null
  floorPlanDraft: FloorPlan | null
  floorPlanWarning: string | null
  publishedAt: string | null
  canEditStructure: boolean
  canEditPassport: boolean
  canConfigurePrices: boolean
  canManageOrganizations: boolean
  traffic?: BranchTraffic
}
export interface BranchInput {
  organizationId: string
  code: string
  name: string
  address: string
  city: string
  division: string | null
  latitude: number | null
  longitude: number | null
  active: boolean
  traffic?: BranchTraffic
}
export interface ContainerInput {
  code: string
  name: string
  type: string
  category: string | null
  position: number
  active: boolean
}
export interface ShelfInput {
  containerId: string | null
  code: string
  title: string | null
  category: string
  type: string
  width: number
  position: number
  maxProducts: number
  active: boolean
  initialPrice?: number
}
export interface PriceInput {
  shelfIds: string[]
  fromMonth: string
  toMonth: string
  price: number
  saleMode: string | null
  halfPrice: number | null
  applyFuture: boolean
}
export interface PriceResult {
  revision: number
  changedCount: number
  protectedCount: number
}
const base = (id: string) => `/api/admin/merch/branches/${encodeURIComponent(id)}/editor`
const json = <T>(path: string, body: unknown, method: 'post' | 'put' = 'post') =>
  api[method]<T>(path, body).then((r) => r.data)
export const inventoryEditorApi = {
  get: (id: string, month: string, signal: AbortSignal) =>
    api.get<InventoryEditorState>(base(id), { params: { month }, signal }).then((r) => r.data),
  organizations: (signal: AbortSignal) =>
    api
      .get<Organization[]>('/api/admin/merch/inventory/organizations', { signal })
      .then((r) => r.data),
  organization: (
    id: string | null,
    body: Omit<Organization, 'id' | 'revision'> & { expectedRevision?: number },
  ) =>
    json<Organization>(
      `/api/admin/merch/inventory/organizations${id ? `/${encodeURIComponent(id)}` : ''}`,
      body,
      id ? 'put' : 'post',
    ),
  createBranch: (body: BranchInput) =>
    json<InventoryEditorState>('/api/admin/merch/inventory/branches', body),
  branch: (id: string, revision: number, body: BranchInput) =>
    json<{ revision: number }>(base(id), { ...body, expectedRevision: revision }, 'put'),
  container: (branch: string, id: string | null, revision: number, body: ContainerInput) =>
    json<{ revision: number }>(
      `${base(branch)}/containers${id ? `/${encodeURIComponent(id)}` : ''}`,
      { ...body, expectedRevision: revision },
      id ? 'put' : 'post',
    ),
  shelf: (branch: string, id: string | null, revision: number, body: ShelfInput) =>
    json<{ revision: number }>(
      `${base(branch)}/shelves${id ? `/${encodeURIComponent(id)}` : ''}`,
      { ...body, expectedRevision: revision },
      id ? 'put' : 'post',
    ),
  configure: (
    branch: string,
    id: string,
    revision: number,
    counts: Record<string, number>,
    initialPrice: number | null,
  ) =>
    json<{ revision: number }>(`${base(branch)}/containers/${encodeURIComponent(id)}/configure`, {
      expectedRevision: revision,
      counts,
      initialPrice,
    }),
  prices: (branch: string, revision: number, body: PriceInput) =>
    json<PriceResult>(`${base(branch)}/prices`, { ...body, expectedRevision: revision }),
  floor: (branch: string, revision: number, plan: FloorPlan) =>
    json<{ revision: number }>(
      `${base(branch)}/floor-plan`,
      { expectedRevision: revision, plan },
      'put',
    ),
  publishFloor: (branch: string, revision: number) =>
    json<{ revision: number }>(`${base(branch)}/floor-plan/publish`, {
      expectedRevision: revision,
    }),
  upload: (
    branch: string,
    revision: number,
    file: File,
    angle: string,
    caption: string,
    shelfId: string | null,
  ) => {
    const body = new FormData()
    body.append('file', file)
    return api
      .post<{
        revision: number
      }>(`${base(branch)}/photos`, body, {
        params: { expectedRevision: revision, angle, caption, shelfId: shelfId || undefined },
      })
      .then((r) => r.data)
  },
  archivePhoto: (branch: string, revision: number, photo: string) =>
    json<{ revision: number }>(`${base(branch)}/photos/${encodeURIComponent(photo)}/archive`, {
      expectedRevision: revision,
    }),
  photo: (branch: string, photo: string, signal: AbortSignal) =>
    api
      .get<Blob>(`${base(branch)}/media/${encodeURIComponent(photo)}`, {
        responseType: 'blob',
        signal,
      })
      .then((r) => r.data),
}
