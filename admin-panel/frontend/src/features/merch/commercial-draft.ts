import { useUiStore } from '@/app/store'
import { producerClient } from './producer-client'
import type {
  CommercialGateway,
  CommercialPlan,
  CommercialPlanData,
} from './producer-commercial-api'

const PREFIX = 'epharm.commercial.draft.v1.'
type Binding = { realm: 'admin' | 'producer'; principal: string; producer: string; session: string }
const fields = new Set([
  'title',
  'startMonth',
  'endMonth',
  'budgetLimit',
  'status',
  'notes',
  'planningMode',
  'productBriefs',
  'shelfSelection',
  'extraActivities',
  'items',
  'id',
  'productName',
  'sku',
  'productCategory',
  'productSubcategory',
  'facingCount',
  'activeMonths',
  'shelfCategories',
  'shelfId',
  'pharmacyId',
  'portion',
  'placementMonths',
  'products',
  'price',
  'code',
  'category',
  'width',
  'pharmacyName',
  'city',
  'address',
  'organizationId',
  'organizationName',
  'shelfKey',
  'placementSelectionId',
  'portionLabel',
  'activityType',
  'pharmacyCount',
  'peopleCount',
  'unitPrice',
  'supplierCategory',
  'note',
])
const invalid = () =>
  new Error(
    'Нужен JSON черновика до 500 КБ, без паролей, кодов, токенов, ссылок и приватных путей. Поля и доступ повторно проверит сервер.',
  )
function storage(): Storage | null {
  try {
    return sessionStorage
  } catch {
    return null
  }
}
export function clearCommercialDrafts(realm: Binding['realm']) {
  const store = storage()
  if (!store) return
  try {
    for (const key of Object.keys(store))
      if (key.startsWith(`${PREFIX}${realm}.`)) store.removeItem(key)
  } catch {
    /* unavailable storage must not block logout */
  }
}
const adminStamp = () => {
  const s = useUiStore.getState()
  return `${s.authedUser?.id ?? ''}:${s.querySessionEpoch}`
}
const producerStamp = () => {
  const s = producerClient.snapshot()
  return `${s.session?.customer.id ?? ''}:${s.epoch}`
}
let lastAdmin = adminStamp()
let lastProducer = producerStamp()
useUiStore.subscribe(() => {
  const next = adminStamp()
  if (next !== lastAdmin) {
    clearCommercialDrafts('admin')
    lastAdmin = next
  }
})
producerClient.subscribe(() => {
  const next = producerStamp()
  if (next !== lastProducer) {
    clearCommercialDrafts('producer')
    lastProducer = next
  }
})

export function draftBinding(realm: Binding['realm'], producer: string): Binding | null {
  const principal =
    realm === 'admin'
      ? useUiStore.getState().authedUser?.id
      : producerClient.snapshot().session?.customer.id
  if (!principal || (realm === 'producer' && principal !== producer)) return null
  const store = storage()
  if (!store) return null
  const key = `${PREFIX}${realm}.session`
  try {
    const previous = JSON.parse(store.getItem(key) ?? 'null') as {
      principal?: string
      nonce?: string
    } | null
    if (previous?.principal !== principal || typeof previous.nonce !== 'string') {
      clearCommercialDrafts(realm)
      store.setItem(key, JSON.stringify({ principal, nonce: crypto.randomUUID() }))
    }
    return { realm, principal, producer, session: JSON.parse(store.getItem(key)!).nonce as string }
  } catch {
    return null
  }
}
function key(binding: Binding, id: string) {
  return `${PREFIX}${binding.realm}.${binding.principal}.${binding.producer}.${binding.session}.${id}`
}
function assertBinding(binding: Binding) {
  const current = draftBinding(binding.realm, binding.producer)
  if (!current || JSON.stringify(current) !== JSON.stringify(binding))
    throw new Error('Сессия изменилась. Локальный черновик недоступен.')
}
export function draftJSON(value: CommercialPlanData): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
  const raw = JSON.stringify(value)
  if (new TextEncoder().encode(raw).length > 500_000) throw invalid()
  const inspect = (v: unknown, depth = 0): void => {
    if (depth > 12 || (typeof v === 'number' && Number.isInteger(v) && !Number.isSafeInteger(v)))
      throw invalid()
    if (
      typeof v === 'string' &&
      (/https?:\/\/|blob:|data:|Bearer\s|(?:^|\s)eyJ[A-Za-z0-9_-]{20}/i.test(v) ||
        /^(?:\/|[A-Za-z]:\\)/.test(v))
    )
      throw invalid()
    if (Array.isArray(v)) v.forEach((child) => inspect(child, depth + 1))
    else if (v && typeof v === 'object')
      for (const [k, child] of Object.entries(v)) {
        if (/password|token|secret|otp|authorization|object.?key|(?:^|_)url$|(?:^|_)path$/i.test(k))
          throw invalid()
        inspect(child, depth + 1)
      }
  }
  inspect(value)
  const plan = JSON.parse(raw, (k, v: unknown) =>
    !k || /^\d+$/.test(k) || fields.has(k) ? v : undefined,
  ) as CommercialPlanData
  const top = new Set([
    'title',
    'startMonth',
    'endMonth',
    'budgetLimit',
    'status',
    'notes',
    'planningMode',
    'productBriefs',
    'shelfSelection',
    'extraActivities',
    'items',
  ])
  for (const k of Object.keys(plan))
    if (!top.has(k)) delete (plan as unknown as Record<string, unknown>)[k]
  if (
    typeof plan.title !== 'string' ||
    !/^\d{4}-\d{2}-01$/.test(plan.startMonth) ||
    !/^\d{4}-\d{2}-01$/.test(plan.endMonth) ||
    !Array.isArray(plan.productBriefs) ||
    !Array.isArray(plan.items) ||
    !Array.isArray(plan.extraActivities) ||
    (plan.shelfSelection !== null && !Array.isArray(plan.shelfSelection))
  )
    throw invalid()
  const dates = (v: unknown): v is string[] =>
    Array.isArray(v) &&
    v.length <= 12 &&
    v.every((s) => typeof s === 'string' && /^\d{4}-\d{2}-01$/.test(s))
  const product = (v: unknown) => {
    const p = v as CommercialPlanData['productBriefs'][number] | null
    if (
      !p ||
      typeof p.id !== 'string' ||
      typeof p.productName !== 'string' ||
      !dates(p.activeMonths) ||
      !Array.isArray(p.shelfCategories) ||
      p.shelfCategories.some((s) => typeof s !== 'string')
    )
      throw invalid()
  }
  if (
    plan.productBriefs.length > 20 ||
    (plan.shelfSelection?.length ?? 0) > 500 ||
    plan.items.length > 500 ||
    plan.extraActivities.length > 30
  )
    throw invalid()
  plan.productBriefs.forEach(product)
  for (const row of plan.shelfSelection ?? []) {
    if (
      !row ||
      typeof row.shelfId !== 'string' ||
      !dates(row.placementMonths) ||
      !Array.isArray(row.products) ||
      row.products.length > 20 ||
      !['whole', 'left', 'right'].includes(row.portion)
    )
      throw invalid()
    row.products.forEach(product)
  }
  for (const activity of [...plan.items, ...plan.extraActivities])
    if (
      !activity ||
      typeof activity.productName !== 'string' ||
      typeof activity.activityType !== 'string' ||
      !dates(activity.activeMonths)
    )
      throw invalid()
  const arrays = new Set([
    'productBriefs',
    'shelfSelection',
    'extraActivities',
    'items',
    'products',
    'activeMonths',
    'shelfCategories',
    'placementMonths',
  ])
  const scalar = (v: unknown): void => {
    if (!v || typeof v !== 'object') return
    if (Array.isArray(v)) {
      v.forEach(scalar)
      return
    }
    for (const [k, child] of Object.entries(v)) {
      if (!arrays.has(k) && child !== null && !['string', 'number'].includes(typeof child))
        throw invalid()
      scalar(child)
    }
  }
  scalar(plan)
  return JSON.stringify(plan)
}
export function importDraftJSON(text: string): CommercialPlanData {
  if (new TextEncoder().encode(text).length > 500_000) throw invalid()
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw invalid()
  }
  const object = value as { plan?: CommercialPlanData }
  return JSON.parse(draftJSON(object?.plan ?? (value as CommercialPlanData))) as CommercialPlanData
}
export async function storeCommercialDraft(
  binding: Binding,
  original: CommercialPlan,
  draft: CommercialPlanData,
  gateway: CommercialGateway,
  signal: AbortSignal,
) {
  assertBinding(binding)
  if (gateway.realm !== binding.realm)
    throw new Error('Кабинет изменился. Локальный снимок недоступен.')
  const fresh = await gateway.view(original.id, signal)
  assertBinding(binding)
  if (
    signal.aborted ||
    !original.canEdit ||
    original.producerId !== binding.producer ||
    fresh.id !== original.id ||
    !fresh.canEdit ||
    fresh.producerId !== binding.producer ||
    fresh.revision !== original.revision
  )
    throw new Error('Права или версия плана изменились. Локальный снимок не сохранён.')
  const plan = JSON.parse(draftJSON(draft)) as CommercialPlanData
  const store = storage()
  if (!store) throw new Error('Хранилище этой сессии недоступно. Сохраните JSON-файл.')
  store.setItem(
    key(binding, original.id),
    JSON.stringify({ schemaVersion: 1, revision: fresh.revision, plan }),
  )
}
export async function recoverCommercialDraft(
  binding: Binding,
  id: string,
  revision: number,
  gateway: CommercialGateway,
  signal: AbortSignal,
): Promise<CommercialPlanData> {
  assertBinding(binding)
  if (gateway.realm !== binding.realm)
    throw new Error('Кабинет изменился. Локальный снимок недоступен.')
  const name = key(binding, id)
  const store = storage()
  const text = store?.getItem(name)
  if (!text) throw new Error('В этой сессии нет сохранённых локальных изменений этого плана.')
  let fresh: CommercialPlan
  try {
    fresh = await gateway.view(id, signal)
  } catch (error) {
    const e = error as { status?: number; response?: { status?: number } }
    if ([401, 403, 404].includes(e?.status ?? e?.response?.status ?? 0)) store?.removeItem(name)
    throw error
  }
  assertBinding(binding)
  let value: { schemaVersion: number; revision: number; plan: CommercialPlanData }
  try {
    value = JSON.parse(text) as typeof value
  } catch {
    store?.removeItem(name)
    throw invalid()
  }
  if (
    signal.aborted ||
    value.schemaVersion !== 1 ||
    fresh.id !== id ||
    !fresh.canEdit ||
    fresh.producerId !== binding.producer ||
    fresh.revision !== value.revision ||
    revision !== value.revision
  ) {
    store?.removeItem(name)
    throw new Error('Версия, владелец или права изменились. Локальный снимок не восстановлен.')
  }
  return importDraftJSON(JSON.stringify(value.plan))
}
export function discardCommercialDraft(binding: Binding, id: string) {
  storage()?.removeItem(key(binding, id))
}
