import { useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { formatKzt } from '@/mocks/fixtures'
import { inventoryEditorApi } from './api'
import type { EditorProps } from './InventoryEditor'

export default function InventoryPrices({
  data,
  write,
  busy,
  month,
}: EditorProps & { month: string }) {
  const [selected, setSelected] = useState<string[]>([])
  const [from, setFrom] = useState(month)
  const [to, setTo] = useState(month)
  const [price, setPrice] = useState(10000)
  const [saleMode, setSaleMode] = useState('')
  const [halfPrice, setHalfPrice] = useState(5000)
  const [future, setFuture] = useState(false)
  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-500">
        Цены рекламных мест отличаются от цен товаров. Принятые и зарезервированные размещения
        защищены; согласованные суммы не пересчитываются.
      </p>
      {!data.canConfigurePrices && (
        <p role="status" className="rounded-xl bg-paper-input p-4">
          Настройка цен недоступна. Просмотр сумм зависит от отдельного финансового права.
        </p>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead>
            <tr>
              <th>Выбор</th>
              <th>Место</th>
              <th>Базовая цена</th>
              <th>Цена половины</th>
              <th>Помесячные значения</th>
            </tr>
          </thead>
          <tbody>
            {data.shelves.map(({ shelf }) => (
              <tr className="border-t border-ink-100" key={shelf.id}>
                <td className="p-2">
                  <input
                    aria-label={`Выбрать цену ${shelf.code}`}
                    type="checkbox"
                    disabled={!data.canConfigurePrices || busy || !shelf.active}
                    checked={selected.includes(shelf.id)}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...selected, shelf.id]
                          : selected.filter((id) => id !== shelf.id),
                      )
                    }
                  />
                </td>
                <td className="font-bold">
                  {shelf.code}
                  {!shelf.active && ' · Архив'}
                </td>
                <td>
                  {shelf.baseMonthlyPrice === null
                    ? 'Нет доступа'
                    : formatKzt(shelf.baseMonthlyPrice)}
                </td>
                <td>{shelf.halfMonthlyPrice === null ? '—' : formatKzt(shelf.halfMonthlyPrice)}</td>
                <td>
                  <div className="flex max-w-md flex-wrap gap-2 py-2">
                    {data.monthlyPrices
                      .filter((item) => item.shelfId === shelf.id)
                      .map((item) => (
                        <span key={item.month} className="rounded bg-paper-input px-2 py-1 text-xs">
                          {item.month.slice(0, 7)} ·{' '}
                          {item.price === null ? 'Нет доступа' : formatKzt(item.price)} ·{' '}
                          {(
                            {
                              available: 'свободно',
                              blocked: 'блокировка',
                              held: 'резерв',
                              booked: 'занято',
                            } as Record<string, string>
                          )[item.status] || item.status}
                        </span>
                      ))}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.canConfigurePrices && (
        <form
          className="space-y-3 rounded-xl bg-paper-input p-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (
              !window.confirm(
                'Изменить цены выбранных мест? Защищённые размещения будут пропущены.',
              )
            )
              return
            void write(() =>
              inventoryEditorApi.prices(data.branch.id, data.revision, {
                shelfIds: selected,
                fromMonth: `${from}-01`,
                toMonth: `${to}-01`,
                price,
                saleMode: saleMode || null,
                halfPrice: saleMode === 'whole_or_halves' ? halfPrice : null,
                applyFuture: future,
              }),
            )
          }}
        >
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Цены с месяца">
              <Input
                aria-label="Цены с месяца"
                type="month"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
                required
              />
            </Field>
            <Field label="Цены по месяц">
              <Input
                aria-label="Цены по месяц"
                type="month"
                value={to}
                min={from}
                onChange={(e) => setTo(e.target.value)}
                required
              />
            </Field>
            <Field label="Цена целого места, ₸">
              <Input
                aria-label="Цена целого места, ₸"
                type="number"
                min={10000}
                required
                value={price}
                onChange={(e) => setPrice(Number(e.target.value))}
              />
            </Field>
            <Field label="Способ продажи">
              <select
                className="inp"
                aria-label="Способ продажи"
                value={saleMode}
                onChange={(e) => setSaleMode(e.target.value)}
              >
                <option value="">Сохранить текущий</option>
                <option value="whole_only">Только целиком</option>
                <option value="whole_or_halves">Целиком или половинами</option>
              </select>
            </Field>
            {saleMode === 'whole_or_halves' && (
              <Field label="Цена половины, ₸">
                <Input
                  aria-label="Цена половины, ₸"
                  type="number"
                  min={5000}
                  required
                  value={halfPrice}
                  onChange={(e) => setHalfPrice(Number(e.target.value))}
                />
              </Field>
            )}
          </div>
          <label className="flex gap-2 text-sm">
            <input type="checkbox" checked={future} onChange={(e) => setFuture(e.target.checked)} />
            Применить также к базовой цене и существующим свободным будущим месяцам
          </label>
          <Button type="submit" disabled={busy || !selected.length || !from || !to || from > to}>
            Применить цены
          </Button>
        </form>
      )}
    </div>
  )
}
