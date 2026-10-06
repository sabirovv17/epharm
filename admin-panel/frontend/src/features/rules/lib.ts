// Helpers для Rules Engine.
// Источник дизайна: references/sections/rules.jsx.

import type { Product, Rule, RuleTrigger } from '@/mocks/fixtures'

export type ProductLookup = (id: string | undefined) => Product | undefined
type Translate = (key: string, vars?: Record<string, string | number>) => string

export function ruleTriggerDisplay(
  trigger: RuleTrigger,
  lookup?: ProductLookup,
  t?: Translate,
): string {
  const get = (id: string | undefined) => lookup?.(id)
  switch (trigger.kind) {
    case 'product':
      return get(trigger.value as string)?.name ?? (trigger.value as string)
    case 'product_any':
      return (trigger.value as string[])
        .map((id) => get(id)?.name ?? id)
        .join(', ')
    case 'mnn':
      return `МНН: ${trigger.value as string}`
    case 'acc_group':
      return t?.('rules.accGroupPrefix', { v: trigger.label || (trigger.value as string) })
        ?? `Группа ACC: ${trigger.label || trigger.value}`
    case 'acc_subgroup':
      return t?.('rules.accSubgroupPrefix', { v: trigger.label || (trigger.value as string) })
        ?? `Подгруппа ACC: ${trigger.label || trigger.value}`
    case 'acc_mnn':
      return t?.('rules.accMnnPrefix', { v: trigger.label || (trigger.value as string) })
        ?? `МНН ACC: ${trigger.label || trigger.value}`
  }
}

/**
 * Краткое описание правила: «Триггер → Рекомендация».
 * Передай `lookup` чтобы развернуть product-id'ы в имена. Без lookup'а
 * вернёт «—» вместо имени, что годится для тестов и fallback-рендера.
 */
export function ruleSummary(r: Rule, lookup?: ProductLookup, t?: Translate): string {
  const get = (id: string | undefined) => lookup?.(id)
  const trig =
    r.trigger.kind === 'product'
      ? (get(r.trigger.value as string)?.name ?? '—')
      : r.trigger.kind === 'product_any'
        ? (r.trigger.value as string[])
            .map((v) => get(v)?.name.split(' ').slice(0, 2).join(' '))
            .filter(Boolean)
            .join(', ') || '—'
        : ruleTriggerDisplay(r.trigger, lookup, t)
  const rec = get(r.recommend)?.name ?? '—'
  return `${trig} → ${rec}`
}

// Палитра упаковки по бренду (для ProductIcon). Если бренда нет в палитре — тёплый серый.
// Бренд-стопы переведены в коралл (Claude orange); amber/red/purple — без изменений.
export const VENDOR_PALETTE: Record<string, string> = {
  'Jadran-Galenski': '#D97757', // коралл 600
  'Aurena Labs': '#BE5A38', // коралл 700
  GlaxoSmithKline: '#F4B73A',
  Novartis: '#E0916B', // коралл 400
  Bayer: '#E5484D',
  Polpharma: '#8B5CF6',
  KRKA: '#BE5A38', // коралл 700
  Reckitt: '#B91C1C',
}

export const vendorColor = (brand: string): string => VENDOR_PALETTE[brand] ?? '#6F665B'
