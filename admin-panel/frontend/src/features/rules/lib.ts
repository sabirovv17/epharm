// Helpers для Rules Engine.
// Источник дизайна: references/sections/rules.jsx.

import type { Product, Rule } from '@/mocks/fixtures'

export type ProductLookup = (id: string | undefined) => Product | undefined

/**
 * Краткое описание правила: «Триггер → Рекомендация».
 * Передай `lookup` чтобы развернуть product-id'ы в имена. Без lookup'а
 * вернёт «—» вместо имени, что годится для тестов и fallback-рендера.
 */
export function ruleSummary(r: Rule, lookup?: ProductLookup): string {
  const get = (id: string | undefined) => lookup?.(id)
  const trig =
    r.trigger.kind === 'product'
      ? (get(r.trigger.value as string)?.name ?? '—')
      : r.trigger.kind === 'product_any'
        ? (r.trigger.value as string[])
            .map((v) => get(v)?.name.split(' ').slice(0, 2).join(' '))
            .filter(Boolean)
            .join(', ') || '—'
        : `МНН: ${r.trigger.value as string}`
  const rec = get(r.recommend)?.name ?? '—'
  return `${trig} → ${rec}`
}

// Палитра упаковки по бренду (для ProductIcon). Если бренда нет в палитре — серый.
export const VENDOR_PALETTE: Record<string, string> = {
  'Jadran-Galenski': '#16C97A',
  'Aurena Labs': '#2A2BE2',
  GlaxoSmithKline: '#F4B73A',
  Novartis: '#5560FB',
  Bayer: '#E5484D',
  Polpharma: '#8B5CF6',
  KRKA: '#0F8F55',
  Reckitt: '#B91C1C',
}

export const vendorColor = (brand: string): string => VENDOR_PALETTE[brand] ?? '#5A6173'
