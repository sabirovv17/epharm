// ProductBlock + ProductIcon — используются в RuleRow и RulePreview.
// Псевдо-цвет упаковки по бренду производителя.

import { IconBox, IconLayers } from '@/ui/icons'
import type { Product, RuleTrigger } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { vendorColor } from './lib'

interface ProductIconProps {
  product: Product
  accent?: boolean
  size?: number
}

export function ProductIcon({ product, accent, size = 40 }: ProductIconProps) {
  const color = vendorColor(product.brand)
  return (
    <span
      className={`flex flex-none items-center justify-center rounded-lg ${
        accent ? 'ring-2 ring-brand-green-400/40' : ''
      }`}
      style={{ width: size, height: size, background: `${color}22`, color }}
    >
      <IconBox size={size * 0.45} />
    </span>
  )
}

interface ProductBlockProps {
  product: Product | undefined | null
  accent?: boolean
  fallback?: RuleTrigger
}

export function ProductBlock({ product, accent, fallback }: ProductBlockProps) {
  const t = useT()
  if (!product) {
    const name =
      fallback?.kind === 'mnn'
        ? t('rules.mnnPrefix', { v: fallback.value as string })
        : fallback?.kind === 'product_any'
          ? t('rules.productGroupN', { n: (fallback.value as string[]).length })
          : t('rules.anyTrigger')
    return (
      <div className="flex min-w-0 flex-1 items-center gap-2">
        <span className="flex h-10 w-10 flex-none items-center justify-center rounded-lg bg-brand-blue-100 text-brand-blue-600">
          <IconLayers size={18} />
        </span>
        <div className="min-w-0">
          <div className="truncate text-[13px] font-extrabold text-ink-900">{name}</div>
          <div className="text-[11px] font-semibold text-ink-500">{t('rules.mnnGroup')}</div>
        </div>
      </div>
    )
  }
  return (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      <ProductIcon product={product} accent={accent} />
      <div className="min-w-0">
        <div className="truncate text-[13px] font-extrabold text-ink-900">{product.name}</div>
        <div className="truncate text-[11px] font-semibold text-ink-500">{product.brand}</div>
      </div>
    </div>
  )
}
