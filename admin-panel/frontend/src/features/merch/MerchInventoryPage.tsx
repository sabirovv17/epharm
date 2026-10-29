import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import { Button, Field, Input, Modal, PageHeader, Select } from '@/ui'
import {
  inventoryApi,
  type AvailabilityRequest,
  type BlockReason,
  type MerchCalendar,
  type MerchInventory,
  type MerchStatus,
} from './inventory-api'

const statusLabels: Record<MerchStatus, string> = {
  available: 'Свободно',
  blocked: 'Заблокировано',
  held: 'В резерве',
  booked: 'Занято',
}
const statusClass: Record<MerchStatus, string> = {
  available: 'bg-accent-success/10 text-recommendation-green-dark',
  blocked: 'bg-paper-input text-ink-600',
  held: 'bg-surface-warning text-surface-warning-strong',
  booked: 'bg-ink-100 text-ink-700',
}
const monthLabel = (month: string) =>
  new Date(`${month}T12:00:00`).toLocaleDateString('ru-RU', { month: 'short', year: 'numeric' })
const key = ['admin-access', 'merch-inventory'] as const

export default function MerchInventoryPage() {
  const capability = useAdministrativeCapabilities()
  if (capability.isPending || (capability.isFetching && !capability.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!capability.canViewMerchInventory)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Мерчендайзинг недоступен</h1>
        <p className="mt-2 text-ink-500">Нет подтверждённого доступа к аптекам мерча.</p>
        {capability.isError && (
          <Button variant="outline" onClick={() => capability.refetch()}>
            Повторить проверку
          </Button>
        )}
      </div>
    )
  return <InventoryWorkspace />
}

function InventoryWorkspace() {
  const [offset, setOffset] = useState(0)
  const [branchId, setBranchId] = useState('')
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7))
  const branches = useQuery({
    queryKey: [...key, 'branches', offset],
    queryFn: ({ signal }) => inventoryApi.branches(offset, signal),
    retry: false,
    placeholderData: undefined,
  })
  const inventory = useQuery({
    queryKey: [...key, branchId, 'inventory'],
    queryFn: ({ signal }) => inventoryApi.inventory(branchId, signal),
    enabled: !!branchId,
    retry: false,
    placeholderData: undefined,
  })
  const calendar = useQuery({
    queryKey: [...key, branchId, month],
    queryFn: ({ signal }) => inventoryApi.calendar(branchId, `${month}-01`, signal),
    enabled: !!branchId && /^\d{4}-\d{2}$/.test(month),
    retry: false,
    placeholderData: undefined,
  })
  const freshCalendar =
    calendar.isSuccess && calendar.isFetchedAfterMount && !calendar.isError ? calendar.data : null
  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Календарь и доступность"
        subtitle="Аптеки мерча, оборудование и доступность полок. Занятые и зарезервированные размещения защищены."
      />
      <div className="card flex flex-wrap items-end gap-3 p-4">
        <Field label="Аптека мерча">
          <Select
            ariaLabel="Аптека мерча"
            value={branchId}
            onChange={setBranchId}
            options={(branches.isError ? [] : (branches.data?.items ?? [])).map((branch) => ({
              value: branch.id,
              label: `${branch.city} · ${branch.name} · ${branch.address}`,
            }))}
            className="min-w-[340px]"
          />
        </Field>
        <Field label="Начало календаря">
          <Input
            aria-label="Начало календаря"
            type="month"
            value={month}
            onChange={(event) => setMonth(event.target.value)}
          />
        </Field>
        <Button
          variant="outline"
          disabled={offset === 0 || branches.isFetching}
          onClick={() => setOffset((value) => Math.max(0, value - 50))}
        >
          Назад
        </Button>
        <Button
          variant="outline"
          disabled={!branches.data || offset + 50 >= branches.data.total || branches.isFetching}
          onClick={() => setOffset((value) => value + 50)}
        >
          Далее
        </Button>
        {branches.isSuccess && (
          <span className="pb-2 text-[12px] text-ink-500">
            Доступно аптек: {branches.data.total}
          </span>
        )}
      </div>
      {branches.isPending && <p role="status">Загружаем аптеки…</p>}
      {branches.isError && <ErrorPanel error={branches.error} retry={() => branches.refetch()} />}
      {branches.isSuccess && !branches.data.items.length && (
        <div className="card p-6">В вашей области нет аптек мерча.</div>
      )}
      {!branchId && !!branches.data?.items.length && (
        <div className="card p-6 text-ink-500">
          Выберите аптеку для просмотра оборудования и календаря.
        </div>
      )}
      {branchId && (inventory.isPending || calendar.isPending) && (
        <p role="status">Загружаем оборудование и доступность…</p>
      )}
      {inventory.isError && (
        <ErrorPanel error={inventory.error} retry={() => inventory.refetch()} />
      )}
      {calendar.isError && <ErrorPanel error={calendar.error} retry={() => calendar.refetch()} />}
      {freshCalendar &&
        inventory.isSuccess &&
        inventory.isFetchedAfterMount &&
        !inventory.isError && (
          <CalendarWorkspace
            key={`${branchId}:${month}`}
            calendar={freshCalendar}
            inventory={inventory.data}
            refresh={() => calendar.refetch()}
          />
        )}
    </div>
  )
}

function ErrorPanel({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div role="alert" className="card flex items-center justify-between gap-3 p-4">
      <p>{describeError(error)}</p>
      <Button variant="outline" onClick={retry}>
        Повторить
      </Button>
    </div>
  )
}

function CalendarWorkspace({
  calendar,
  inventory,
  refresh,
}: {
  calendar: MerchCalendar
  inventory: MerchInventory
  refresh: () => void
}) {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<string[]>([])
  const [fromMonth, setFromMonth] = useState(calendar.months[0]?.month ?? '')
  const [toMonth, setToMonth] = useState(calendar.months[0]?.month ?? '')
  const [action, setAction] = useState<'block' | 'unblock'>('block')
  const [reason, setReason] = useState<BlockReason>('repair')
  const [note, setNote] = useState('')
  const [review, setReview] = useState<AvailabilityRequest | null>(null)
  const mutation = useMutation({
    mutationFn: (request: AvailabilityRequest) => inventoryApi.change(calendar.branch.id, request),
    onSuccess: () => {
      setReview(null)
      setSelected([])
      queryClient.invalidateQueries({ queryKey: [...key, calendar.branch.id] })
    },
    onError: () => {
      setReview(null)
      refresh()
    },
  })
  const months = calendar.months.map((item) => ({
    value: item.month,
    label: monthLabel(item.month),
  }))
  const invalid =
    !selected.length || !fromMonth || !toMonth || fromMonth > toMonth || note.length > 300
  return (
    <>
      <div className="card p-4">
        <h2 className="font-bold">{calendar.branch.name}</h2>
        <p className="mt-1 text-[13px] text-ink-500">
          {calendar.branch.city} · {calendar.branch.address} · {calendar.branch.organizationName}
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          {inventory.containers.map((container) => (
            <span key={container.id} className="rounded-md bg-paper-input px-2 py-1 text-[12px]">
              {container.name} · {container.code}
              {!container.active && ' · Неактивно'}
            </span>
          ))}
        </div>
      </div>
      <div className="card overflow-x-auto">
        <table className="w-full text-left text-[12px]">
          <thead className="bg-paper-input">
            <tr>
              <th className="p-3">Полка</th>
              <th className="p-3">Цена / месяц</th>
              {calendar.months.map((item) => (
                <th key={item.month} className="min-w-[140px] p-3">
                  <div>{monthLabel(item.month)}</div>
                  <div className="mt-1 font-normal text-ink-500">
                    Свободно: {item.availableCount}
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {calendar.shelves.map((shelf) => (
              <tr key={shelf.id} className="border-t border-paper-line">
                <td className="p-3">
                  <label className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      aria-label={`Выбрать полку ${shelf.code}`}
                      checked={selected.includes(shelf.id)}
                      disabled={
                        !calendar.canManage ||
                        mutation.isPending ||
                        !shelf.active ||
                        !shelf.months.some((cell) => cell.canEdit)
                      }
                      onChange={(event) =>
                        setSelected((current) =>
                          event.target.checked
                            ? [...current, shelf.id]
                            : current.filter((id) => id !== shelf.id),
                        )
                      }
                    />
                    <span className="font-bold">{shelf.code}</span>
                  </label>
                  <p className="mt-1 text-ink-500">
                    {shelf.category} · {shelf.width} см{!shelf.active && ' · Неактивна'}
                  </p>
                </td>
                <td className="p-3">
                  {calendar.canViewPrices && shelf.price !== null ? formatKzt(shelf.price) : '—'}
                </td>
                {shelf.months.map((cell) => (
                  <td key={cell.month} className="p-2 align-top">
                    <div className={`rounded-lg p-2 ${statusClass[cell.status]}`}>
                      <div className="font-semibold">{statusLabels[cell.status]}</div>
                      {cell.placement && (
                        <>
                          <div className="mt-1">{cell.placement.portionLabel}</div>
                          {cell.placement.items.map((item) => (
                            <div key={item.id}>{item.advertiserName}</div>
                          ))}
                        </>
                      )}
                      {cell.note && <div className="mt-1">{cell.note}</div>}
                      {!cell.canEdit && (
                        <div className="mt-1 text-[10px]">Без изменения доступности</div>
                      )}
                    </div>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {!calendar.shelves.length && (
          <p className="p-6 text-ink-500">Оборудование и полки пока не добавлены.</p>
        )}
      </div>
      {!calendar.canViewPrices && (
        <p className="text-[12px] text-ink-500">Цены недоступны с вашими правами.</p>
      )}
      {calendar.canManage ? (
        <div className="card flex flex-col gap-3 p-4">
          <h3 className="font-bold">Изменить доступность выбранных полок</h3>
          <p className="text-[12px] text-ink-500">
            Занятые, зарезервированные и защищённые размещения сохранятся. Это действие не создаёт
            бронирование.
          </p>
          <div className="grid grid-cols-4 gap-3">
            <Field label="Действие">
              <Select
                value={action}
                onChange={(value) => setAction(value as typeof action)}
                options={[
                  { value: 'block', label: 'Заблокировать' },
                  { value: 'unblock', label: 'Разблокировать' },
                ]}
              />
            </Field>
            <Field label="С месяца">
              <Select
                ariaLabel="С месяца"
                value={fromMonth}
                onChange={setFromMonth}
                options={months}
              />
            </Field>
            <Field label="По месяц">
              <Select ariaLabel="По месяц" value={toMonth} onChange={setToMonth} options={months} />
            </Field>
            {action === 'block' && (
              <Field label="Причина">
                <Select
                  value={reason}
                  onChange={(value) => setReason(value as BlockReason)}
                  options={[
                    { value: 'repair', label: 'Ремонт' },
                    { value: 'own_promo', label: 'Собственная акция' },
                    { value: 'reserve', label: 'Резерв' },
                  ]}
                />
              </Field>
            )}
          </div>
          <Field label="Комментарий" optional>
            <Input
              aria-label="Комментарий доступности"
              maxLength={300}
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
          </Field>
          <div>
            <Button
              disabled={invalid || mutation.isPending}
              onClick={() =>
                setReview({
                  shelfIds: [...selected],
                  fromMonth,
                  toMonth,
                  action,
                  reason: action === 'block' ? reason : null,
                  note: note.trim() || null,
                })
              }
            >
              Проверить изменение
            </Button>
            <span className="ml-3 text-[12px] text-ink-500">Выбрано полок: {selected.length}</span>
          </div>
        </div>
      ) : (
        <p className="text-[13px] text-ink-500">Календарь доступен только для просмотра.</p>
      )}
      {mutation.isError && (
        <p role="alert" className="text-accent-danger">
          {describeError(mutation.error)} Изменение не повторяется автоматически. Проверьте
          обновлённый календарь.
        </p>
      )}
      {mutation.isSuccess && (
        <p role="status" className="card p-4">
          Изменено: {mutation.data.changedCount}; защищено: {mutation.data.protectedCount}; без
          изменений: {mutation.data.unchangedCount}.
        </p>
      )}
      {review && calendar.canManage && (
        <Modal
          open
          title="Подтвердите изменение доступности"
          onClose={() => {
            if (!mutation.isPending) setReview(null)
          }}
          footer={
            <>
              <Button variant="ghost" disabled={mutation.isPending} onClick={() => setReview(null)}>
                Отмена
              </Button>
              <Button disabled={mutation.isPending} onClick={() => mutation.mutate(review)}>
                {mutation.isPending ? 'Сохраняем…' : 'Подтвердить'}
              </Button>
            </>
          }
        >
          <p>
            {review.action === 'block' ? 'Заблокировать' : 'Разблокировать'} полок:{' '}
            {review.shelfIds.length}. Период: {monthLabel(review.fromMonth)} —{' '}
            {monthLabel(review.toMonth)}.
          </p>
          <p className="mt-3 text-[13px] text-ink-500">
            Сервер повторно проверит доступ и защищённые размещения перед сохранением.
          </p>
        </Modal>
      )}
    </>
  )
}
