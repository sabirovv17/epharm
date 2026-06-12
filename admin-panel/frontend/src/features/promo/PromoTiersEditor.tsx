// Редактор ценовых порогов акции: строки {minQty, price, bonus}. Бонус встроен
// в порог (0 у первого). minQty не опускается ниже 1 (как @Min(1) на бэке),
// последний порог удалить нельзя (акция без порогов невалидна). disabled — для
// архивной акции (read-only).

import { Button, IconButton, Input } from '@/ui'
import { IconPlus, IconTrash } from '@/ui/icons'
import type { PromoTierDto } from '@/lib/api-types'

interface Props {
  value: PromoTierDto[]
  onChange: (tiers: PromoTierDto[]) => void
  disabled?: boolean
}

export function PromoTiersEditor({ value, onChange, disabled = false }: Props) {
  const patch = (i: number, p: Partial<PromoTierDto>) =>
    onChange(value.map((t, idx) => (idx === i ? { ...t, ...p } : t)))
  // Последний порог не удаляем: товарная акция без порогов невалидна (бэк → 400).
  const remove = (i: number) => {
    if (value.length <= 1) return
    onChange(value.filter((_, idx) => idx !== i))
  }
  const add = () => {
    const last = value[value.length - 1]
    onChange([...value, { minQty: last ? last.minQty + 1 : 1, price: last?.price ?? 0, bonus: 0 }])
  }
  const notMonotonic = (i: number) => i > 0 && value[i].minQty <= value[i - 1].minQty
  const hasBadOrder = value.some((_, i) => notMonotonic(i))

  // Целое с нижней границей (minQty ≥ 1, цена/бонус ≥ 0 — зеркало bean-validation бэка).
  const intMin = (v: string, min: number) => {
    const n = Number(v)
    return Number.isFinite(n) ? Math.max(min, Math.trunc(n)) : min
  }

  const canRemove = !disabled && value.length > 1

  return (
    <div className="flex flex-col gap-2">
      {value.length > 0 && (
        <div className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2 px-1 text-[11px] font-bold uppercase tracking-wide text-ink-400">
          <span>От, шт</span>
          <span>Цена&nbsp;₸</span>
          <span>Бонус&nbsp;₸</span>
          <span className="w-8" />
        </div>
      )}
      {value.map((t, i) => (
        <div key={i} className="grid grid-cols-[1fr_1fr_1fr_auto] items-center gap-2">
          <Input
            type="number"
            min={1}
            value={String(t.minQty)}
            disabled={disabled}
            onChange={(e) => patch(i, { minQty: intMin(e.target.value, 1) })}
            className={notMonotonic(i) ? 'border-accent-danger' : ''}
          />
          <Input
            type="number"
            min={0}
            value={String(t.price)}
            disabled={disabled}
            onChange={(e) => patch(i, { price: intMin(e.target.value, 0) })}
          />
          <Input
            type="number"
            min={0}
            value={String(t.bonus)}
            disabled={disabled}
            onChange={(e) => patch(i, { bonus: intMin(e.target.value, 0) })}
          />
          <IconButton onClick={() => remove(i)} aria-label="Удалить порог" disabled={!canRemove}>
            <IconTrash size={15} />
          </IconButton>
        </div>
      ))}
      {hasBadOrder && (
        <div className="text-[12px] font-semibold text-accent-danger">
          Количество в порогах должно возрастать (от 1 → от 10 → от 20).
        </div>
      )}
      {!disabled && (
        <div>
          <Button variant="ghost" onClick={add} leading={<IconPlus size={14} />}>
            Добавить порог
          </Button>
        </div>
      )}
    </div>
  )
}

/** Валидны ли пороги: ≥1 порог, minQty≥1, цена/бонус≥0, строго возрастающий minQty. */
export function tiersValid(tiers: PromoTierDto[]): boolean {
  if (tiers.length < 1) return false
  for (let i = 0; i < tiers.length; i++) {
    if (tiers[i].minQty < 1 || tiers[i].price < 0 || tiers[i].bonus < 0) return false
    if (i > 0 && tiers[i].minQty <= tiers[i - 1].minQty) return false
  }
  return true
}
