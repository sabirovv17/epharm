import type { PromoRuleProductRef, PromoTriggerKind } from '@/lib/api-types'

export function promoTriggerKind(ref: PromoRuleProductRef): PromoTriggerKind {
  return ref.triggerKind ?? 'product'
}

export function promoTriggerKey(ref: PromoRuleProductRef): string {
  const kind = promoTriggerKind(ref)
  return kind === 'product'
    ? `product:${ref.medusaProductId}`
    : `${kind}:${ref.triggerValue ?? ''}`
}

export function promoTriggerDomId(ref: PromoRuleProductRef): string {
  if (promoTriggerKind(ref) === 'product') return ref.medusaProductId
  return promoTriggerKey(ref).replace(/[^a-zA-Z0-9_-]/g, '-')
}
