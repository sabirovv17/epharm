import { useCallback, useRef, useState } from 'react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button, Field, Input, Modal, PageHeader, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import MerchTaskDetail from './MerchTaskDetail'
import { adminTaskApi, adminTaskGateway, type CreateTask, type TaskBranch } from './tasks-api'
import { usePlacementRead } from './usePlacementRead'
import ReportDownloadButton from '@/features/reports/ReportDownloadButton'

export default function MerchTasksPage() {
  const access = useAdministrativeCapabilities()
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!access.canViewMerchTasks)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Нет доступа к заданиям мерча</h1>
        <p className="mt-2 text-ink-500">
          Для этого раздела нужны отдельные права на аптеки мерча.
        </p>
      </div>
    )
  return (
    <TaskWorkspace
      canCreate={access.canCreateMerchTasks}
      canExecute={access.canExecuteMerchTasks}
      canReview={access.canReviewMerchTasks}
      canExport={access.canExportMerchTasks}
    />
  )
}
function TaskWorkspace({
  canCreate,
  canExecute,
  canReview,
  canExport,
}: {
  canCreate: boolean
  canExecute: boolean
  canReview: boolean
  canExport: boolean
}) {
  const [offset, setOffset] = useState(0)
  const [queue, setQueue] = useState('all')
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState('')
  const [creating, setCreating] = useState(false)
  const load = useCallback(
    (signal: AbortSignal) => adminTaskApi.list(offset, signal, { queue, q: query }),
    [offset, queue, query],
  )
  const list = usePlacementRead(load)
  const branchLoad = useCallback((signal: AbortSignal) => adminTaskApi.branches(signal), [])
  const branchNames = usePlacementRead(branchLoad)
  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Задания фармацевтов"
        subtitle="Задания, фотоотчёты и проверка отдельных пунктов в вашей области аптек."
        actions={
          <div className="flex gap-2">
            <ReportDownloadButton
              allowed={canExport}
              path="/api/admin/merch/exports/tasks.xlsx"
              params={{}}
              filename="merch-tasks.xlsx"
              label="Excel: задания"
              note="Вся доступная история заданий; открытая карточка не является фильтром."
            />
            <Button disabled={!canCreate} onClick={() => setCreating(true)}>
              Новое задание
            </Button>
          </div>
        }
      />
      <form
        className="card flex flex-wrap gap-3 p-3"
        onSubmit={(event) => {
          event.preventDefault()
          setQuery(search.trim())
          setOffset(0)
        }}
      >
        <Select
          ariaLabel="Рабочая очередь заданий"
          value={queue}
          onChange={(value) => {
            setQueue(value)
            setOffset(0)
          }}
          options={[
            { value: 'all', label: 'Вся история' },
            { value: 'review', label: 'Ожидают проверки' },
            { value: 'revision', label: 'На доработке' },
            { value: 'overdue', label: 'Просрочены' },
            { value: 'active', label: 'В работе' },
            { value: 'completed', label: 'Завершены' },
          ]}
        />
        <Input
          aria-label="Поиск заданий"
          placeholder="Название задания"
          maxLength={120}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Button type="submit" variant="outline">
          Найти
        </Button>
      </form>
      {list.pending && <p role="status">Загружаем задания…</p>}
      {!!list.error && (
        <div role="alert" className="card p-4">
          <p>{describeError(list.error)}</p>
          <Button className="mt-2" variant="outline" onClick={list.reload}>
            Повторить
          </Button>
        </div>
      )}
      {list.data && (
        <div className="card overflow-auto">
          <table className="w-full text-left text-[13px]">
            <thead className="bg-paper-input">
              <tr>
                {['Задание', 'Аптека', 'Срок, Алматы', 'Пунктов', 'Предусмотрено', ''].map(
                  (value) => (
                    <th key={value} className="p-3">
                      {value}
                    </th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((task) => (
                <tr key={task.id} className="border-t border-paper-line">
                  <td className="p-3 font-semibold">{task.title}</td>
                  <td className="p-3">
                    {branchNames.data?.find((branch) => branch.id === task.branchId)?.name ??
                      'Название аптеки недоступно'}
                  </td>
                  <td className="p-3">
                    {task.dueAt
                      ? new Date(task.dueAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })
                      : 'Срок не записан'}
                  </td>
                  <td className="p-3">{task.itemCount}</td>
                  <td className="p-3">
                    {task.rewardAmountKzt === null
                      ? '—'
                      : task.rewardAmountKzt === 0
                        ? 'Без вознаграждения'
                        : formatKzt(task.rewardAmountKzt)}
                  </td>
                  <td className="p-3">
                    <Button variant="outline" size="sm" onClick={() => setSelected(task.id)}>
                      Открыть задание
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!list.data.items.length && (
            <p className="p-6 text-ink-500">В вашей области заданий пока нет.</p>
          )}
          <div className="flex gap-2 border-t p-3">
            <Button
              variant="outline"
              disabled={!offset}
              onClick={() => setOffset((value) => Math.max(0, value - 20))}
            >
              Назад
            </Button>
            <Button
              variant="outline"
              disabled={!list.data.hasMore}
              onClick={() => setOffset((value) => value + 20)}
            >
              Далее
            </Button>
          </div>
        </div>
      )}
      {selected && (
        <div>
          <div className="mb-3 flex justify-between gap-2">
            <Button variant="ghost" onClick={() => setSelected('')}>
              Скрыть задание
            </Button>
            <a
              className="btn btn-md btn-outline"
              href={`/learn/merch/tasks/${encodeURIComponent(selected)}`}
              target="_blank"
              rel="noreferrer"
            >
              Открыть в личном кабинете
            </a>
          </div>
          <MerchTaskDetail
            key={selected}
            id={selected}
            gateway={adminTaskGateway}
            canExecute={canExecute}
            canReview={canReview}
            onChanged={list.reload}
          />
        </div>
      )}
      {creating && (
        <CreateTaskDialog
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false)
            setSelected(id)
            list.reload()
          }}
        />
      )}
    </div>
  )
}
function CreateTaskDialog({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (id: string) => void
}) {
  const load = useCallback((signal: AbortSignal) => adminTaskApi.branches(signal), [])
  const branches = usePlacementRead(load)
  const [branchId, setBranchId] = useState('')
  const [multiple, setMultiple] = useState(false)
  const [branchIds, setBranchIds] = useState<string[]>([])
  const lastBulk = useRef<{ body: string; id: string } | null>(null)
  const [title, setTitle] = useState('')
  const [due, setDue] = useState('')
  const [availableFrom, setAvailableFrom] = useState('')
  const [reward, setReward] = useState('0')
  const [priority, setPriority] = useState('normal')
  const [items, setItems] = useState<CreateTask['items']>([
    { title: '', instructions: '', required: true, requiredPhotos: 1, checklist: [] },
  ])
  const [pending, setPending] = useState(false)
  const [now] = useState(Date.now)
  const [failure, setFailure] = useState('')
  const branch = branches.data?.find((item) => item.id === branchId)
  const selectedBranches = multiple
    ? (branches.data ?? []).filter((item) => branchIds.includes(item.id))
    : branch
      ? [branch]
      : []
  const financial =
    selectedBranches.length > 0 && selectedBranches.every((item) => item.canConfigureReward)
  const dueTime = due ? Date.parse(`${due}+05:00`) : NaN
  const fromTime = availableFrom ? Date.parse(`${availableFrom}+05:00`) : null
  const validation =
    !selectedBranches.length ||
    selectedBranches.some((item) => !item.active || !item.createEligible)
      ? 'Выберите доступную активную аптеку.'
      : branchIds.length > 50 || (multiple && branchIds.length * items.length > 500)
        ? 'Не больше 50 аптек и 500 пунктов в одном назначении.'
        : !financial && Number(reward) > 0
          ? 'Нет права задавать вознаграждение. Уберите сумму, чтобы создать задание без бонуса.'
          : title.trim().length < 2 || title.trim().length > 160
            ? 'Название задания: от 2 до 160 символов.'
            : !Number.isFinite(dueTime) || dueTime <= now || dueTime > now + 366 * 86400000
              ? 'Срок должен быть в будущем, не дальше 366 дней.'
              : fromTime !== null && (!Number.isFinite(fromTime) || fromTime > dueTime)
                ? 'Начало доступности должно быть не позже срока.'
                : !Number.isSafeInteger(Number(reward)) ||
                    Number(reward) < 0 ||
                    Number(reward) > 1000000
                  ? 'Вознаграждение: целое число от 0 до 1 000 000 ₸.'
                  : !items.some((item) => item.required)
                    ? 'Нужен хотя бы один обязательный пункт.'
                    : items.some(
                          (item) =>
                            item.title.trim().length < 2 ||
                            item.title.trim().length > 240 ||
                            item.instructions.length > 4000 ||
                            !Number.isInteger(item.requiredPhotos) ||
                            item.requiredPhotos < 0 ||
                            item.requiredPhotos > 12 ||
                            item.checklist.map((value) => value.trim()).filter(Boolean).length >
                              20 ||
                            item.checklist.some(
                              (value) =>
                                value.trim() &&
                                (value.trim().length < 2 || value.trim().length > 240),
                            ),
                        )
                      ? 'Проверьте пункты: название 2–240 символов, инструкция до 4000, фото 0–12, чек-лист до 20 строк по 2–240 символов.'
                      : ''
  const valid = !validation

  const save = async () => {
    if (!valid || !selectedBranches.length || pending) return
    setPending(true)
    setFailure('')
    try {
      const body = {
        branchId: selectedBranches[0].id,
        title: title.trim(),
        dueAt: new Date(`${due}+05:00`).toISOString(),
        rewardAmountKzt: Number(reward),
        priority,
        availableFrom: availableFrom ? new Date(`${availableFrom}+05:00`).toISOString() : null,
        items: items.map((item) => ({
          ...item,
          title: item.title.trim(),
          checklist: item.checklist.map((value) => value.trim()).filter(Boolean),
        })),
      }
      if (multiple) {
        const { branchId: _branchId, ...template } = body
        const payload = { ...template, branchIds: [...branchIds].sort() }
        const serialized = JSON.stringify(payload)
        if (lastBulk.current?.body !== serialized)
          lastBulk.current = { body: serialized, id: crypto.randomUUID() }
        const result = await adminTaskApi.createBulk({ ...payload, requestId: lastBulk.current.id })
        onCreated(result.dispatchIds[0])
      } else {
        const result = await adminTaskApi.create(body)
        onCreated(result.id)
      }
    } catch (error) {
      setFailure(describeError(error))
    } finally {
      setPending(false)
    }
  }
  const patchItem = (index: number, patch: Partial<CreateTask['items'][number]>) =>
    setItems((current) => current.map((item, i) => (i === index ? { ...item, ...patch } : item)))
  return (
    <Modal
      open
      title="Новое задание аптеки"
      width={920}
      onClose={() => {
        if (!pending) onClose()
      }}
      footer={
        <>
          <Button variant="ghost" disabled={pending} onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={!valid || pending} onClick={save}>
            {pending ? 'Создаём…' : 'Создать задание'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {branches.pending && <p role="status">Загружаем доступные аптеки…</p>}
        {!!branches.error && <p role="alert">{describeError(branches.error)}</p>}
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={multiple}
            disabled={pending}
            onChange={(event) => {
              setMultiple(event.target.checked)
              setReward('0')
            }}
          />
          Назначить нескольким аптекам
        </label>
        {multiple ? (
          <Field
            label="Аптеки мерча *"
            hint="Выберите до 50 аптек; для каждой создаётся отдельное задание. Ctrl/⌘ — несколько строк."
          >
            <select
              aria-label="Аптеки массового задания"
              multiple
              className="inp min-h-36"
              value={branchIds}
              disabled={pending}
              onChange={(event) => {
                setBranchIds(Array.from(event.target.selectedOptions, (option) => option.value))
                setReward('0')
              }}
            >
              {(branches.data ?? [])
                .filter((item) => item.active && item.createEligible)
                .map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.city} · {item.name} · {item.address}
                  </option>
                ))}
            </select>
            <p>
              Выбрано: {branchIds.length}. Пунктов всего: {branchIds.length * items.length}.
            </p>
          </Field>
        ) : (
          <Field label="Аптека мерча *">
            <Select
              ariaLabel="Аптека нового задания"
              value={branchId}
              disabled={pending}
              onChange={(value) => {
                setBranchId(value)
                setReward('0')
              }}
              options={(branches.data ?? [])
                .filter((item: TaskBranch) => item.active && item.createEligible)
                .map((item) => ({
                  value: item.id,
                  label: `${item.city} · ${item.name} · ${item.address}`,
                }))}
            />
          </Field>
        )}
        {branches.data && !branches.data.some((item) => item.active && item.createEligible) && (
          <p className="text-[13px] text-ink-500">
            Нет активных аптек, в которых разрешено создание. Существующие задания остаются доступны
            для просмотра.
          </p>
        )}
        <Field label="Название задания *">
          <Input
            aria-label="Название нового задания"
            value={title}
            maxLength={160}
            disabled={pending}
            onChange={(event) => setTitle(event.target.value)}
          />
        </Field>
        <div className="grid grid-cols-3 gap-3">
          <Field label="Срок, время Алматы *">
            <Input
              aria-label="Срок задания Алматы"
              type="datetime-local"
              value={due}
              disabled={pending}
              onChange={(event) => setDue(event.target.value)}
            />
          </Field>
          <Field label="Приоритет">
            <Select
              value={priority}
              onChange={setPriority}
              disabled={pending}
              options={[
                { value: 'low', label: 'Низкий' },
                { value: 'normal', label: 'Обычный' },
                { value: 'high', label: 'Высокий' },
                { value: 'urgent', label: 'Срочный' },
              ]}
            />
          </Field>
          <Field
            label="Вознаграждение, ₸"
            hint={
              !financial
                ? 'Нет права задавать денежное вознаграждение'
                : 'Предусмотренная сумма; начисление после проверки'
            }
          >
            <Input
              aria-label="Вознаграждение нового задания"
              type="number"
              min={0}
              step={1}
              value={reward}
              disabled={pending || !financial}
              onChange={(event) => setReward(event.target.value)}
            />
          </Field>
        </div>
        <Field label="Доступно с, время Алматы" optional>
          <Input
            aria-label="Начало доступности задания Алматы"
            type="datetime-local"
            value={availableFrom}
            disabled={pending}
            onChange={(event) => setAvailableFrom(event.target.value)}
          />
        </Field>
        {items.map((item, index) => (
          <div key={index} className="flex flex-col gap-3 rounded-lg border border-paper-line p-3">
            <div className="flex items-center justify-between">
              <h3 className="font-bold">Пункт {index + 1}</h3>
              <Button
                variant="ghost"
                size="sm"
                disabled={pending || items.length === 1}
                onClick={() => setItems((current) => current.filter((_, i) => i !== index))}
              >
                Удалить пункт
              </Button>
            </div>
            <Field label="Название пункта *">
              <Input
                aria-label={`Название пункта ${index + 1}`}
                maxLength={240}
                value={item.title}
                disabled={pending}
                onChange={(event) => patchItem(index, { title: event.target.value })}
              />
            </Field>
            <Field label="Инструкция">
              <textarea
                aria-label={`Инструкция пункта ${index + 1}`}
                className="inp min-h-20 py-2"
                maxLength={4000}
                value={item.instructions}
                disabled={pending}
                onChange={(event) => patchItem(index, { instructions: event.target.value })}
              />
            </Field>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 text-[13px]">
                <input
                  type="checkbox"
                  checked={item.required}
                  disabled={pending}
                  onChange={(event) => patchItem(index, { required: event.target.checked })}
                />
                Обязательный пункт
              </label>
              <Field label="Фото, минимум">
                <Input
                  aria-label={`Минимум фото пункта ${index + 1}`}
                  type="number"
                  min={0}
                  max={12}
                  value={item.requiredPhotos}
                  disabled={pending}
                  onChange={(event) =>
                    patchItem(index, { requiredPhotos: Number(event.target.value) })
                  }
                />
              </Field>
            </div>
            <Field label="Чек-лист — одна проверка в строке" optional>
              <textarea
                aria-label={`Чек-лист пункта ${index + 1}`}
                className="inp min-h-20 py-2"
                value={item.checklist.join('\n')}
                disabled={pending}
                onChange={(event) =>
                  patchItem(index, { checklist: event.target.value.split('\n') })
                }
              />
            </Field>
          </div>
        ))}
        <div>
          <Button
            variant="outline"
            disabled={
              pending ||
              items.length >= (multiple ? Math.floor(500 / Math.max(1, branchIds.length)) : 200)
            }
            onClick={() =>
              setItems((current) => [
                ...current,
                { title: '', instructions: '', required: true, requiredPhotos: 1, checklist: [] },
              ])
            }
          >
            Добавить пункт
          </Button>
        </div>
        {validation && <p className="text-[12px] text-ink-500">{validation}</p>}
        {failure && (
          <p role="alert" className="text-[13px] text-accent-danger">
            {failure}
          </p>
        )}
        <p className="text-[12px] text-ink-500">
          Задание относится к выбранной аптеке. Исполнитель подтверждается в своём личном кабинете.
          Стандарт-Н не блокируется.
        </p>
      </div>
    </Modal>
  )
}
