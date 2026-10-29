import { useCallback, useState } from 'react'
import { Button, Field, Input, Modal, PageHeader, Select } from '@/ui'
import { formatKzt } from '@/mocks/fixtures'
import type {
  AvailablePlace,
  Placement,
  PlacementAction,
  PlacementGateway,
  PlacementStatus,
  PlaceSelection,
  Portion,
  ProducerOption,
} from './placements-api'
import { usePlacementRead } from './usePlacementRead'

const labels: Record<PlacementStatus, string> = {
  draft: 'Черновик',
  pending: 'Ожидает подтверждения',
  approved: 'Подтверждено',
  rejected: 'Отклонено',
  cancelled: 'Отменено',
}
const actions: Record<PlacementAction, string> = {
  submit: 'Отправить на подтверждение',
  approve: 'Подтвердить размещение',
  reject: 'Отклонить',
  cancel: 'Отменить размещение',
}
const portions: Record<Portion, string> = {
  whole: 'Целиком',
  left: 'Левая половина',
  right: 'Правая половина',
}
const amount = (value: number | null) => (value === null ? '—' : formatKzt(value))
const statusCode = (error: unknown): number | undefined =>
  typeof error === 'object' && error !== null
    ? ((error as { status?: number; response?: { status?: number } }).status ??
      (error as { response?: { status?: number } }).response?.status)
    : undefined
const placementError = (error: unknown) => {
  const body = error as { code?: string; response?: { data?: { code?: string } } } | null
  if (body?.response?.data?.code === 'COMPLETE_PROFILE' || body?.code === 'COMPLETE_PROFILE')
    return 'Перед отправкой или служебным размещением заполните название компании в кабинете производителя.'
  if (statusCode(error) === 409)
    return 'Данные или цены изменились. Загрузите актуальную версию, заново сохраните черновик и проверьте сумму.'
  if ([401, 403].includes(statusCode(error) ?? 0))
    return 'Нет действующего доступа к этим данным. Обновите сессию или обратитесь к администратору.'
  return error instanceof Error ? error.message : 'Не удалось выполнить запрос.'
}
const localMonth = () => {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

export default function PlacementWorkspace({
  gateway,
  customerName,
}: {
  gateway: PlacementGateway
  customerName?: string
}) {
  const [producerId, setProducerId] = useState('')
  const [status, setStatus] = useState('')
  const [offset, setOffset] = useState(0)
  const [detailId, setDetailId] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const loadOptions = useCallback((signal: AbortSignal) => gateway.producers(signal), [gateway])
  const options = usePlacementRead(loadOptions)
  const load = useCallback(
    (signal: AbortSignal) => gateway.list({ producerId, status, offset }, signal),
    [gateway, producerId, status, offset],
  )
  const list = usePlacementRead(load)
  const created = (placement: Placement) => {
    setCreateOpen(false)
    setDetailId(placement.id)
    list.reload()
  }
  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={gateway.realm === 'admin' ? 'Кабинет производителя' : 'Мои размещения'}
        subtitle={
          gateway.realm === 'admin'
            ? 'Медиапланы и резервирование мест для производителей в вашей области.'
            : `${customerName ?? 'Производитель'} · собственные медиапланы и доступные места.`
        }
      />
      <div className="card flex flex-wrap items-end gap-3 p-4">
        {gateway.realm === 'admin' && (
          <Field label="Производитель">
            <Select
              ariaLabel="Фильтр производителя"
              value={producerId || 'all'}
              onChange={(value) => {
                setProducerId(value === 'all' ? '' : value)
                setOffset(0)
              }}
              options={[
                { value: 'all', label: 'Все доступные' },
                ...(options.data ?? []).map((option) => ({
                  value: option.id,
                  label: option.name ?? `Компания не указана · ${option.id.slice(0, 8)}`,
                })),
              ]}
            />
          </Field>
        )}
        <Field label="Статус">
          <Select
            ariaLabel="Статус медиаплана"
            value={status || 'all'}
            onChange={(value) => {
              setStatus(value === 'all' ? '' : value)
              setOffset(0)
            }}
            options={[
              { value: 'all', label: 'Все статусы' },
              ...Object.entries(labels).map(([value, label]) => ({ value, label })),
            ]}
          />
        </Field>
        <Button
          onClick={() => setCreateOpen(true)}
          disabled={
            gateway.realm === 'admin' &&
            (!options.data?.some((item) => item.active) || !!options.error)
          }
        >
          Новый медиаплан
        </Button>
      </div>
      {!!options.error && gateway.realm === 'admin' && (
        <Notice error={options.error} onReload={options.reload} />
      )}
      {list.pending && <p role="status">Загружаем медиапланы…</p>}
      {!!list.error && <Notice error={list.error} onReload={list.reload} />}
      {list.data && (
        <div className="card overflow-auto">
          <table className="w-full text-left text-[13px]">
            <thead className="bg-paper-input">
              <tr>
                {['Производитель', 'Месяц', 'Статус', 'Сумма', 'Резерв', ''].map((title) => (
                  <th key={title} className="p-3">
                    {title}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((item) => (
                <tr key={item.id} className="border-t border-paper-line">
                  <td className="p-3 font-semibold">
                    {item.producerName ?? item.advertiserName ?? 'Компания не указана'}
                    {item.producerBindingStatus === 'unresolved' && (
                      <span className="block text-[11px] font-normal text-ink-500">
                        Владелец не подтверждён
                      </span>
                    )}
                  </td>
                  <td className="p-3">{item.placementMonth.slice(0, 7)}</td>
                  <td className="p-3">{labels[item.status]}</td>
                  <td className="p-3">{amount(item.totalAmount)}</td>
                  <td className="p-3">
                    {item.reservationState === 'expired'
                      ? 'Истёк'
                      : item.heldUntil
                        ? new Date(item.heldUntil).toLocaleString('ru-RU', {
                            timeZone: item.reservationTimezone,
                          })
                        : '—'}
                  </td>
                  <td className="p-3">
                    <Button variant="outline" size="sm" onClick={() => setDetailId(item.id)}>
                      Открыть
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!list.data.items.length && (
            <p className="p-6 text-ink-500">Медиапланов по этим условиям нет.</p>
          )}
          <div className="flex items-center gap-3 border-t p-3">
            <Button
              variant="outline"
              disabled={offset === 0}
              onClick={() => setOffset((value) => Math.max(0, value - 50))}
            >
              Назад
            </Button>
            <span className="text-[12px] text-ink-500">Всего: {list.data.total}</span>
            <Button
              variant="outline"
              disabled={offset + 50 >= list.data.total}
              onClick={() => setOffset((value) => value + 50)}
            >
              Далее
            </Button>
          </div>
        </div>
      )}
      <p className="text-[12px] text-ink-500">
        Оплата пока не подключена. Черновик, резерв и подтверждённое место не являются
        подтверждением платежа.
      </p>
      {createOpen && (
        <PlacementEditor
          gateway={gateway}
          producers={options.data ?? []}
          initialProducerId={producerId}
          onClose={() => setCreateOpen(false)}
          onSaved={created}
        />
      )}
      {detailId && (
        <PlacementDetail
          key={detailId}
          id={detailId}
          gateway={gateway}
          producers={options.data ?? []}
          onClose={() => setDetailId('')}
          onChanged={list.reload}
        />
      )}
    </div>
  )
}

function Notice({ error, onReload }: { error: unknown; onReload: () => void }) {
  return (
    <div
      role="alert"
      className="rounded-lg border border-paper-line bg-paper-input p-3 text-[13px]"
    >
      <p>{placementError(error)}</p>
      <Button className="mt-2" variant="outline" size="sm" onClick={onReload}>
        Загрузить актуальную версию
      </Button>
    </div>
  )
}

function PlacementDetail({
  id,
  gateway,
  producers,
  onClose,
  onChanged,
}: {
  id: string
  gateway: PlacementGateway
  producers: ProducerOption[]
  onClose: () => void
  onChanged: () => void
}) {
  const load = useCallback((signal: AbortSignal) => gateway.view(id, signal), [gateway, id])
  const read = usePlacementRead(load)
  const [editing, setEditing] = useState(false)
  const [action, setAction] = useState<PlacementAction | null>(null)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const reload = () => {
    setFailure(null)
    setAction(null)
    setReason('')
    read.reload()
  }
  const transition = async () => {
    if (!action || !read.data || pending) return
    setPending(true)
    try {
      await gateway.transition(id, action, read.data.revision, reason.trim() || null)
      setAction(null)
      setReason('')
      read.reload()
      onChanged()
    } catch (error) {
      setFailure(error)
      setAction(null)
    } finally {
      setPending(false)
    }
  }
  const denied = [401, 403].includes(statusCode(failure) ?? 0)
  const item = denied ? undefined : read.data
  const bound =
    !!item &&
    (gateway.realm === 'producer' ||
      (item.producerBindingStatus === 'verified' && item.producerId !== null))
  const allowedActions =
    item && bound
      ? (
          [
            ['submit', item.canSubmit],
            ['approve', item.canApprove && gateway.realm === 'admin'],
            ['reject', item.canReject && gateway.realm === 'admin'],
            ['cancel', item.canCancel],
          ] as const
        ).filter(([, allowed]) => allowed)
      : []
  return (
    <>
      {!editing && !action && (
        <Modal
          open
          width={920}
          title="Медиаплан"
          onClose={() => {
            if (!pending) onClose()
          }}
          footer={
            <Button variant="ghost" disabled={pending} onClick={onClose}>
              Закрыть
            </Button>
          }
        >
          {read.pending && <p role="status">Загружаем актуальный медиаплан…</p>}
          {!!read.error && <Notice error={read.error} onReload={reload} />}
          {failure !== null && <Notice error={failure} onReload={reload} />}
          {item && (
            <div className="flex flex-col gap-4">
              <div>
                <h2 className="text-lg font-bold">
                  {item.producerName ?? item.advertiserName ?? 'Компания не указана'}
                </h2>
                <p className="mt-1 text-[13px] text-ink-500">
                  {item.placementMonth.slice(0, 7)} · {labels[item.status]} · Версия {item.revision}
                </p>
              </div>
              {item.producerBindingStatus === 'unresolved' && (
                <p className="rounded-lg bg-surface-warning p-3 text-[13px]">
                  У исторического медиаплана не подтверждён производитель. Изменения недоступны до
                  отдельной проверки связи.
                </p>
              )}
              <div className="rounded-lg bg-paper-input p-3">
                <p className="font-bold">Сумма по данным сервера: {amount(item.totalAmount)}</p>
                <p className="mt-1 text-[12px]">
                  Резерв: {item.reservationState}. Срок подтверждения —{' '}
                  {item.reservationBusinessDays} рабочих дня, {item.reservationTimezone}.
                  {item.heldUntil &&
                    ` До ${new Date(item.heldUntil).toLocaleString('ru-RU', { timeZone: item.reservationTimezone })}.`}
                </p>
                <p className="mt-1 text-[12px] text-ink-500">
                  Это резервирование места. Оплата не подключена.
                </p>
              </div>
              <table className="w-full text-left text-[12px]">
                <thead>
                  <tr>
                    <th className="p-2">Место</th>
                    <th className="p-2">Часть</th>
                    <th className="p-2">Статус позиции</th>
                    <th className="p-2">Цена</th>
                  </tr>
                </thead>
                <tbody>
                  {item.items.map((row) => (
                    <tr key={row.id} className="border-t border-paper-line">
                      <td className="p-2">{row.shelfCode}</td>
                      <td className="p-2">{portions[row.portion]}</td>
                      <td className="p-2">{labels[row.status]}</td>
                      <td className="p-2">{amount(row.monthlyPrice)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {item.note && <p className="text-[13px]">Комментарий: {item.note}</p>}
              {item.reviewNote && <p className="text-[13px]">Решение: {item.reviewNote}</p>}
              <div className="flex flex-wrap gap-2">
                {bound && item.canEdit && (
                  <Button
                    variant="outline"
                    disabled={!!failure || pending}
                    onClick={() => setEditing(true)}
                  >
                    Изменить черновик
                  </Button>
                )}
                {allowedActions.map(([name]) => (
                  <Button
                    key={name}
                    variant={name === 'submit' || name === 'approve' ? 'primary' : 'outline'}
                    disabled={!!failure || pending}
                    onClick={() => {
                      setAction(name)
                      setReason('')
                    }}
                  >
                    {actions[name]}
                  </Button>
                ))}
              </div>
            </div>
          )}
        </Modal>
      )}
      {editing && item && (
        <PlacementEditor
          gateway={gateway}
          producers={producers}
          initial={item}
          onClose={() => {
            setEditing(false)
            read.reload()
          }}
          onSaved={() => {
            setEditing(false)
            read.reload()
            onChanged()
          }}
        />
      )}
      {action && item && (
        <Modal
          open
          title={actions[action]}
          onClose={() => {
            if (!pending) setAction(null)
          }}
          footer={
            <>
              <Button variant="ghost" disabled={pending} onClick={() => setAction(null)}>
                Отмена
              </Button>
              <Button
                disabled={pending || (['reject', 'cancel'].includes(action) && !reason.trim())}
                onClick={transition}
              >
                {pending ? 'Сохраняем…' : 'Подтвердить действие'}
              </Button>
            </>
          }
        >
          <p className="text-[13px]">
            Версия {item.revision}. Сумма: {amount(item.totalAmount)}.
          </p>
          {action === 'submit' && (
            <p className="my-3 text-[13px]">
              Будет создан резерв на {item.reservationBusinessDays} рабочих дня. Подтверждение и
              оплата — отдельные процессы.
            </p>
          )}
          <Field label="Причина / комментарий" optional={!['reject', 'cancel'].includes(action)}>
            <Input
              aria-label="Причина решения по размещению"
              maxLength={1000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        </Modal>
      )}
    </>
  )
}

function PlacementEditor({
  gateway,
  producers,
  initial,
  initialProducerId = '',
  onClose,
  onSaved,
}: {
  gateway: PlacementGateway
  producers: ProducerOption[]
  initial?: Placement
  initialProducerId?: string
  onClose: () => void
  onSaved: (placement: Placement) => void
}) {
  const [producerId, setProducerId] = useState(initial?.producerId ?? initialProducerId)
  const [month, setMonth] = useState(initial?.placementMonth.slice(0, 7) ?? localMonth())
  const [offset, setOffset] = useState(0)
  const [branchId, setBranchId] = useState('')
  const [selected, setSelected] = useState<PlaceSelection[]>(
    initial?.items
      .filter((item) => item.status === 'draft')
      .map(({ shelfId, portion }) => ({ shelfId, portion })) ?? [],
  )
  const [note, setNote] = useState(initial?.note ?? '')
  const [assignment, setAssignment] = useState(false)
  const [confirmAssignment, setConfirmAssignment] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<unknown>(null)
  const load = useCallback(
    (signal: AbortSignal) =>
      (gateway.realm === 'producer' || producerId) && /^\d{4}-\d{2}$/.test(month)
        ? gateway.catalog(
            producerId,
            `${month}-01`,
            offset,
            signal,
            gateway.realm === 'producer' ? branchId : undefined,
          )
        : Promise.resolve(null),
    [gateway, producerId, month, offset, branchId],
  )
  const catalog = usePlacementRead(load)
  const branch = catalog.data?.branches.find((item) => item.id === branchId)
  const canSave =
    !pending &&
    !failure &&
    !!catalog.data &&
    selected.length > 0 &&
    (initial ? initial.canEdit : assignment ? catalog.data.canAssign : catalog.data.canCreate)
  const save = async () => {
    if (!canSave) return
    setPending(true)
    try {
      const result = initial
        ? await gateway.save(initial.id, initial.revision, selected, note.trim() || null)
        : await gateway.create(
            {
              producerId,
              placementMonth: `${month}-01`,
              items: selected,
              note: note.trim() || null,
            },
            assignment,
          )
      onSaved(result)
    } catch (error) {
      setFailure(error)
      setConfirmAssignment(false)
    } finally {
      setPending(false)
    }
  }
  const choose = (shelf: AvailablePlace, portion: Portion | '') =>
    setSelected((current) => [
      ...current.filter((item) => item.shelfId !== shelf.shelfId),
      ...(portion ? [{ shelfId: shelf.shelfId, portion }] : []),
    ])
  const resetSelectors = () => {
    setBranchId('')
    setOffset(0)
    setSelected([])
    setFailure(null)
  }
  return (
    <>
      {!confirmAssignment && (
        <Modal
          open
          width={1050}
          title={initial ? 'Изменить черновик' : 'Новый медиаплан'}
          subtitle="Выберите аптеку и место. Цены и доступность проверяются сервером."
          onClose={() => {
            if (!pending) onClose()
          }}
          footer={
            <>
              <Button variant="ghost" disabled={pending} onClick={onClose}>
                Отмена
              </Button>
              <Button
                disabled={!canSave}
                onClick={() => (assignment ? setConfirmAssignment(true) : save())}
              >
                {pending
                  ? 'Сохраняем…'
                  : assignment
                    ? 'Проверить служебное размещение'
                    : 'Сохранить черновик'}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-3">
            <div className="grid grid-cols-2 gap-3">
              {gateway.realm === 'admin' && (
                <Field label="Производитель">
                  <Select
                    ariaLabel="Производитель медиаплана"
                    value={producerId}
                    disabled={!!initial || pending}
                    onChange={(value) => {
                      resetSelectors()
                      setProducerId(value)
                    }}
                    options={producers
                      .filter((item) => item.active || item.id === initial?.producerId)
                      .map((item) => ({
                        value: item.id,
                        label: item.name ?? `Компания не указана · ${item.id.slice(0, 8)}`,
                      }))}
                  />
                </Field>
              )}
              <Field label="Месяц размещения">
                <Input
                  aria-label="Месяц размещения"
                  type="month"
                  value={month}
                  disabled={!!initial || pending}
                  onChange={(event) => {
                    resetSelectors()
                    setMonth(event.target.value)
                  }}
                />
              </Field>
            </div>
            {catalog.pending && <p role="status">Проверяем доступные места…</p>}
            {!!catalog.error && <Notice error={catalog.error} onReload={catalog.reload} />}
            {failure !== null && (
              <Notice
                error={failure}
                onReload={() => {
                  if (initial && statusCode(failure) === 409) onClose()
                  else {
                    setFailure(null)
                    catalog.reload()
                  }
                }}
              />
            )}
            {catalog.data && ![401, 403].includes(statusCode(failure) ?? 0) && (
              <>
                <Field label="Аптека">
                  <Select
                    ariaLabel="Аптека для размещения"
                    value={branchId}
                    onChange={setBranchId}
                    options={catalog.data.branches.map((item) => ({
                      value: item.id,
                      label: `${item.city} · ${item.name} · ${item.address}`,
                    }))}
                  />
                </Field>
                <div className="flex items-center gap-3">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!offset || pending}
                    onClick={() => {
                      setBranchId('')
                      setOffset((value) => Math.max(0, value - 50))
                    }}
                  >
                    Назад
                  </Button>
                  <span className="text-[12px] text-ink-500">Аптек: {catalog.data.total}</span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={offset + 50 >= catalog.data.total || pending}
                    onClick={() => {
                      setBranchId('')
                      setOffset((value) => value + 50)
                    }}
                  >
                    Далее
                  </Button>
                </div>
                {branch && (
                  <div className="rounded-lg border border-paper-line">
                    <table className="w-full text-left text-[12px]">
                      <thead className="bg-paper-input">
                        <tr>
                          <th className="p-2">Место</th>
                          <th className="p-2">Вариант и цена</th>
                        </tr>
                      </thead>
                      <tbody>
                        {branch.items.map((shelf) => (
                          <tr key={shelf.shelfId} className="border-t border-paper-line">
                            <td className="p-2 font-semibold">{shelf.code}</td>
                            <td className="p-2">
                              <Select
                                ariaLabel={`Часть места ${shelf.code}`}
                                value={
                                  selected.find((item) => item.shelfId === shelf.shelfId)
                                    ?.portion ?? 'none'
                                }
                                disabled={
                                  pending || (assignment ? !branch.canAssign : !branch.canCreate)
                                }
                                onChange={(value) =>
                                  choose(shelf, value === 'none' ? '' : (value as Portion))
                                }
                                options={[
                                  { value: 'none', label: 'Не выбрано' },
                                  ...(['whole', 'left', 'right'] as const)
                                    .filter(
                                      (part) =>
                                        shelf[part]?.available ||
                                        selected.some(
                                          (item) =>
                                            item.shelfId === shelf.shelfId && item.portion === part,
                                        ),
                                    )
                                    .map((part) => ({
                                      value: part,
                                      label: `${portions[part]} · ${amount(shelf[part]?.price ?? null)}${!shelf[part]?.available ? ' · Недоступно' : ''}`,
                                    })),
                                ]}
                              />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {!branch.items.length && (
                      <p className="p-3 text-[13px] text-ink-500">
                        Для этого месяца доступных мест нет.
                      </p>
                    )}
                  </div>
                )}
                {gateway.realm === 'admin' && !initial && (
                  <label className="flex gap-2 text-[13px]">
                    <input
                      type="checkbox"
                      checked={assignment}
                      disabled={!catalog.data.canAssign || pending}
                      onChange={(event) => {
                        setAssignment(event.target.checked)
                        setSelected([])
                      }}
                    />
                    Служебное размещение — сразу подтвердить место
                  </label>
                )}
              </>
            )}
            <p className="text-[13px]">
              Выбрано мест: {selected.length}. Сумма будет возвращена сервером после сохранения.
            </p>
            {selected.length > 0 && (
              <Button variant="ghost" size="sm" disabled={pending} onClick={() => setSelected([])}>
                Очистить выбранные места
              </Button>
            )}
            <Field label="Комментарий" optional>
              <Input
                aria-label="Комментарий медиаплана"
                maxLength={500}
                value={note}
                disabled={pending}
                onChange={(event) => setNote(event.target.value)}
              />
            </Field>
            <p className="text-[12px] text-ink-500">
              Черновик не занимает место. Оплата не подключена. Отправка на подтверждение создаёт
              отдельный резерв.
            </p>
          </div>
        </Modal>
      )}
      {confirmAssignment && (
        <Modal
          open
          title="Подтвердите служебное размещение"
          onClose={() => {
            if (!pending) setConfirmAssignment(false)
          }}
          footer={
            <>
              <Button
                variant="ghost"
                disabled={pending}
                onClick={() => setConfirmAssignment(false)}
              >
                Назад к черновику
              </Button>
              <Button disabled={!canSave} onClick={save}>
                Подтвердить служебное размещение
              </Button>
            </>
          }
        >
          <p>
            Будет занято мест: {selected.length}. Сервер проверит текущие цены, права и отсутствие
            пересечений. Это не подтверждение оплаты.
          </p>
        </Modal>
      )}
    </>
  )
}
