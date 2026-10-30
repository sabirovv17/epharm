import { useCallback, useEffect, useRef, useState } from 'react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button, Field, Input, Modal, PageHeader, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import MerchTaskDetail from './MerchTaskDetail'
import {
  adminTaskApi,
  adminTaskGateway,
  type CreateTask,
  type MerchTask,
  type TaskBranch,
} from './tasks-api'
import { usePlacementRead } from './usePlacementRead'
import TaskTemplateDialog from './TaskTemplateDialog'
import { AdminMonthlyVisitPanel } from './MonthlyVisitPanel'
import ReportDownloadButton from '@/features/reports/ReportDownloadButton'
import { taskPriorityLabel, taskTypeLabel } from './task-labels'
import TaskStatusBadge from './TaskStatusBadge'

export default function MerchTasksPage() {
  const access = useAdministrativeCapabilities()
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (access.isError)
    return (
      <div className="card p-6">
        <p role="alert">Не удалось проверить доступ к заданиям. {describeError(access.error)}</p>
        <Button
          className="mt-3"
          variant="outline"
          disabled={access.isFetching}
          onClick={() => void access.refetch()}
        >
          Повторить проверку доступа
        </Button>
      </div>
    )
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
    <>
      <TaskWorkspace
        canCreate={access.canCreateMerchTasks}
        canExecute={access.canExecuteMerchTasks}
        canReview={access.canReviewMerchTasks}
        canExport={access.canExportMerchTasks}
        canComment={access.canCommentMerchTasks}
        canCancel={access.canCancelMerchTasks}
      />
    </>
  )
}
function TaskWorkspace({
  canCreate,
  canExecute,
  canReview,
  canExport,
  canComment,
  canCancel,
}: {
  canCreate: boolean
  canExecute: boolean
  canReview: boolean
  canExport: boolean
  canComment: boolean
  canCancel: boolean
}) {
  const [offset, setOffset] = useState(0)
  const [queue, setQueue] = useState('all')
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState('')
  const [reviewFocus, setReviewFocus] = useState(false)
  const [executionItemId, setExecutionItemId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [templateOpen, setTemplateOpen] = useState(false)
  const [visitOpen, setVisitOpen] = useState(false)
  const detailRegion = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!selected || reviewFocus) return
    detailRegion.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
    detailRegion.current?.focus({ preventScroll: true })
  }, [selected, reviewFocus])
  const load = useCallback(
    (signal: AbortSignal) => adminTaskApi.list(offset, signal, { queue, q: query }),
    [offset, queue, query],
  )
  const list = usePlacementRead(load)
  const branchLoad = useCallback((signal: AbortSignal) => adminTaskApi.branches(signal), [])
  const branchNames = usePlacementRead(branchLoad)
  const filtered = queue !== 'all' || !!query
  const openTask = (id: string, focusReview = false, itemId: string | null = null) => {
    setSelected(id)
    setReviewFocus(focusReview)
    setExecutionItemId(itemId)
  }
  const openListedTask = (task: MerchTask) =>
    openTask(
      task.id,
      queue !== 'mine' && (task.pendingReviewCount ?? 0) > 0,
      queue === 'mine' ? (task.nextExecutionItemId ?? null) : null,
    )
  const resetFilters = () => {
    setQueue('all')
    setSearch('')
    setQuery('')
    setOffset(0)
    setSelected('')
  }
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <PageHeader
        title="Задания мерча"
        subtitle="Задания, фотоотчёты и проверка отдельных пунктов в вашей области аптек."
        actions={
          <Button disabled={!canCreate} onClick={() => setCreating(true)}>
            Новое задание
          </Button>
        }
      />
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-ink-200 bg-white px-4 py-3">
        <p className="text-xs font-semibold text-ink-500">Инструменты заданий</p>
        <div className="flex flex-wrap items-center gap-2">
          {canExecute && (
            <Button variant="outline" onClick={() => setVisitOpen((value) => !value)}>
              Месячный визит
            </Button>
          )}
          {canCreate && (
            <Button variant="outline" onClick={() => setTemplateOpen(true)}>
              По шаблону
            </Button>
          )}
          <ReportDownloadButton
            allowed={canExport}
            path="/api/admin/merch/exports/tasks.xlsx"
            params={{}}
            filename="merch-tasks.xlsx"
            label="Excel: задания"
            note="Вся доступная история заданий; открытая карточка не является фильтром."
          />
        </div>
      </div>
      {visitOpen && (
        <AdminMonthlyVisitPanel
          onStarted={(id) => {
            openTask(id)
            setVisitOpen(false)
            list.reload()
          }}
        />
      )}
      {templateOpen && (
        <TaskTemplateDialog
          onClose={() => setTemplateOpen(false)}
          onCreated={(id) => {
            setTemplateOpen(false)
            openTask(id)
            list.reload()
          }}
        />
      )}
      <form
        className="card flex flex-wrap items-end gap-3 p-4"
        onSubmit={(event) => {
          event.preventDefault()
          setQuery(search.trim())
          setOffset(0)
          setSelected('')
        }}
      >
        <Field label="Рабочая очередь">
          <Select
            ariaLabel="Рабочая очередь заданий"
            value={queue}
            onChange={(value) => {
              setQueue(value)
              setOffset(0)
              setSelected('')
            }}
            options={[
              { value: 'all', label: 'Вся история' },
              ...(canExecute ? [{ value: 'mine', label: 'Мне выполнить' }] : []),
              { value: 'review', label: 'Ожидают проверки' },
              { value: 'revision', label: 'На доработке' },
              { value: 'overdue', label: 'Просрочены' },
              { value: 'active', label: 'В работе' },
              { value: 'completed', label: 'Завершены' },
            ]}
          />
        </Field>
        <div className="min-w-0 flex-1 basis-64">
          <Field label="Поиск по названию">
            <Input
              aria-label="Поиск заданий"
              placeholder="Название задания"
              maxLength={120}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
        </div>
        <Button type="submit" variant="outline">
          Найти
        </Button>
        {(filtered || search) && (
          <Button type="button" variant="ghost" onClick={resetFilters}>
            Сбросить фильтры
          </Button>
        )}
        <p className="w-full text-[12px] text-ink-500">
          {query ? `Применён поиск: «${query}». ` : 'Показаны задания в вашей области аптек. '}
          {search.trim() !== query && 'Нажмите «Найти», чтобы применить новое название. '}
          {queue === 'review' &&
            'Здесь также задания, где часть пунктов уже возвращена на доработку.'}
          {queue === 'mine' &&
            'Только подтверждённые личные назначения, ваши месячные визиты и начатые вами пункты. Доступ к аптеке сам по себе не делает задание вашим.'}
        </p>
      </form>
      {list.pending && (
        <p role="status" className="card p-6 text-ink-500">
          Загружаем задания…
        </p>
      )}
      {!!branchNames.error && (
        <div role="alert" className="card flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm">
            Не удалось загрузить названия и адреса аптек. Задания доступны по текущим правам.
          </p>
          <Button variant="outline" onClick={branchNames.reload}>
            Повторить загрузку аптек
          </Button>
        </div>
      )}
      {!!list.error && (
        <div role="alert" className="card p-4">
          <p>{describeError(list.error)}</p>
          <Button className="mt-2" variant="outline" onClick={list.reload}>
            Повторить
          </Button>
        </div>
      )}
      {list.data && (
        <div className="card min-w-0 overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-200 p-4">
            <h2 className="font-bold">
              {queue === 'mine'
                ? 'Мне выполнить'
                : queue === 'review'
                  ? 'Отчёты на проверке'
                  : 'Очередь заданий'}
            </h2>
            <p className="text-sm text-ink-500">
              {list.data.items.length
                ? `Показаны ${offset + 1}–${offset + list.data.items.length}${list.data.hasMore ? ' · Есть следующая страница' : ''}`
                : '0 заданий'}
            </p>
          </div>
          {list.data.items.length > 0 ? (
            <div
              className="overflow-x-auto focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-green-400"
              role="region"
              aria-label="Таблица заданий"
              tabIndex={0}
            >
              <table className="w-full min-w-[980px] text-left text-[13px]">
                <caption className="sr-only">
                  Задания доступных аптек. Откройте задание для работы с его пунктами.
                </caption>
                <thead className="bg-paper-input">
                  <tr>
                    {[
                      'Задание / тип',
                      'Аптека / адрес',
                      'Статус',
                      'Приоритет',
                      'Срок, Алматы',
                      'Всего пунктов',
                      'На проверке',
                      'Предусмотрено',
                      '',
                    ].map((value) => (
                      <th
                        key={value}
                        scope="col"
                        className="px-4 py-3 text-xs font-semibold text-ink-500"
                      >
                        {value || <span className="sr-only">Действие</span>}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.map((task) => {
                    const branch = branchNames.data?.find((entry) => entry.id === task.branchId)
                    return (
                      <tr
                        key={task.id}
                        className={`border-t border-ink-200 ${selected === task.id ? 'bg-brand-green-50' : 'hover:bg-paper-hover'}`}
                      >
                        <td className="min-w-44 p-4">
                          <button
                            type="button"
                            className="rounded-sm text-left font-semibold underline decoration-ink-300 underline-offset-4 hover:text-brand-green-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-green-400"
                            onClick={() => openListedTask(task)}
                          >
                            {task.title}
                          </button>
                          <div className="mt-1 text-ink-500">{taskTypeLabel(task.taskType)}</div>
                        </td>
                        <td className="p-3">
                          {branch?.name ??
                            (branchNames.pending
                              ? 'Загружаем аптеку…'
                              : 'Название аптеки недоступно')}
                          {branch && (
                            <div className="mt-1 text-ink-500">
                              {[branch.city, branch.address].filter(Boolean).join(' · ') ||
                                'Адрес не указан'}
                            </div>
                          )}
                        </td>
                        <td className="p-3">
                          <TaskStatusBadge status={task.status} />
                        </td>
                        <td className="p-3">{taskPriorityLabel(task.priority)}</td>
                        <td className="p-3">
                          {task.dueAt
                            ? new Date(task.dueAt).toLocaleString('ru-RU', {
                                timeZone: 'Asia/Almaty',
                              })
                            : 'Срок не записан'}
                        </td>
                        <td className="p-3">{task.itemCount}</td>
                        <td className="p-3">
                          {task.pendingReviewCount == null ? (
                            <span className="text-ink-500">Нет данных</span>
                          ) : task.pendingReviewCount > 0 ? (
                            <span className="chip chip-blue whitespace-nowrap">
                              Пунктов: {task.pendingReviewCount}
                            </span>
                          ) : (
                            <span className="text-ink-500">Нет</span>
                          )}
                        </td>
                        <td className="p-3">
                          {task.rewardAmountKzt === null
                            ? '—'
                            : task.rewardAmountKzt === 0
                              ? 'Без вознаграждения'
                              : formatKzt(task.rewardAmountKzt)}
                        </td>
                        <td className="p-3">
                          <Button variant="outline" size="sm" onClick={() => openListedTask(task)}>
                            {queue === 'mine'
                              ? 'Открыть мой пункт'
                              : (task.pendingReviewCount ?? 0) > 0
                                ? 'Открыть отчёт'
                                : 'Открыть задание'}
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="p-6" role="status">
              <h3 className="font-semibold">
                {queue === 'mine'
                  ? 'Личных заданий по этим условиям нет.'
                  : filtered
                    ? 'По этим условиям заданий нет.'
                    : 'В вашей области заданий пока нет.'}
              </h3>
              <p className="mt-2 text-sm text-ink-500">
                {queue === 'mine'
                  ? 'Если назначение ожидается, уточните привязку учётной записи у администратора. Остальные доступные задания можно посмотреть во «Всей истории».'
                  : filtered
                    ? 'Измените очередь или название задания.'
                    : 'Здесь появятся доступные вам задания и фотоотчёты.'}
              </p>
            </div>
          )}
          <div className="flex justify-end gap-2 border-t border-ink-200 bg-paper-input px-4 py-3">
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
        <div
          ref={detailRegion}
          role="region"
          aria-label="Открытое задание"
          tabIndex={-1}
          className="scroll-mt-20 rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-brand-green-400"
        >
          <div className="mb-3 flex flex-wrap justify-between gap-2 border-t border-ink-200 pt-5">
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
            canComment={canComment}
            canCancel={canCancel}
            focusReview={reviewFocus}
            preferredItemId={executionItemId}
            onChanged={list.reload}
          />
        </div>
      )}
      {creating && (
        <CreateTaskDialog
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false)
            openTask(id)
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
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
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
