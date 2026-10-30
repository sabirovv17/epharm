import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import ReportDownloadButton from '@/features/reports/ReportDownloadButton'
import { Button, Field, Input, Modal, PageHeader, Select } from '@/ui'
import InventoryEditor, { NetworkStructureButton } from './inventory-editor/InventoryEditor'
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
  if (capability.isError)
    return (
      <div className="card space-y-3 p-6">
        <h1 className="text-xl font-bold">Не удалось проверить доступ</h1>
        <p role="alert" className="text-sm text-ink-600">
          {describeError(capability.error)}
        </p>
        <Button
          variant="outline"
          disabled={capability.isFetching}
          onClick={() => void capability.refetch()}
        >
          Повторить проверку
        </Button>
      </div>
    )
  if (!capability.canViewMerchInventory)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Мерчендайзинг недоступен</h1>
        <p className="mt-2 text-ink-500">Нет подтверждённого доступа к аптекам мерча.</p>
      </div>
    )
  return <InventoryWorkspace canExport={capability.canExportMerchInventory} />
}

function InventoryWorkspace({ canExport }: { canExport: boolean }) {
  const [offset, setOffset] = useState(0)
  const [branchId, setBranchId] = useState('')
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7))
  const [editorOpen, setEditorOpen] = useState(false)
  const validMonth = /^\d{4}-\d{2}$/.test(month)
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
    enabled: !!branchId && validMonth,
    retry: false,
    placeholderData: undefined,
  })
  const freshCalendar =
    calendar.isSuccess && calendar.isFetchedAfterMount && !calendar.isError ? calendar.data : null
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <PageHeader
        title="Календарь и доступность"
        subtitle="Аптеки мерча, оборудование и доступность полок. Занятые и зарезервированные размещения защищены."
        actions={
          <ReportDownloadButton
            allowed={canExport}
            path="/api/admin/merch/exports/inventory.xlsx"
            params={{
              branchId: branchId || undefined,
              startMonth: freshCalendar?.startMonth,
              monthCount: freshCalendar ? String(freshCalendar.monthCount) : undefined,
            }}
            filename="merch-inventory.xlsx"
            label="Excel: доступность"
            invalid={
              !freshCalendar ||
              freshCalendar.branch.id !== branchId ||
              freshCalendar.startMonth !== `${month}-01`
                ? 'Сначала выберите аптеку и дождитесь актуального календаря'
                : undefined
            }
          />
        }
      />
      <section className="card min-w-0" aria-labelledby="inventory-filter-title">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-200 px-4 py-3">
          <div>
            <h2 id="inventory-filter-title" className="font-bold">
              Аптека и период
            </h2>
            <p className="mt-1 text-xs text-ink-500">
              Выберите аптеку, затем начало календаря доступности.
            </p>
          </div>
          <NetworkStructureButton
            onChanged={() => {
              void branches.refetch()
            }}
          />
        </div>
        <div className="grid min-w-0 gap-4 p-4 md:grid-cols-[minmax(0,1fr)_220px]">
          <Field label="Аптека мерча">
            <Select
              ariaLabel="Аптека мерча"
              value={branchId}
              onChange={setBranchId}
              options={(branches.isError ? [] : (branches.data?.items ?? [])).map((branch) => ({
                value: branch.id,
                label: `${branch.city} · ${branch.name} · ${branch.address}`,
              }))}
              className="min-w-0 w-full"
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
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-200 px-4 py-3">
          <p className="text-xs text-ink-500">
            {branches.isSuccess
              ? `Доступно аптек: ${branches.data.total}`
              : 'Список аптек в вашей области доступа'}
          </p>
          <div className="flex items-center gap-2" role="group" aria-label="Страницы аптек">
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
          </div>
        </div>
      </section>
      {branches.isPending && <p role="status">Загружаем аптеки…</p>}
      {branches.isError && <ErrorPanel error={branches.error} retry={() => branches.refetch()} />}
      {branches.isSuccess && !branches.data.items.length && (
        <div className="card p-6" role="status">
          В вашей области нет аптек мерча.
        </div>
      )}
      {!branchId && !!branches.data?.items.length && (
        <div className="card p-6 text-ink-500" role="status">
          Выберите аптеку для просмотра оборудования и календаря.
        </div>
      )}
      {branchId && !validMonth && (
        <p role="alert" className="card p-4 text-sm text-ink-600">
          Укажите начало календаря, чтобы загрузить доступность.
        </p>
      )}
      {branchId && validMonth && (inventory.isPending || calendar.isPending) && (
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
          <>
            <CalendarWorkspace
              key={`${branchId}:${month}`}
              calendar={freshCalendar}
              inventory={inventory.data}
              refresh={() => calendar.refetch()}
              onEdit={() => setEditorOpen(true)}
            />
            {editorOpen && (
              <InventoryEditor
                key={branchId}
                branchId={branchId}
                month={month}
                onClose={() => setEditorOpen(false)}
                onChanged={() => {
                  void inventory.refetch()
                  void calendar.refetch()
                  void branches.refetch()
                }}
              />
            )}
          </>
        )}
    </div>
  )
}

function ErrorPanel({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div role="alert" className="card flex flex-wrap items-center justify-between gap-3 p-4">
      <p className="min-w-0 break-words text-sm">{describeError(error)}</p>
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
  onEdit,
}: {
  calendar: MerchCalendar
  inventory: MerchInventory
  refresh: () => void
  onEdit: () => void
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
      <section className="card min-w-0" aria-label="Доступность выбранной аптеки">
        <div className="border-b border-ink-200 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="break-words text-lg font-bold">{calendar.branch.name}</h2>
              <p className="mt-1 break-words text-[13px] text-ink-500">
                {calendar.branch.city} · {calendar.branch.address} ·{' '}
                {calendar.branch.organizationName}
              </p>
              <p className="mt-2 text-xs text-ink-600">
                Полок: <span className="num font-semibold">{calendar.shelves.length}</span>
                {calendar.months.length > 0 &&
                  ` · ${monthLabel(calendar.months[0].month)} — ${monthLabel(calendar.months[calendar.months.length - 1].month)}`}
              </p>
            </div>
            <Button variant="outline" onClick={onEdit}>
              Паспорт и рекламные места
            </Button>
          </div>
          {inventory.containers.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {inventory.containers.map((container) => (
                <span
                  key={container.id}
                  className="rounded-md bg-paper-input px-2 py-1 text-[12px]"
                >
                  {container.name} · {container.code}
                  {!container.active && ' · Неактивно'}
                </span>
              ))}
            </div>
          )}
        </div>
        <div className="space-y-2 border-b border-ink-200 px-4 py-3">
          <ul
            className="flex flex-wrap items-center gap-x-4 gap-y-2"
            aria-label="Статусы доступности"
          >
            {(Object.keys(statusLabels) as MerchStatus[]).map((status) => (
              <li
                key={status}
                className={`rounded-md px-2 py-1 text-xs font-semibold ${statusClass[status]}`}
              >
                {statusLabels[status]}
              </li>
            ))}
          </ul>
          <p id="inventory-calendar-help" className="text-xs leading-5 text-ink-500">
            Статус указан для каждой полки и месяца. Резерв и занятые места защищены от изменения
            доступности.
            {calendar.shelves.length > 0 &&
              ' Прокрутите календарь по горизонтали, чтобы увидеть остальные месяцы.'}
          </p>
        </div>
        {calendar.shelves.length > 0 ? (
          <div
            role="region"
            aria-label="Календарь доступности полок"
            aria-describedby="inventory-calendar-help"
            tabIndex={0}
            className="max-w-full overflow-x-auto rounded-b-xl focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-green-600"
          >
            <table className="w-full text-left text-[13px]">
              <caption className="sr-only">
                Доступность полок аптеки {calendar.branch.name} по месяцам
              </caption>
              <thead className="bg-paper-input">
                <tr>
                  <th
                    scope="col"
                    className="sticky left-0 z-20 min-w-[180px] border-r border-ink-200 bg-paper-input p-3"
                  >
                    Полка
                  </th>
                  <th scope="col" className="whitespace-nowrap p-3 text-right">
                    Цена / месяц
                  </th>
                  {calendar.months.map((item) => (
                    <th scope="col" key={item.month} className="min-w-[168px] p-3">
                      <div>{monthLabel(item.month)}</div>
                      <div className="num mt-1 text-xs font-normal text-ink-500">
                        Свободно: {item.availableCount}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {calendar.shelves.map((shelf) => (
                  <tr
                    key={shelf.id}
                    className={`border-t border-ink-200 ${selected.includes(shelf.id) ? 'bg-brand-green-50' : ''}`}
                  >
                    <th
                      scope="row"
                      className={`sticky left-0 z-10 max-w-[240px] border-r border-ink-200 p-3 text-left align-top font-normal ${selected.includes(shelf.id) ? 'bg-brand-green-50' : 'bg-paper-card'}`}
                    >
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
                        <span className="break-words font-bold">{shelf.code}</span>
                      </label>
                      <p className="mt-1 text-xs text-ink-500">
                        {shelf.category} · {shelf.width} см{!shelf.active && ' · Неактивна'}
                      </p>
                    </th>
                    <td className="num whitespace-nowrap p-3 text-right align-top font-medium">
                      {calendar.canViewPrices && shelf.price !== null
                        ? formatKzt(shelf.price)
                        : '—'}
                    </td>
                    {shelf.months.map((cell) => (
                      <td key={cell.month} className="max-w-[260px] break-words p-3 align-top">
                        <div className="space-y-2">
                          <span
                            className={`inline-block rounded-md px-2 py-1 text-xs font-semibold ${statusClass[cell.status]}`}
                          >
                            {statusLabels[cell.status]}
                          </span>
                          {cell.placement && (
                            <div className="space-y-1 text-xs text-ink-600">
                              <div>{cell.placement.portionLabel}</div>
                              {cell.placement.items.map((item) => (
                                <div key={item.id}>{item.advertiserName}</div>
                              ))}
                            </div>
                          )}
                          {cell.note && (
                            <p className="text-xs leading-5 text-ink-600">{cell.note}</p>
                          )}
                          {!cell.canEdit && (
                            <p className="text-[11px] leading-4 text-ink-500">
                              Без изменения доступности
                            </p>
                          )}
                        </div>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div role="status" className="space-y-2 p-6">
            <p className="font-semibold">Оборудование и полки пока не добавлены.</p>
            <p className="text-sm text-ink-500">
              Паспорт и рекламные места доступны по кнопке выше. Изменение структуры зависит от
              ваших прав.
            </p>
          </div>
        )}
      </section>
      {!calendar.canViewPrices && (
        <p className="text-[12px] text-ink-500">Цены недоступны с вашими правами.</p>
      )}
      {calendar.canManage ? (
        <section className="card flex flex-col gap-4 p-4" aria-label="Изменение доступности">
          <h3 className="font-bold">Изменить доступность выбранных полок</h3>
          <p className="text-[12px] text-ink-500">
            Занятые, зарезервированные и защищённые размещения сохранятся. Это действие не создаёт
            бронирование.
          </p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Field label="Действие">
              <Select
                ariaLabel="Действие доступности"
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
                  ariaLabel="Причина ограничения"
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
          <div className="flex flex-wrap items-center gap-3 border-t border-ink-200 pt-4">
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
            <span className="num text-xs text-ink-500" aria-live="polite">
              Выбрано полок: {selected.length}
            </span>
          </div>
        </section>
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
