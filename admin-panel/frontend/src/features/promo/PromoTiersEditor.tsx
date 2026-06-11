// Редактор ценовых порогов акции: строки {minQty, price, bonus}. Бонус встроен
// в порог (0 у первого). Подсвечивает нарушение монотонности по количеству.

import { Button, IconButton, Input } from '@/ui'
import { IconPlus, IconTrash } from '@/ui/icons'
import type { PromoTierDto } from '@/lib/api-types'

interface Props {
  value: PromoTierDto[]
  onChange: (tiers: PromoTierDto[]) => void
}

export function PromoTiersEditor({ value, onChange }: Props) {
  const patch = (i: number, p: Partial<PromoTierDto>) =>
    onChange(value.map((t, idx) => (idx === i ? { ...t, ...p } : t)))
  const remove = (i: number) => onChange(value.filter((_, idx) => idx !== i))
  const add = () => {
    const last = value[value.length - 1]
    onChange([...value, { minQty: last ? last.minQty + 1 : 1, price: last?.price ?? 0, bonus: 0 }])
  }
  const notMonotonic = (i: number) => i > 0 && value[i].minQty <= value[i - 1].minQty
  const hasBadOrder = value.some((_, i) => notMonotonic(i))

  const num = (v: string) => {
    const n = Number(v)
    return Number.isFinite(n) && n >= 0 ? Math.trunc(n) : 0
  }

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
            onChange={(e) => patch(i, { minQty: num(e.target.value) })}
            className={notMonotonic(i) ? 'border-accent-danger' : ''}
          />
          <Input
            type="number"
            min={0}
            value={String(t.price)}
            onChange={(e) => patch(i, { price: num(e.target.value) })}
          />
          <Input
            type="number"
            min={0}
            value={String(t.bonus)}
            onChange={(e) => patch(i, { bonus: num(e.target.value) })}
          />
          <IconButton onClick={() => remove(i)} aria-label="Удалить порог">
            <IconTrash size={15} />
          </IconButton>
        </div>
      ))}
      {hasBadOrder && (
        <div className="text-[12px] font-semibold text-accent-danger">
          Количество в порогах должно возрастать (от 1 → от 10 → от 20).
        </div>
      )}
      <div>
        <Button variant="ghost" onClick={add} leading={<IconPlus size={14} />}>
          Добавить порог
        </Button>
      </div>
    </div>
  )
}
