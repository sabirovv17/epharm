import { useState } from 'react'
import { Button, Field, Input, Select } from '@/ui'
import { inventoryEditorApi, type FloorPlan } from './api'
import type { EditorProps } from './InventoryEditor'

const initialPlan: FloorPlan = {
  version: 3,
  room: { width: 18, depth: 11 },
  fixtures: [
    {
      id: 'cashier-1',
      type: 'cashier',
      label: 'Касса',
      x: 9,
      y: 1,
      width: 4.2,
      depth: 1,
      orientation: 'horizontal',
    },
    {
      id: 'entrance-1',
      type: 'entrance',
      label: 'Вход',
      x: 9,
      y: 10.5,
      width: 2.6,
      depth: 0.5,
      orientation: 'horizontal',
    },
  ],
  cabinetPositions: [],
}
export default function InventoryFloorPlan({ data, write, busy }: EditorProps) {
  const saved = data.floorPlanDraft || data.floorPlan || initialPlan
  const [plan, setPlan] = useState<FloorPlan>(() => structuredClone(saved))
  const [selected, setSelected] = useState('')
  const [cabinet, setCabinet] = useState('')
  const dirty = JSON.stringify(plan) !== JSON.stringify(saved)
  const objects = [
    ...plan.fixtures.map((item) => ({ ...item, key: item.id, cabinet: false })),
    ...plan.cabinetPositions.map((item) => ({
      ...item,
      key: `cabinet:${item.cabinetCode}`,
      label: item.cabinetCode,
      type: 'cabinet',
      cabinet: true,
    })),
  ]
  const active = objects.find((item) => item.key === selected)
  const unplaced = data.containers.filter(
    (item) =>
      item.active &&
      item.type === 'cabinet' &&
      !plan.cabinetPositions.some((position) => position.cabinetCode === item.code),
  )
  function update(field: 'x' | 'y' | 'width' | 'depth', value: number) {
    if (!active || !data.canEditPassport) return
    if (active.cabinet)
      setPlan({
        ...plan,
        cabinetPositions: plan.cabinetPositions.map((item) =>
          `cabinet:${item.cabinetCode}` === selected ? { ...item, [field]: value } : item,
        ),
      })
    else
      setPlan({
        ...plan,
        fixtures: plan.fixtures.map((item) =>
          item.id === selected ? { ...item, [field]: value } : item,
        ),
      })
  }
  function remove() {
    if (!active) return
    if (active.cabinet)
      setPlan({
        ...plan,
        cabinetPositions: plan.cabinetPositions.filter(
          (item) => `cabinet:${item.cabinetCode}` !== selected,
        ),
      })
    else setPlan({ ...plan, fixtures: plan.fixtures.filter((item) => item.id !== selected) })
    setSelected('')
  }
  const canRemove =
    active?.cabinet ||
    (active?.type === 'entrance' &&
      plan.fixtures.filter((item) => item.type === 'entrance').length > 1)
  return (
    <div className="space-y-4">
      {data.floorPlanWarning && (
        <p role="alert" className="rounded-xl bg-surface-warning p-3">
          {data.floorPlanWarning}
        </p>
      )}
      <p className="text-sm text-ink-500">
        Сначала разместите шкафы и сохраните черновик. Публикация — отдельное действие; удаление
        объекта со схемы не удаляет шкаф или его историю.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Ширина помещения, м">
          <Input
            aria-label="Ширина помещения, м"
            type="number"
            min={10}
            max={40}
            step={0.1}
            value={plan.room.width}
            disabled={!data.canEditPassport || busy}
            onChange={(e) =>
              setPlan({ ...plan, room: { ...plan.room, width: Number(e.target.value) } })
            }
          />
        </Field>
        <Field label="Глубина помещения, м">
          <Input
            aria-label="Глубина помещения, м"
            type="number"
            min={7}
            max={30}
            step={0.1}
            value={plan.room.depth}
            disabled={!data.canEditPassport || busy}
            onChange={(e) =>
              setPlan({ ...plan, room: { ...plan.room, depth: Number(e.target.value) } })
            }
          />
        </Field>
        {data.canEditPassport && (
          <>
            <Field label="Шкаф для схемы">
              <select
                className="inp"
                aria-label="Шкаф для схемы"
                value={cabinet}
                onChange={(e) => setCabinet(e.target.value)}
              >
                <option value="">Выберите шкаф</option>
                {unplaced.map((item) => (
                  <option key={item.id} value={item.code}>
                    {item.code} · {item.name}
                  </option>
                ))}
              </select>
            </Field>
            <Button
              variant="outline"
              disabled={busy || !cabinet}
              onClick={() => {
                setPlan({
                  ...plan,
                  cabinetPositions: [
                    ...plan.cabinetPositions,
                    {
                      cabinetCode: cabinet,
                      x: plan.room.width / 2,
                      y: plan.room.depth / 2,
                      width: 1.4,
                      depth: 0.55,
                      orientation: 'horizontal',
                      zone: 'custom',
                    },
                  ],
                })
                setSelected(`cabinet:${cabinet}`)
                setCabinet('')
              }}
            >
              Разместить шкаф
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                const id = crypto.randomUUID()
                setPlan({
                  ...plan,
                  fixtures: [
                    ...plan.fixtures,
                    {
                      id,
                      type: 'entrance',
                      label: 'Вход',
                      x: 2,
                      y: plan.room.depth - 0.3,
                      width: 2.6,
                      depth: 0.5,
                      orientation: 'horizontal',
                    },
                  ],
                })
                setSelected(id)
              }}
            >
              Добавить вход
            </Button>
          </>
        )}
      </div>
      <div className="max-w-md">
        <Field
          label="Объект схемы"
          hint="Выберите объект в списке или на схеме, чтобы увидеть его параметры."
        >
          <Select
            ariaLabel="Объект схемы"
            value={selected}
            onChange={setSelected}
            placeholder="Выберите объект"
            options={objects.map((item) => ({ value: item.key, label: item.label }))}
          />
        </Field>
      </div>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_260px]">
        <div
          className="relative min-h-[260px] overflow-hidden rounded-xl border-2 border-ink-300 bg-paper-input"
          style={{
            aspectRatio: `${Math.max(1, plan.room.width)} / ${Math.max(1, plan.room.depth)}`,
          }}
          aria-label="План помещения"
        >
          {objects.map((item) => (
            <button
              key={item.key}
              type="button"
              aria-label={`Объект ${item.label}`}
              aria-pressed={selected === item.key}
              onClick={() => setSelected(item.key)}
              className={`absolute overflow-hidden rounded border text-[10px] font-bold focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-green-600 ${selected === item.key ? 'border-brand-green-600 bg-brand-green-100' : item.type === 'cabinet' ? 'border-ink-300 bg-white' : 'border-recommendation-green-dark bg-accent-success/10'}`}
              style={{
                left: `${((item.x - item.width / 2) / plan.room.width) * 100}%`,
                top: `${((item.y - item.depth / 2) / plan.room.depth) * 100}%`,
                width: `${(item.width / plan.room.width) * 100}%`,
                height: `${(item.depth / plan.room.depth) * 100}%`,
                minHeight: 10,
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="rounded-xl border border-ink-100 p-4">
          {active ? (
            <>
              <h3 className="mb-3 font-bold">{active.label}</h3>
              <div className="grid grid-cols-2 gap-3">
                {(['x', 'y', 'width', 'depth'] as const).map((field) => (
                  <Field
                    key={field}
                    label={
                      { x: 'Позиция X', y: 'Позиция Y', width: 'Ширина', depth: 'Глубина' }[field]
                    }
                  >
                    <Input
                      aria-label={
                        {
                          x: 'Позиция X',
                          y: 'Позиция Y',
                          width: 'Ширина объекта',
                          depth: 'Глубина объекта',
                        }[field]
                      }
                      type="number"
                      step={0.1}
                      value={active[field]}
                      disabled={!data.canEditPassport || busy}
                      onChange={(e) => update(field, Number(e.target.value))}
                    />
                  </Field>
                ))}
              </div>
              {data.canEditPassport && (
                <div className="mt-4 flex flex-col gap-2">
                  <Button
                    variant="outline"
                    disabled={busy}
                    onClick={() => {
                      if (active.cabinet)
                        setPlan({
                          ...plan,
                          cabinetPositions: plan.cabinetPositions.map((item) =>
                            `cabinet:${item.cabinetCode}` === selected
                              ? {
                                  ...item,
                                  width: item.depth,
                                  depth: item.width,
                                  orientation:
                                    item.orientation === 'vertical' ? 'horizontal' : 'vertical',
                                }
                              : item,
                          ),
                        })
                      else
                        setPlan({
                          ...plan,
                          fixtures: plan.fixtures.map((item) =>
                            item.id === selected
                              ? {
                                  ...item,
                                  width: item.depth,
                                  depth: item.width,
                                  orientation:
                                    item.orientation === 'vertical' ? 'horizontal' : 'vertical',
                                }
                              : item,
                          ),
                        })
                    }}
                  >
                    Повернуть
                  </Button>
                  <Button variant="outline" disabled={busy || !canRemove} onClick={remove}>
                    Убрать со схемы
                  </Button>
                </div>
              )}
            </>
          ) : (
            <p className="text-sm text-ink-500">
              Выберите объект на плане и задайте размеры и позицию в метрах.
            </p>
          )}
        </div>
      </div>
      <p className="text-sm text-ink-500">
        Не размещены шкафы: {unplaced.length}.{' '}
        {data.floorPlanDraft
          ? 'Есть сохранённый черновик.'
          : data.publishedAt
            ? 'Схема опубликована.'
            : 'Схема ещё не опубликована.'}
      </p>
      {data.canEditPassport && (
        <div className="flex flex-wrap gap-3">
          <Button
            disabled={busy || (!dirty && !!data.floorPlanDraft)}
            onClick={() =>
              void write(() => inventoryEditorApi.floor(data.branch.id, data.revision, plan))
            }
          >
            Сохранить черновик схемы
          </Button>
          <Button
            variant="outline"
            disabled={busy || dirty || !data.floorPlanDraft}
            onClick={() => {
              if (
                window.confirm(
                  'Опубликовать сохранённую схему? Предыдущие версии останутся в истории.',
                )
              )
                void write(() => inventoryEditorApi.publishFloor(data.branch.id, data.revision))
            }}
          >
            Опубликовать схему
          </Button>
          {dirty && (
            <span className="self-center text-sm text-ink-500">
              Перед публикацией сохраните изменения.
            </span>
          )}
        </div>
      )}
    </div>
  )
}
