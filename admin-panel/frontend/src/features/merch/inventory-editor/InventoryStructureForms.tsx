import { useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { formatKzt } from '@/mocks/fixtures'
import { inventoryEditorApi, type ContainerInput, type ShelfInput } from './api'
import type { EditorProps } from './InventoryEditor'

const categories = ['AA', 'AB', 'BA', 'BB', 'CA', 'CB']
export default function InventoryStructureForms(props: EditorProps) {
  const { data, busy } = props
  const [containerId, setContainerId] = useState('')
  const [shelfId, setShelfId] = useState('')
  const [configure, setConfigure] = useState('')
  return (
    <div className="space-y-5">
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="font-bold">Шкафы и зоны</h3>
          {data.canEditStructure && (
            <Button
              disabled={busy}
              onClick={() => {
                setContainerId('new')
                setShelfId('')
                setConfigure('')
              }}
            >
              Добавить шкаф
            </Button>
          )}
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {data.containers.map((item) => (
            <div key={item.id} className="rounded-xl border border-ink-100 p-4">
              <div className="font-bold">
                {item.code} · {item.name}
              </div>
              <p className="text-sm text-ink-500">
                {item.type} · {item.category || 'Без категории'}
                {!item.active && ' · Архив'}
              </p>
              {data.canEditStructure && (
                <div className="mt-3 flex gap-2">
                  <Button
                    variant="outline"
                    disabled={busy}
                    onClick={() => {
                      setContainerId(item.id)
                      setConfigure('')
                      setShelfId('')
                    }}
                  >
                    Изменить шкаф
                  </Button>
                  {item.active && item.type === 'cabinet' && (
                    <Button
                      variant="outline"
                      disabled={busy}
                      onClick={() => {
                        setConfigure(item.id)
                        setContainerId('')
                        setShelfId('')
                      }}
                    >
                      Количество полок
                    </Button>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
        {containerId && (
          <ContainerForm
            key={containerId}
            {...props}
            id={containerId === 'new' ? null : containerId}
            close={() => setContainerId('')}
          />
        )}
        {configure && (
          <ConfigureForm key={configure} {...props} id={configure} close={() => setConfigure('')} />
        )}
      </section>
      <section className="border-t border-ink-100 pt-5">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="font-bold">Рекламные места</h3>
          {data.canEditStructure && (
            <Button
              disabled={busy || !data.canConfigurePrices}
              onClick={() => {
                setShelfId('new')
                setContainerId('')
                setConfigure('')
              }}
            >
              Добавить рекламное место
            </Button>
          )}
        </div>
        {data.canEditStructure && !data.canConfigurePrices && (
          <p className="mb-3 text-xs text-ink-500">
            Для нового места нужна начальная цена и отдельное право настройки цен. Описания
            существующих мест можно менять.
          </p>
        )}
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                <th className="p-2">Место</th>
                <th>Шкаф</th>
                <th>Размер / ёмкость</th>
                <th>Базовая цена</th>
                <th>Статус</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.shelves.map(({ shelf, maxProducts }) => (
                <tr key={shelf.id} className="border-t border-ink-100">
                  <td className="p-2 font-bold">
                    {shelf.code}
                    <div className="font-normal text-ink-500">{shelf.title || shelf.category}</div>
                  </td>
                  <td>
                    {data.containers.find((item) => item.id === shelf.containerId)?.code ||
                      'Без шкафа'}
                  </td>
                  <td>
                    {shelf.width} м · до {maxProducts} товаров
                  </td>
                  <td>
                    {shelf.baseMonthlyPrice === null
                      ? 'Нет доступа'
                      : formatKzt(shelf.baseMonthlyPrice)}
                  </td>
                  <td>{shelf.active ? 'Активно' : 'Архив'}</td>
                  <td>
                    {data.canEditStructure && (
                      <Button
                        variant="outline"
                        disabled={busy}
                        onClick={() => {
                          setShelfId(shelf.id)
                          setContainerId('')
                          setConfigure('')
                        }}
                      >
                        Изменить место
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {shelfId && (
          <ShelfForm
            key={shelfId}
            {...props}
            id={shelfId === 'new' ? null : shelfId}
            close={() => setShelfId('')}
          />
        )}
      </section>
    </div>
  )
}
function ContainerForm({
  data,
  busy,
  write,
  id,
  close,
}: EditorProps & { id: string | null; close: () => void }) {
  const old = data.containers.find((item) => item.id === id)
  const [form, setForm] = useState<ContainerInput>({
    code: old?.code || '',
    name: old?.name || '',
    type: old?.type || 'cabinet',
    category: old?.category || 'A',
    position: old?.position || 0,
    active: old?.active ?? true,
  })
  return (
    <form
      className="mt-4 space-y-3 rounded-xl bg-paper-input p-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (
          !form.active &&
          !window.confirm(
            'Архивировать шкаф вместе с его полками? Действующие размещения защищены, история сохраняется.',
          )
        )
          return
        void write(() =>
          inventoryEditorApi.container(data.branch.id, id, data.revision, form),
        ).then((ok) => {
          if (ok) close()
        })
      }}
    >
      <h4 className="font-bold">{id ? 'Редактирование шкафа' : 'Новый шкаф'}</h4>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Код шкафа">
          <Input
            aria-label="Код шкафа"
            value={form.code}
            maxLength={40}
            required
            onChange={(e) => setForm({ ...form, code: e.target.value })}
          />
        </Field>
        <Field label="Название шкафа">
          <Input
            aria-label="Название шкафа"
            value={form.name}
            maxLength={120}
            required
            onChange={(e) => setForm({ ...form, name: e.target.value })}
          />
        </Field>
        <Field label="Тип оборудования">
          <select
            aria-label="Тип оборудования"
            className="inp"
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value })}
          >
            {[
              ['cabinet', 'Шкаф'],
              ['zone', 'Зона'],
              ['wall', 'Стена'],
              ['stand', 'Стойка'],
            ].map(([value, name]) => (
              <option value={value} key={value}>
                {name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Категория шкафа">
          <select
            aria-label="Категория шкафа"
            className="inp"
            value={form.category || ''}
            onChange={(e) => setForm({ ...form, category: e.target.value || null })}
          >
            <option value="">Без категории</option>
            {['A', 'B', 'C'].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </Field>
        <Field label="Порядок шкафа">
          <Input
            aria-label="Порядок шкафа"
            type="number"
            min={0}
            max={999}
            value={form.position}
            onChange={(e) => setForm({ ...form, position: Number(e.target.value) })}
          />
        </Field>
      </div>
      <label className="flex gap-2 text-sm">
        <input
          type="checkbox"
          checked={form.active}
          onChange={(e) => setForm({ ...form, active: e.target.checked })}
        />
        Шкаф активен
      </label>
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          Сохранить шкаф
        </Button>
        <Button type="button" variant="outline" onClick={close} disabled={busy}>
          Отмена
        </Button>
      </div>
    </form>
  )
}
function ShelfForm({
  data,
  busy,
  write,
  id,
  close,
}: EditorProps & { id: string | null; close: () => void }) {
  const old = data.shelves.find((item) => item.shelf.id === id)
  const [form, setForm] = useState<ShelfInput>({
    containerId: old?.shelf.containerId || null,
    code: old?.shelf.code || '',
    title: old?.shelf.title || '',
    category: old?.shelf.category || 'AA',
    type: old?.shelf.type || 'shelf',
    width: old?.shelf.width || 0.8,
    position: old?.shelf.position || 0,
    maxProducts: old?.maxProducts || 20,
    active: old?.shelf.active ?? true,
    ...(!id ? { initialPrice: 10000 } : {}),
  })
  return (
    <form
      className="mt-4 space-y-3 rounded-xl bg-paper-input p-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (
          !form.active &&
          !window.confirm('Архивировать место? ID, фотографии и история размещений сохранятся.')
        )
          return
        void write(() => inventoryEditorApi.shelf(data.branch.id, id, data.revision, form)).then(
          (ok) => {
            if (ok) close()
          },
        )
      }}
    >
      <h4 className="font-bold">{id ? 'Редактирование места' : 'Новое рекламное место'}</h4>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Код места">
          <Input
            aria-label="Код места"
            required
            maxLength={80}
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value })}
          />
        </Field>
        <Field label="Название места">
          <Input
            aria-label="Название места"
            maxLength={255}
            value={form.title || ''}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
          />
        </Field>
        <Field label="Шкаф места">
          <select
            className="inp"
            aria-label="Шкаф места"
            value={form.containerId || ''}
            onChange={(e) => setForm({ ...form, containerId: e.target.value || null })}
          >
            <option value="">Без шкафа</option>
            {data.containers
              .filter((item) => item.active || item.id === form.containerId)
              .map((item) => (
                <option value={item.id} key={item.id}>
                  {item.code}
                </option>
              ))}
          </select>
        </Field>
        <Field label="Категория места">
          <select
            className="inp"
            aria-label="Категория места"
            value={form.category}
            onChange={(e) => setForm({ ...form, category: e.target.value })}
          >
            {categories.map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </Field>
        <Field label="Тип места">
          <select
            className="inp"
            aria-label="Тип места"
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value })}
          >
            {[
              ['shelf', 'Полка'],
              ['screen', 'Экран'],
              ['tv', 'ТВ'],
            ].map(([value, name]) => (
              <option value={value} key={value}>
                {name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Ширина, м">
          <Input
            aria-label="Ширина, м"
            type="number"
            step="0.01"
            min={0.25}
            max={20}
            required
            value={form.width}
            onChange={(e) => setForm({ ...form, width: Number(e.target.value) })}
          />
        </Field>
        <Field label="Товаров на полке">
          <Input
            aria-label="Товаров на полке"
            type="number"
            min={1}
            max={20}
            required
            value={form.maxProducts}
            onChange={(e) => setForm({ ...form, maxProducts: Number(e.target.value) })}
          />
        </Field>
        <Field label="Порядок места">
          <Input
            aria-label="Порядок места"
            type="number"
            min={0}
            max={999}
            value={form.position}
            onChange={(e) => setForm({ ...form, position: Number(e.target.value) })}
          />
        </Field>
        {!id && (
          <Field label="Начальная цена, ₸">
            <Input
              aria-label="Начальная цена, ₸"
              type="number"
              min={10000}
              step={1000}
              required
              value={form.initialPrice}
              onChange={(e) => setForm({ ...form, initialPrice: Number(e.target.value) })}
            />
          </Field>
        )}
      </div>
      <label className="flex gap-2 text-sm">
        <input
          type="checkbox"
          checked={form.active}
          onChange={(e) => setForm({ ...form, active: e.target.checked })}
        />
        Место активно
      </label>
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          Сохранить место
        </Button>
        <Button type="button" variant="outline" onClick={close} disabled={busy}>
          Отмена
        </Button>
      </div>
    </form>
  )
}
function ConfigureForm({
  data,
  busy,
  write,
  id,
  close,
}: EditorProps & { id: string; close: () => void }) {
  const existing = Object.fromEntries(
    categories.map((category) => [
      category,
      data.shelves.filter(
        (item) =>
          item.shelf.containerId === id && item.shelf.category === category && item.shelf.active,
      ).length,
    ]),
  )
  const [counts, setCounts] = useState(existing)
  const [price, setPrice] = useState(10000)
  const adding = categories.some((category) => counts[category] > existing[category])
  return (
    <form
      className="mt-4 space-y-3 rounded-xl bg-paper-input p-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (
          !window.confirm(
            'Применить количество полок? Свободные лишние места уйдут в архив, действующие размещения сохранятся.',
          )
        )
          return
        void write(() =>
          inventoryEditorApi.configure(
            data.branch.id,
            id,
            data.revision,
            counts,
            adding ? price : null,
          ),
        ).then((ok) => {
          if (ok) close()
        })
      }}
    >
      <h4 className="font-bold">Количество полок по категориям</h4>
      <div className="grid grid-cols-3 gap-3">
        {categories.map((category) => (
          <Field key={category} label={category}>
            <Input
              aria-label={`Количество ${category}`}
              type="number"
              min={0}
              max={data.canConfigurePrices ? 50 : existing[category]}
              value={counts[category]}
              required
              onChange={(e) => setCounts({ ...counts, [category]: Number(e.target.value) })}
            />
          </Field>
        ))}
      </div>
      {adding && (
        <Field label="Начальная цена новых полок, ₸">
          <Input
            aria-label="Начальная цена новых полок, ₸"
            type="number"
            min={10000}
            value={price}
            required
            onChange={(e) => setPrice(Number(e.target.value))}
          />
        </Field>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={busy || (adding && !data.canConfigurePrices)}>
          Применить количество
        </Button>
        <Button type="button" variant="outline" onClick={close} disabled={busy}>
          Отмена
        </Button>
      </div>
    </form>
  )
}
