import { useEffect, useRef, useState } from 'react'
import { Button, Field, Input } from '@/ui'
import type { PlacementCatalog, PlacementGateway } from './placements-api'
import type { CommercialProduct, CommercialShelf } from './producer-commercial-api'
import PlanningMap from './PlanningMap'
import { exactKzt, suggestPlaces } from './planning-selection'

export default function PlacementPlanningSelector({
  catalog,
  gateway,
  producerId,
  month,
  product,
  selected,
  branchId,
  onBranch,
  onChoose,
  disabled = false,
}: {
  catalog: PlacementCatalog | null
  gateway: PlacementGateway
  producerId: string
  month: string
  product?: CommercialProduct
  selected: CommercialShelf[]
  branchId: string
  onBranch: (id: string) => void
  onChoose: (rows: CommercialShelf[]) => void
  disabled?: boolean
}) {
  const [count, setCount] = useState('5')
  const [budget, setBudget] = useState('100000')
  const [city, setCity] = useState('')
  const [cabinet, setCabinet] = useState('')
  const [preview, setPreview] = useState<{
    identity: string
    rows: CommercialShelf[]
    total: bigint
    checked: number
    complete: boolean
  } | null>(null)
  const [pending, setPending] = useState('')
  const [progress, setProgress] = useState(0)
  const [failure, setFailure] = useState('')
  const controller = useRef<AbortController | null>(null)
  const identity = JSON.stringify([
    gateway.realm,
    producerId,
    month,
    product,
    selected,
    count,
    budget,
    city,
    disabled,
  ])
  useEffect(() => () => controller.current?.abort(), [identity, gateway])
  const visible = preview?.identity === identity ? preview : null
  const busy = pending === identity
  const branch = catalog?.branches.find((row) => row.id === branchId)
  const plan = branch?.floorPlan
  const choose = (id: string) => {
    setCabinet('')
    onBranch(id)
  }
  const propose = async () => {
    if (!product || disabled || busy) return
    const money = exactKzt(budget)
    if (money == null) {
      setFailure('Бюджет — целое неотрицательное число.')
      return
    }
    controller.current?.abort()
    const request = new AbortController()
    controller.current = request
    setPending(identity)
    setFailure('')
    setPreview(null)
    setProgress(0)
    try {
      const result = await suggestPlaces(
        gateway,
        producerId,
        month,
        product,
        selected,
        Number(count),
        money,
        city.trim(),
        request.signal,
        setProgress,
      )
      if (!request.signal.aborted) setPreview({ ...result, identity })
    } catch (error) {
      if (!request.signal.aborted)
        setFailure(
          error instanceof Error ? error.message : 'Подбор не выполнен. Выбранные места сохранены.',
        )
    } finally {
      if (!request.signal.aborted) setPending('')
    }
  }
  const addShelf = (shelfId: string) => {
    const place = branch?.items.find((row) => row.shelfId === shelfId)
    if (
      !place ||
      !branch ||
      !product ||
      disabled ||
      !branch.canCreate ||
      !branch.canViewPrices ||
      !product.activeMonths.includes(month) ||
      !place.whole.available ||
      exactKzt(place.whole.price) == null
    )
      return
    if (selected.some((row) => row.shelfId === shelfId && row.placementMonths.includes(month)))
      return
    onChoose([
      {
        id: crypto.randomUUID(),
        shelfId,
        pharmacyId: branch.id,
        portion: 'whole',
        placementMonths: [month],
        products: [structuredClone(product)],
        price: place.whole.price,
        code: place.code,
        pharmacyName: branch.name,
        city: branch.city,
        address: branch.address,
      },
    ])
  }
  return (
    <section
      aria-label="Карта и подбор мест"
      className="space-y-4 rounded-xl border border-paper-line p-3"
    >
      <h3 className="font-bold">Карта, схема аптеки и автоподбор</h3>
      <PlanningMap branches={catalog?.branches ?? []} selected={branchId} onSelect={choose} />
      {branch && (
        <div className="space-y-2">
          <h4 className="font-semibold">
            {branch.name} · {branch.address}
          </h4>
          {branch.floorPlanWarning && (
            <p className="text-sm text-ink-500">{branch.floorPlanWarning}</p>
          )}
          {plan && (
            <svg
              className="max-h-72 w-full rounded-lg bg-paper-input"
              viewBox={`0 0 ${plan.room.width} ${plan.room.depth}`}
              aria-label="Опубликованная схема аптеки"
            >
              {plan.fixtures.map((fixture) => (
                <g key={fixture.id}>
                  <rect
                    x={fixture.x - fixture.width / 2}
                    y={fixture.y - fixture.depth / 2}
                    width={fixture.width}
                    height={fixture.depth}
                    className="fill-ink-200"
                  />
                  <text x={fixture.x} y={fixture.y} fontSize={0.35} textAnchor="middle">
                    {fixture.label}
                  </text>
                </g>
              ))}
              {plan.cabinetPositions.map((position) => (
                <g
                  key={position.cabinetCode}
                  role="button"
                  tabIndex={0}
                  aria-label={`Шкаф ${position.cabinetCode}`}
                  onClick={() => setCabinet(position.cabinetCode)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      setCabinet(position.cabinetCode)
                    }
                  }}
                >
                  <title>Выбрать шкаф {position.cabinetCode}</title>
                  <rect
                    x={position.x - position.width / 2}
                    y={position.y - position.depth / 2}
                    width={position.width}
                    height={position.depth}
                    className={
                      cabinet === position.cabinetCode
                        ? 'fill-brand-green-600'
                        : 'fill-brand-green-500'
                    }
                  />
                  <text x={position.x} y={position.y} fontSize={0.35} textAnchor="middle">
                    {position.cabinetCode}
                  </text>
                </g>
              ))}
            </svg>
          )}
          {cabinet && (
            <div className="flex flex-wrap gap-2">
              {branch.items
                .filter((place) => place.containerCode === cabinet)
                .map((place) => (
                  <Button
                    key={place.shelfId}
                    type="button"
                    variant="outline"
                    disabled={
                      disabled ||
                      !product ||
                      !product.activeMonths.includes(month) ||
                      !branch.canCreate ||
                      !branch.canViewPrices ||
                      !place.whole.available ||
                      exactKzt(place.whole.price) == null ||
                      selected.some(
                        (row) =>
                          row.shelfId === place.shelfId && row.placementMonths.includes(month),
                      )
                    }
                    onClick={() => addShelf(place.shelfId)}
                  >
                    {place.code} · {place.category ?? 'Категория не указана'} ·{' '}
                    {place.whole.available ? 'Выбрать место целиком' : 'Занято'}
                  </Button>
                ))}
              {!branch.items.some((place) => place.containerCode === cabinet) && (
                <p>Для этого шкафа доступных мест в каталоге нет.</p>
              )}
            </div>
          )}
        </div>
      )}
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Аптек для предложения">
          <Input
            aria-label="Аптек для автоподбора"
            type="number"
            min={1}
            max={50}
            step={1}
            value={count}
            disabled={busy || disabled}
            onChange={(event) => setCount(event.target.value)}
          />
        </Field>
        <Field label="Бюджет новых мест за месяц, ₸">
          <Input
            aria-label="Бюджет автоподбора"
            inputMode="numeric"
            value={budget}
            disabled={busy || disabled}
            onChange={(event) => setBudget(event.target.value)}
          />
        </Field>
        <Field label="Город (точное название)" optional>
          <Input
            aria-label="Город автоподбора"
            value={city}
            disabled={busy || disabled}
            onChange={(event) => setCity(event.target.value)}
          />
        </Field>
        <Button
          type="button"
          disabled={disabled || !product || !product.activeMonths.includes(month) || busy}
          onClick={() => void propose()}
        >
          Предложить места
        </Button>
        {busy && (
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              controller.current?.abort()
              setPending('')
            }}
          >
            Остановить подбор
          </Button>
        )}
      </div>
      <p className="text-xs text-ink-500">
        Предложение на {month}: одно подходящее место на аптеку по доступности, категории товара и
        бюджету с фейсингами. Алгоритм последовательно выбирает самое дешёвое место в каждой аптеке.
        Это предварительный подбор; бронирование выполняется при отправке сохранённого плана.
      </p>
      {busy && <p role="status">Проверено аптек: {progress}…</p>}
      {failure && <p role="alert">{failure}</p>}
      {visible && (
        <div className="space-y-2">
          <p>
            Подобрано {visible.rows.length} из {count}; проверено аптек: {visible.checked};
            предварительно {new Intl.NumberFormat('ru-RU').format(visible.total)} ₸.
          </p>
          {!visible.complete && (
            <p role="status">
              Не хватает доступных мест в заданном бюджете и области. Можно изменить условия или
              явно добавить найденное.
            </p>
          )}
          <ul>
            {visible.rows.map((row) => (
              <li key={row.id}>
                {row.city} · {row.pharmacyName} · {row.code} ·{' '}
                {row.portion === 'whole'
                  ? 'целиком'
                  : row.portion === 'left'
                    ? 'левая половина'
                    : 'правая половина'}
              </li>
            ))}
          </ul>
          <Button
            type="button"
            disabled={disabled || !visible.rows.length}
            onClick={() => onChoose(visible.rows)}
          >
            Добавить предложенные места
          </Button>
        </div>
      )}
    </section>
  )
}
