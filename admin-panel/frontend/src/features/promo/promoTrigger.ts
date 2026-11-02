import type { PromoRuleProductRef, PromoTriggerKind } from '@/lib/api-types'

export function promoTriggerKind(ref: PromoRuleProductRef): PromoTriggerKind {
  return ref.triggerKind ?? 'product'
}

export function promoTriggerKey(ref: PromoRuleProductRef): string {
  const kind = promoTriggerKind(ref)
  const base =
    kind === 'product' ? `product:${ref.medusaProductId}` : `${kind}:${ref.triggerValue ?? ''}`
  const conditions = [...(ref.triggerConditions ?? [])].map(({ kind, value }) => [
    kind,
    value.trim(),
  ])
  if (!conditions.length) return base
  const factors = [
    [kind, kind === 'product' ? ref.medusaProductId : (ref.triggerValue ?? '').trim()],
    ...conditions,
  ].sort((a, b) => a[0].localeCompare(b[0]))
  return JSON.stringify(factors)
}

export function promoTriggerDomId(ref: PromoRuleProductRef): string {
  if (promoTriggerKind(ref) === 'product' && !ref.triggerConditions?.length)
    return ref.medusaProductId
  return promoTriggerKey(ref).replace(/[^a-zA-Z0-9_-]/g, '-')
}
