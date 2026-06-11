// Пикер товара витрины Medusa для промо-кампании. Поиск (q) идёт на бэкенд
// (GET /api/admin/storefront/products) — тот же прокси, что в разделе «Витрина».
// При выборе товара заполняет medusaProductId + снимок (имя/фото/бренд).

import { useEffect, useMemo, useState } from 'react'
import { Button, Input } from '@/ui'
import { IconBox, IconCheck, IconSearch } from '@/ui/icons'
import { useStorefront } from '@/lib/queries/storefront'
import type { StorefrontProductDto } from '@/lib/api-types'

export interface SelectedProduct {
  medusaProductId: string
  productName: string
  productImage: string | null
  brand: string
}

interface Props {
  value: SelectedProduct | null
  onChange: (p: SelectedProduct) => void
}

export function PromoProductPicker({ value, onChange }: Props) {
  const [open, setOpen] = useState(value === null)
  const [raw, setRaw] = useState('')
  const [q, setQ] = useState('')

  // Дебаунс ввода — не дёргаем бэкенд на каждый символ.
  useEffect(() => {
    const t = setTimeout(() => setQ(raw.trim()), 300)
    return () => clearTimeout(t)
  }, [raw])

  const { data, isFetching } = useStorefront(q, 0, 20)
  const items = useMemo(() => data?.items ?? [], [data])

  const pick = (p: StorefrontProductDto) => {
    onChange({
      medusaProductId: p.id,
      productName: p.name,
      productImage: p.imageUrl ?? null,
      brand: p.brand ?? '',
    })
    setOpen(false)
    setRaw('')
  }

  if (value && !open) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-ink-100 bg-paper-card p-3">
        <ProductThumb url={value.productImage} name={value.productName} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-[14px] font-extrabold text-ink-900">
            {value.productName}
          </div>
          {value.brand && (
            <div className="truncate text-[12px] font-bold text-ink-500">{value.brand}</div>
          )}
        </div>
        <Button variant="ghost" onClick={() => setOpen(true)}>
          Сменить
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <Input
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
        placeholder="Поиск товара в витрине Medusa…"
        leading={<IconSearch size={15} />}
        autoFocus
      />
      <div className="max-h-64 overflow-y-auto rounded-xl border border-ink-100">
        {items.length === 0 ? (
          <div className="flex items-center justify-center gap-2 px-3 py-6 text-[13px] font-semibold text-ink-400">
            <IconBox size={15} />
            {isFetching ? 'Поиск…' : q ? 'Ничего не найдено' : 'Начните вводить название товара'}
          </div>
        ) : (
          items.map((p) => {
            const selected = value?.medusaProductId === p.id
            return (
              <button
                key={p.id}
                type="button"
                onClick={() => pick(p)}
                className="flex w-full items-center gap-3 border-b border-ink-50 px-3 py-2 text-left last:border-b-0 hover:bg-paper-input"
              >
                <ProductThumb url={p.imageUrl} name={p.name} size={40} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13px] font-bold text-ink-900">{p.name}</div>
                  {p.brand && (
                    <div className="truncate text-[11px] font-semibold text-ink-500">{p.brand}</div>
                  )}
                </div>
                {selected && <IconCheck size={16} />}
              </button>
            )
          })
        )}
      </div>
      {value && (
        <div className="text-right">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            Отмена
          </Button>
        </div>
      )}
    </div>
  )
}

function ProductThumb({
  url,
  name,
  size = 48,
}: {
  url: string | null
  name: string
  size?: number
}) {
  const [broken, setBroken] = useState(false)
  if (!url || broken) {
    return (
      <div
        className="flex shrink-0 items-center justify-center rounded-lg bg-brand-green-100 font-extrabold text-brand-green-700"
        style={{ width: size, height: size, fontSize: size * 0.4 }}
      >
        {(name[0] ?? '?').toUpperCase()}
      </div>
    )
  }
  return (
    <img
      src={url}
      alt={name}
      onError={() => setBroken(true)}
      className="shrink-0 rounded-lg object-cover"
      style={{ width: size, height: size }}
    />
  )
}
