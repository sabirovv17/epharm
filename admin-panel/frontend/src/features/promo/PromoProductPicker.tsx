// Пикер товара витрины Medusa для промо-кампании. Поиск (q) идёт на бэкенд
// (GET /api/admin/storefront/products) — тот же прокси, что в разделе «Витрина».
// При выборе товара заполняет medusaProductId + снимок (имя/фото/бренд).
//
// T6: серверная пагинация (page size 50) с «Показать ещё» — доступны ВСЕ ~20k
// товаров витрины, а не первые 20. Дебаунс ввода (~300мс) сбрасывает выдачу.

import { useEffect, useMemo, useState } from 'react'
import { Button, Input } from '@/ui'
import { IconBox, IconCheck, IconSearch } from '@/ui/icons'
import { useStorefront } from '@/lib/queries/storefront'
import { useT } from '@/i18n'
import type { StorefrontProductDto } from '@/lib/api-types'

const PAGE = 50

export interface SelectedProduct {
  medusaProductId: string
  productName: string
  productImage: string | null
  brand: string
  /** Цена из витрины Medusa (read-only, для предпросмотра в форме). null — нет цены. */
  price: number | null
}

/**
 * Список результатов витрины с дебаунсом + «Показать ещё». Накапливает страницы,
 * показывает «показано X из Y». Используется и одиночным пикером кампании, и
 * мульти-пикером правил (T2). selectedIds — для галочек уже выбранных.
 */
function ProductSearchList({
  selectedIds,
  onPick,
}: {
  selectedIds: Set<string>
  onPick: (p: StorefrontProductDto) => void
}) {
  const t = useT()
  const [raw, setRaw] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)

  // Дебаунс ввода — не дёргаем бэкенд на каждый символ; новый запрос → стр. 0.
  useEffect(() => {
    const id = setTimeout(() => {
      setQ(raw.trim())
      setPage(0)
    }, 300)
    return () => clearTimeout(id)
  }, [raw])

  const { data, isFetching } = useStorefront(q, page * PAGE, PAGE)
  const total = data?.total ?? 0

  // Накапливаем страницы в один список (page=0 заменяет, >0 — добавляет). Храним
  // по q, чтобы смена запроса начинала с чистого листа. Обновляем acc только когда
  // реально пришли НОВЫЕ id (или сменился запрос) — иначе бесконечный ре-рендер,
  // т.к. useStorefront может возвращать новый объект data на каждый рендер.
  const [acc, setAcc] = useState<{ q: string; ids: string; items: StorefrontProductDto[] }>({
    q: '',
    ids: '',
    items: [],
  })
  useEffect(() => {
    if (!data) return
    const fresh = data.items
    if (page === 0 || acc.q !== q) {
      const ids = fresh.map((i) => i.id).join(',')
      if (acc.q === q && acc.ids === ids) return // ничего не изменилось — не дёргаем state
      setAcc({ q, ids, items: fresh })
      return
    }
    // page > 0: дозагрузка — добавляем только товары, которых ещё нет
    const seen = new Set(acc.items.map((i) => i.id))
    const added = fresh.filter((i) => !seen.has(i.id))
    if (added.length === 0) return
    const items = [...acc.items, ...added]
    setAcc({ q, ids: items.map((i) => i.id).join(','), items })
  }, [data, page, q, acc])

  const items = acc.q === q ? acc.items : []
  const canLoadMore = items.length < total

  return (
    <div className="flex flex-col gap-2">
      <Input
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
        placeholder={t('pp.searchPh')}
        leading={<IconSearch size={15} />}
        autoFocus
      />
      <div className="max-h-64 overflow-y-auto rounded-xl border border-ink-100">
        {items.length === 0 ? (
          <div className="flex items-center justify-center gap-2 px-3 py-6 text-[13px] font-semibold text-ink-400">
            <IconBox size={15} />
            {isFetching ? t('pp.searching') : q ? t('pp.notFound') : t('pp.startTyping')}
          </div>
        ) : (
          items.map((p) => {
            const selected = selectedIds.has(p.id)
            return (
              <button
                key={p.id}
                type="button"
                onClick={() => onPick(p)}
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
      {total > 0 && (
        <div className="flex items-center justify-between text-[12px] font-semibold text-ink-500">
          <span>{t('pp.shownOf', { shown: items.length, total })}</span>
          {canLoadMore && (
            <Button
              variant="ghost"
              disabled={isFetching}
              onClick={() => setPage((p) => p + 1)}
              data-testid="pp-load-more"
            >
              {isFetching ? t('pp.loading') : t('pp.loadMore')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Одиночный пикер (кампания: 1 товар) ─────────────────────────────────────
interface Props {
  value: SelectedProduct | null
  onChange: (p: SelectedProduct) => void
}

export function PromoProductPicker({ value, onChange }: Props) {
  const t = useT()
  const [open, setOpen] = useState(value === null)

  const pick = (p: StorefrontProductDto) => {
    onChange({
      medusaProductId: p.id,
      productName: p.name,
      productImage: p.imageUrl ?? null,
      brand: p.brand ?? '',
      price: p.price ?? null,
    })
    setOpen(false)
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
          {t('pp.change')}
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <ProductSearchList
        selectedIds={value ? new Set([value.medusaProductId]) : new Set()}
        onPick={pick}
      />
      {value && (
        <div className="text-right">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            {t('common.cancel')}
          </Button>
        </div>
      )}
    </div>
  )
}

// ─── Мульти-пикер (правила: список товаров замены/кросс-селла) ────────────────
export interface MultiProductPickerProps {
  /** id уже выбранных товаров — для галочек в выдаче. */
  selectedIds: string[]
  /** клик по товару в выдаче (toggle делает родитель). */
  onPick: (p: StorefrontProductDto) => void
}

export function MultiProductPicker({ selectedIds, onPick }: MultiProductPickerProps) {
  const ids = useMemo(() => new Set(selectedIds), [selectedIds])
  return <ProductSearchList selectedIds={ids} onPick={onPick} />
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
