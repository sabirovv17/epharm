import type { Placement, PlacementGateway, Portion } from './placements-api'

export type CommercialKzt = string | number
export interface CommercialProduct {
  id: string
  productName: string
  sku: string | null
  productCategory: string
  productSubcategory: string
  facingCount: number
  activeMonths: string[]
  shelfCategories: string[]
}
export interface CommercialShelf {
  id: string
  shelfId: string
  pharmacyId: string | null
  portion: Portion
  placementMonths: string[]
  products: CommercialProduct[]
  price: CommercialKzt | null
  code?: string | null
  pharmacyName?: string | null
  city?: string | null
  address?: string | null
}
export interface CommercialActivity {
  id: string | null
  productName: string
  sku: string | null
  activityType: 'placement' | 'promotion' | 'training' | 'motivation'
  activeMonths: string[]
  pharmacyCount: number
  facingCount: number
  peopleCount: number
  unitPrice: CommercialKzt | null
  supplierCategory?: string | null
  note: string | null
}
export interface CommercialPlanData {
  title: string
  startMonth: string
  endMonth: string
  budgetLimit: CommercialKzt | null
  status: 'draft' | 'ready' | 'archived'
  notes: string | null
  planningMode: 'shelves' | 'products'
  productBriefs: CommercialProduct[]
  shelfSelection: CommercialShelf[] | null
  extraActivities: CommercialActivity[]
  items: CommercialActivity[]
}
export interface CommercialPlan {
  id: string
  producerId: string
  revision: number
  plan: CommercialPlanData
  requestIds: string[]
  unresolvedRequestIds?: string[]
  monthlyRequests: Placement[]
  copiedFrom: string | null
  estimatedTotal: CommercialKzt | null
  estimateState: string
  canEdit: boolean
  canSubmit: boolean
  canExport: boolean
  canCopy?: boolean
  updatedAt: string
  paymentStatus: 'not_connected'
}
export interface CommercialSettings {
  revision: number
  supplierCategory: string | null
  sixMonthBudget: CommercialKzt | null
  primaryContactRole: string | null
}
export interface CommercialPricing {
  category: string
  label: string
  multiplier: string
  facingMonthPrice: CommercialKzt
  promotionMonthPrice: CommercialKzt
  trainingEventPrice: CommercialKzt
  motivationPersonPrice: CommercialKzt
}
export interface CommercialMember {
  id: string
  revision: number
  fullName: string
  email: string
  position: string | null
  accessRole: 'head' | 'planner' | 'finance' | 'viewer'
  active: boolean
}
export interface CommercialPage {
  items: CommercialPlan[]
  total: number
  limit: number
  offset: number
}
export interface CommercialWorkspace {
  settings: CommercialSettings
  pricing: CommercialPricing | null
  members: CommercialMember[]
  plans: CommercialPage
}
export interface CommercialCsvPreview {
  rows: {
    line: number
    branchCode: string
    shelfCode: string
    month: string | null
    error: string | null
  }[]
  valid: boolean
  placements: CommercialShelf[]
}
export interface CommercialGateway {
  realm: PlacementGateway['realm']
  workspace(signal: AbortSignal): Promise<CommercialWorkspace>
  list(producerId: string | undefined, offset: number, signal: AbortSignal): Promise<CommercialPage>
  view(id: string, signal: AbortSignal): Promise<CommercialPlan>
  save(
    id: string,
    producerId: string,
    expectedRevision: number,
    plan: CommercialPlanData,
  ): Promise<CommercialPlan>
  submit(id: string, expectedRevision: number): Promise<CommercialPlan>
  copy(
    id: string,
    expectedRevision: number,
    newId: string,
    startMonth: string,
  ): Promise<CommercialPlan>
  preview(csv: string, producerId: string): Promise<CommercialCsvPreview>
  settings(value: CommercialSettings): Promise<CommercialSettings>
  member(value: CommercialMember): Promise<CommercialMember>
  categorySettings(
    producerId: string,
    signal: AbortSignal,
  ): Promise<{ settings: CommercialSettings; canConfigureCategory: boolean }>
  pricing(signal: AbortSignal): Promise<CommercialPricing[]>
  setCategory(
    producerId: string,
    expectedRevision: number,
    category: string,
  ): Promise<CommercialSettings>
  download(id: string, signal: AbortSignal, range?: { from: string; to: string }): Promise<Blob>
}
type Requester = <T>(path: string, init?: RequestInit) => Promise<T>
export function commercialGateway(
  realm: PlacementGateway['realm'],
  request: Requester,
  download: (path: string, signal: AbortSignal) => Promise<Blob>,
): CommercialGateway {
  const base = realm === 'producer' ? '/api/producer/commercial' : '/api/admin/merch/commercial'
  const body = (value: unknown, method = 'POST'): RequestInit => ({
    method,
    body: JSON.stringify(value),
  })
  const own = () => {
    if (realm !== 'producer')
      throw new Error('Команда доступна только в собственном кабинете производителя.')
  }
  return {
    realm,
    workspace: (signal) => {
      own()
      return request(`${base}/workspace`, { signal })
    },
    list: (producerId, offset, signal) =>
      request(
        `${base}/plans?limit=50&offset=${offset}${realm === 'admin' && producerId ? `&producerId=${encodeURIComponent(producerId)}` : ''}`,
        { signal },
      ),
    view: (id, signal) => request(`${base}/plans/${encodeURIComponent(id)}`, { signal }),
    save: (id, producerId, expectedRevision, plan) =>
      request(
        `${base}/plans/${encodeURIComponent(id)}${realm === 'admin' ? `?producerId=${encodeURIComponent(producerId)}` : ''}`,
        body({ expectedRevision, plan }, 'PUT'),
      ),
    submit: (id, expectedRevision) =>
      request(`${base}/plans/${encodeURIComponent(id)}/submit`, body({ expectedRevision })),
    copy: (id, expectedRevision, newId, startMonth) =>
      request(
        `${base}/plans/${encodeURIComponent(id)}/copy`,
        body({ expectedRevision, newId, startMonth }),
      ),
    preview: (csv, producerId) =>
      request(
        `${base}/plans/import-preview${realm === 'admin' ? `?producerId=${encodeURIComponent(producerId)}` : ''}`,
        body({ csv }),
      ),
    settings: (value) => {
      own()
      return request(
        `${base}/settings`,
        body(
          {
            expectedRevision: value.revision,
            sixMonthBudget: value.sixMonthBudget,
            primaryContactRole: value.primaryContactRole,
          },
          'PUT',
        ),
      )
    },
    member: (value) => {
      own()
      return request(
        `${base}/members/${encodeURIComponent(value.id)}`,
        body(
          {
            expectedRevision: value.revision,
            fullName: value.fullName,
            email: value.email,
            position: value.position,
            accessRole: value.accessRole,
            active: value.active,
          },
          'PUT',
        ),
      )
    },
    categorySettings: (id, signal) =>
      request(`${base}/producers/${encodeURIComponent(id)}/settings`, { signal }),
    pricing: (signal) => request(`${base}/pricing`, { signal }),
    setCategory: (id, expectedRevision, category) =>
      request(
        `${base}/producers/${encodeURIComponent(id)}/category`,
        body({ expectedRevision, category }, 'PUT'),
      ),
    download: (id, signal, range) =>
      download(
        `${base}/plans/${encodeURIComponent(id)}/media-kit.xlsx${range ? `?${new URLSearchParams(range)}` : ''}`,
        signal,
      ),
  }
}

// Exact placement taxonomy from source CRM 078; this is separate from ACC recommendation groups.
export const placementTaxonomy = [
  {
    id: 'medicines',
    label: 'Лекарственные препараты',
    children: [
      ['prescription', 'Рецептурные препараты'],
      ['otc', 'Безрецептурные препараты'],
      ['vitamins_supplements', 'Витамины и БАД'],
    ],
  },
  {
    id: 'medical_devices',
    label: 'Медицинские изделия',
    children: [
      ['diagnostics', 'Диагностика и контроль'],
      ['dressings', 'Перевязочные материалы'],
      ['orthopedics', 'Ортопедия и реабилитация'],
    ],
  },
  {
    id: 'beauty_care',
    label: 'Красота и уход',
    children: [
      ['dermocosmetics', 'Дерматокосметика'],
      ['personal_hygiene', 'Личная гигиена'],
      ['oral_care', 'Уход за полостью рта'],
    ],
  },
  {
    id: 'mother_child',
    label: 'Мать и ребёнок',
    children: [
      ['baby_food', 'Детское питание'],
      ['mother_products', 'Товары для мам'],
      ['baby_hygiene', 'Детская гигиена'],
    ],
  },
  {
    id: 'nutrition_wellness',
    label: 'Питание и wellness',
    children: [
      ['sports_nutrition', 'Спортивное питание'],
      ['functional_food', 'Функциональное питание'],
      ['diet_food', 'Диетические продукты'],
    ],
  },
  {
    id: 'other',
    label: 'Прочие аптечные товары',
    children: [
      ['seasonal', 'Сезонные товары'],
      ['accessories', 'Сопутствующие товары'],
    ],
  },
]
export function planMonths(start: string, end: string): string[] {
  if (!/^\d{4}-\d{2}-01$/.test(start) || !/^\d{4}-\d{2}-01$/.test(end)) return []
  const from = new Date(`${start}T00:00:00Z`)
  const to = new Date(`${end}T00:00:00Z`)
  if (
    !Number.isFinite(from.getTime()) ||
    !Number.isFinite(to.getTime()) ||
    from.toISOString().slice(0, 10) !== start ||
    to.toISOString().slice(0, 10) !== end
  )
    return []
  const count =
    (to.getUTCFullYear() - from.getUTCFullYear()) * 12 + to.getUTCMonth() - from.getUTCMonth() + 1
  if (count < 1 || count > 12) return []
  const result: string[] = []
  for (const date = from; date <= to; date.setUTCMonth(date.getUTCMonth() + 1))
    result.push(date.toISOString().slice(0, 10))
  return result
}
