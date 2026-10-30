import { useCallback, useEffect, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import TaskReference from './TaskReference'
import TaskConversation from './TaskConversation'
import { usePlacementRead } from './usePlacementRead'
import { TaskStockFields, InstructionDownload } from './TaskStockFields'
import type { TaskCapture } from './tasks-api'
import type { MerchTask, TaskAttempt, TaskEvidence, TaskGateway, TaskItem } from './tasks-api'
import { taskStatusLabel } from './task-labels'
import TaskStatusBadge from './TaskStatusBadge'
const rewardReasons: Record<string, string> = {
  zero_reward: 'Задание без вознаграждения',
  historical_approval_or_outbox_requires_HQ:
    'Историческое начисление требует отдельной проверки HQ',
  historical_due_at_not_recorded: 'В источнике не записан срок задания',
  executor_unresolved: 'Исполнитель не подтверждён',
  executor_mismatch: 'Исполнители пунктов требуют проверки',
  pharmacy_binding_unverified: 'Связь аптеки ещё не подтверждена',
}

function focusTaskItem(itemId: string) {
  const element = document.getElementById(`merch-task-item-${itemId}`)
  element?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  element?.focus({ preventScroll: true })
}

export default function MerchTaskDetail({
  id,
  gateway,
  canExecute = true,
  canReview = false,
  canComment = false,
  canCancel = false,
  focusReview = false,
  preferredItemId = null,
  onChanged,
}: {
  id: string
  gateway: TaskGateway
  canExecute?: boolean
  canReview?: boolean
  canComment?: boolean
  canCancel?: boolean
  focusReview?: boolean
  preferredItemId?: string | null
  onChanged?: () => void
}) {
  const load = useCallback((signal: AbortSignal) => gateway.get(id, signal), [gateway, id])
  const read = usePlacementRead(load)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState('')
  const [cancelPending, setCancelPending] = useState(false)
  const [cancelError, setCancelError] = useState('')
  const pendingItems = read.data?.items.filter((item) => item.status === 'submitted') ?? []
  const nextReviewItem =
    gateway.realm === 'admin' && canReview && gateway.review
      ? pendingItems.find((item) => item.canReview === true && !!item.currentAttempt)
      : undefined
  const nextPendingItem =
    pendingItems.find((item) => item.id === read.data?.nextPendingReviewItemId) ?? pendingItems[0]
  const nextItem = nextReviewItem ?? nextPendingItem
  const nextItemId = nextItem?.id
  useEffect(() => {
    if (focusReview && nextItemId) focusTaskItem(nextItemId)
  }, [focusReview, nextItemId, read.data])
  useEffect(() => {
    // A queue hint only navigates within the freshly authorized detail response.
    // All action buttons continue to use the item's current server permissions.
    if (
      !focusReview &&
      preferredItemId &&
      read.data?.items.some((item) => item.id === preferredItemId)
    )
      focusTaskItem(preferredItemId)
  }, [focusReview, preferredItemId, read.data])
  const refresh = () => {
    read.reload()
    onChanged?.()
  }
  return (
    <div className="flex flex-col gap-4">
      {read.pending && <p role="status">Загружаем задание…</p>}
      {!!read.error && (
        <div role="alert" className="card p-4">
          <p>{describeError(read.error)}</p>
          <Button variant="outline" className="mt-2" onClick={read.reload}>
            Повторить
          </Button>
        </div>
      )}
      {read.data && (
        <>
          <div className="card border-t-4 border-t-brand-green-500 p-4 sm:p-6">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-ink-500">
              Карточка задания
            </p>
            <h2 className="break-words text-xl font-bold leading-7">{read.data.title}</h2>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <TaskStatusBadge status={read.data.status} />
              <span className="text-xs text-ink-500">Пунктов: {read.data.items.length}</span>
            </div>
            <p className="mt-2 text-[13px] text-ink-500">
              {read.data.dueAt &&
                `До ${new Date(read.data.dueAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })}, время Алматы`}
            </p>
            <TaskReward task={read.data} />
            {gateway.realm === 'admin' && pendingItems.length > 0 && (
              <div className="mt-3 rounded-xl border border-brand-green-200 bg-brand-green-50 p-4">
                <p className="font-semibold">
                  Непроверенных пунктов: {read.data.pendingReviewCount ?? pendingItems.length}
                </p>
                {nextItem && (
                  <>
                    <p className="mt-1 text-sm text-ink-600">Следующий пункт: {nextItem.title}</p>
                    <Button
                      className="mt-3"
                      variant="outline"
                      onClick={() => focusTaskItem(nextItem.id)}
                    >
                      {nextReviewItem
                        ? 'Перейти к проверке следующего пункта'
                        : 'Посмотреть непроверенный пункт'}
                    </Button>
                  </>
                )}
                {!nextReviewItem && gateway.realm === 'admin' && (
                  <p className="mt-2 text-sm text-ink-500">
                    Проверка этих пунктов недоступна для вашей текущей учётной записи. Можно
                    просмотреть отчёт.
                  </p>
                )}
              </div>
            )}
            {canCancel && read.data.canCancel && gateway.cancel && (
              <Button
                variant="outline"
                className="mt-3"
                onClick={() => {
                  setCancelReason('')
                  setCancelError('')
                  setCancelOpen(true)
                }}
              >
                Отменить задание
              </Button>
            )}
            {cancelOpen && canCancel && read.data.canCancel && gateway.cancel && (
              <Modal
                open
                title="Отмена задания"
                subtitle="Файлы и история сохраняются. Отмена не списывает и не возвращает деньги."
                onClose={() => {
                  if (!cancelPending) setCancelOpen(false)
                }}
              >
                <form
                  className="space-y-3"
                  onSubmit={async (event) => {
                    event.preventDefault()
                    if (!read.data) return
                    setCancelPending(true)
                    setCancelError('')
                    try {
                      await gateway.cancel!(id, read.data.version, cancelReason)
                      setCancelOpen(false)
                      refresh()
                    } catch (error) {
                      setCancelError(describeError(error))
                      setCancelOpen(false)
                      read.reload()
                    } finally {
                      setCancelPending(false)
                    }
                  }}
                >
                  <Field label="Причина отмены">
                    <textarea
                      aria-label="Причина отмены"
                      className="inp min-h-20 w-full"
                      minLength={3}
                      maxLength={1200}
                      required
                      value={cancelReason}
                      onChange={(event) => setCancelReason(event.target.value)}
                    />
                  </Field>
                  <Button type="submit" disabled={cancelPending || cancelReason.trim().length < 3}>
                    Подтвердить отмену
                  </Button>
                </form>
              </Modal>
            )}
            {cancelError && <p role="alert">{cancelError}</p>}
          </div>
          {read.data.items.map((item, index) => (
            <div
              key={item.id}
              id={`merch-task-item-${item.id}`}
              role="region"
              aria-label={`Пункт: ${item.title}`}
              tabIndex={-1}
              className="scroll-mt-20 rounded-xl outline-none focus:ring-2 focus:ring-brand-green-400"
            >
              <TaskItemCard
                item={item}
                ordinal={index + 1}
                gateway={gateway}
                canExecute={canExecute}
                canReview={canReview}
                canComment={canComment}
                canCancel={canCancel}
                refresh={refresh}
              />
            </div>
          ))}
          {!read.data.items.length && (
            <p className="card p-4 text-ink-500">Пункты задания не найдены в источнике.</p>
          )}
        </>
      )}
    </div>
  )
}
function TaskReward({ task }: { task: MerchTask }) {
  if (task.rewardAmountKzt === null)
    return <p className="mt-3 text-[13px] text-ink-500">Финансовые данные недоступны.</p>
  const credited = task.reward?.state === 'credited' && !!task.reward.walletEntryId
  return (
    <div className="mt-3 rounded-lg bg-paper-input p-3 text-[13px]">
      <p className="font-semibold">
        {task.rewardAmountKzt === 0
          ? 'Задание без вознаграждения'
          : `Предусмотрено: ${formatKzt(task.rewardAmountKzt)}`}
      </p>
      {task.reward && (
        <p className="mt-1">
          {credited
            ? `Начислено в кошелёк: ${formatKzt(task.reward.amountKzt)}`
            : task.reward.state === 'not_monetary'
              ? 'Денежное начисление не предусмотрено.'
              : (rewardReasons[task.reward.reason] ?? 'Начисление ожидает отдельной проверки.')}
        </p>
      )}
      <p className="mt-1 text-[12px] text-ink-500">
        Отправленный фотоотчёт и предусмотренная сумма не означают начисление или банковскую
        выплату.
      </p>
    </div>
  )
}
function TaskItemCard({
  item,
  ordinal,
  gateway,
  canExecute,
  canReview,
  canComment,
  canCancel,
  refresh,
}: {
  item: TaskItem
  ordinal: number
  gateway: TaskGateway
  canExecute: boolean
  canReview: boolean
  canComment: boolean
  canCancel: boolean
  refresh: () => void
}) {
  const [checked, setChecked] = useState<string[]>(item.currentAttempt?.completedChecklistIds ?? [])
  const [comment, setComment] = useState('')
  const [file, setFile] = useState<{ value: File; uploadId: string } | null>(null)
  const [capture, setCapture] = useState<TaskCapture>()
  const [locating, setLocating] = useState(false)
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState('')
  const [failureStatus, setFailureStatus] = useState<number>()
  const [review, setReview] = useState<'approve' | 'revision' | null>(null)
  const [reason, setReason] = useState('')
  const [manual, setManual] = useState(false)
  const [continuation, setContinuation] = useState<'resume' | 'review' | null>(null)
  const [history, setHistory] = useState(false)
  const [blockOpen, setBlockOpen] = useState(false)
  const [itemCancelOpen, setItemCancelOpen] = useState(false)
  const [conversationOpen, setConversationOpen] = useState(false)
  const attempt = item.currentAttempt
  const perform = async (action: () => Promise<unknown>) => {
    if (pending) return
    setPending(true)
    setFailure('')
    try {
      await action()
      setFile(null)
      setChecked([])
      setComment('')
      setReview(null)
      setContinuation(null)
      setBlockOpen(false)
      setItemCancelOpen(false)
      refresh()
    } catch (error) {
      setFailure(describeError(error))
      setReview(null)
      setContinuation(null)
      setBlockOpen(false)
      setItemCancelOpen(false)
      const status = error as { status?: number; response?: { status?: number } }
      setFailureStatus(status?.response?.status ?? status?.status)
    } finally {
      setPending(false)
    }
  }
  const locked = pending || failureStatus === 409
  if (failureStatus === 401 || failureStatus === 403)
    return (
      <div role="alert" className="card p-4">
        <p>{failure}</p>
        <Button className="mt-2" variant="outline" onClick={refresh}>
          Обновить доступ к заданию
        </Button>
      </div>
    )
  const complete =
    !!attempt &&
    attempt.evidence.length >= item.requiredPhotos &&
    (!item.execution?.requiresStock || !!item.execution.stock) &&
    (!item.execution?.requiresVideo ||
      attempt.evidence.some((e) => e.mimeType.startsWith('video/'))) &&
    item.checklist.filter((row) => row.required).every((row) => checked.includes(row.id))
  const manualSource = attempt?.executorIdentityStatus === 'source_preserved_unverified'
  const unverifiedExecutor =
    attempt?.executorIdentityStatus === 'capability_unverified' ||
    attempt?.executorIdentityStatus === 'staff_access_unverified'
  const needsManualReview = manualSource || unverifiedExecutor
  const missingRequirements = [
    !attempt ? 'Начните выполнение пункта.' : '',
    attempt && attempt.evidence.length < item.requiredPhotos
      ? `Добавьте материалы: ${attempt.evidence.length} из ${item.requiredPhotos}.`
      : '',
    item.execution?.requiresVideo &&
    !attempt?.evidence.some((row) => row.mimeType.startsWith('video/'))
      ? 'Загрузите требуемое видео.'
      : '',
    item.execution?.requiresStock && !item.execution.stock ? 'Сохраните остатки.' : '',
    item.checklist.some((row) => row.required && !checked.includes(row.id))
      ? 'Отметьте обязательные пункты чек-листа.'
      : '',
  ].filter(Boolean)
  return (
    <section className="card flex min-w-0 flex-col gap-4 p-4 sm:p-5">
      {attempt?.executorIdentityStatus === 'staff_access_unverified' && (
        <p className="text-sm text-ink-500">
          Общий месячный фотоотчёт: личность исполнителя не подтверждена.
        </p>
      )}
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-ink-200 pb-4">
        <div className="min-w-0 flex-1 basis-48">
          <p className="mb-2 text-xs font-semibold text-ink-500">Пункт {ordinal}</p>
          <h3 className="break-words text-base font-bold leading-6">
            {item.title}
            {item.required && (
              <span className="ml-2 text-[11px] text-ink-500">Обязательный пункт</span>
            )}
          </h3>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <TaskStatusBadge status={item.status} />
            <span className="text-xs text-ink-500">
              Фото: {attempt?.evidence.length ?? 0} / {item.requiredPhotos}
            </span>
          </div>
        </div>
        {gateway.history && (
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={history}
            onClick={() => setHistory((value) => !value)}
          >
            {history ? 'Скрыть историю' : 'История попыток'}
          </Button>
        )}
      </div>
      {canCancel && item.canCancel && gateway.cancelItem && (
        <Button
          variant="outline"
          disabled={locked}
          onClick={() => {
            setReason('')
            setItemCancelOpen(true)
          }}
        >
          Отменить этот пункт
        </Button>
      )}
      {itemCancelOpen && gateway.cancelItem && (
        <Modal
          open
          title="Отмена одного пункта"
          subtitle="Остальные пункты и фотографии сохраняются. Начисления автоматически не отменяются."
          onClose={() => {
            if (!pending) setItemCancelOpen(false)
          }}
        >
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault()
              void perform(() => gateway.cancelItem!(item.id, item.version, reason))
            }}
          >
            <Field label="Причина отмены пункта">
              <textarea
                aria-label="Причина отмены пункта"
                className="inp min-h-20 w-full"
                required
                minLength={3}
                maxLength={1200}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            <Button type="submit" disabled={pending || reason.trim().length < 3}>
              Подтвердить отмену пункта
            </Button>
          </form>
        </Modal>
      )}
      {item.blockedReason && (
        <p className="rounded-lg bg-paper-input p-3 text-sm">Препятствие: {item.blockedReason}</p>
      )}
      {gateway.comments && (
        <Button
          className="self-start"
          variant="ghost"
          aria-expanded={conversationOpen}
          onClick={() => setConversationOpen((value) => !value)}
        >
          {conversationOpen ? 'Скрыть обсуждение' : 'Обсуждение пункта'}
        </Button>
      )}
      {conversationOpen && gateway.comments && (
        <TaskConversation
          key={item.id}
          itemId={item.id}
          gateway={gateway}
          canWrite={item.canComment === true && (canComment || gateway.realm === 'pharmacist')}
        />
      )}
      {canExecute && item.canBlock && gateway.block && (
        <Button
          variant="outline"
          disabled={locked}
          onClick={() => {
            setReason('')
            setBlockOpen(true)
          }}
        >
          Сообщить о препятствии
        </Button>
      )}
      {blockOpen && gateway.block && (
        <Modal
          open
          title="Препятствие выполнению"
          onClose={() => {
            if (!pending) setBlockOpen(false)
          }}
        >
          <form
            className="space-y-3"
            onSubmit={(event) => {
              event.preventDefault()
              void perform(() => gateway.block!(item.id, item.version, reason))
            }}
          >
            <Field label="Причина препятствия">
              <textarea
                aria-label="Причина препятствия"
                className="inp min-h-20 w-full"
                minLength={3}
                maxLength={1200}
                required
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            <Button type="submit" disabled={pending || reason.trim().length < 3}>
              Сохранить препятствие
            </Button>
          </form>
        </Modal>
      )}
      {item.instructions && (
        <div className="rounded-lg bg-paper-input p-4">
          <h4 className="mb-2 text-sm font-bold">Что нужно сделать</h4>
          <p className="whitespace-pre-wrap break-words text-sm leading-6">{item.instructions}</p>
        </div>
      )}
      <TaskReference
        key={`${item.id}:${item.execution?.clientReferencePhotoId ?? 'none'}`}
        item={item}
        gateway={gateway}
      />
      {item.execution?.requiresVideo && <InstructionDownload item={item} gateway={gateway} />}
      {item.execution?.requiresStock && (
        <TaskStockFields
          key={`${item.id}:${attempt?.number ?? 0}`}
          item={item}
          gateway={gateway}
          editable={
            canExecute &&
            attempt?.status === 'draft' &&
            (item.canUpload === true || item.canSubmit === true)
          }
          onSaved={refresh}
        />
      )}
      {item.availableFrom && (
        <p className="text-[12px] text-ink-500">
          Доступно с{' '}
          {new Date(item.availableFrom).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })}, время
          Алматы.
        </p>
      )}
      {canExecute && !attempt && ['in_progress', 'submitted'].includes(item.status) && (
        <p className="rounded-lg bg-paper-input p-3 text-[13px]">
          Этот пункт сейчас недоступен для изменений по этой ссылке. Обратитесь к ответственному
          сотруднику.
        </p>
      )}
      {item.checklist.length > 0 && (
        <div className="flex flex-col gap-2 rounded-lg border border-ink-200 p-3">
          <h4 className="text-sm font-bold">Чек-лист выполнения</h4>
          {item.checklist.map((row) => (
            <label
              key={row.id}
              className="flex min-h-10 items-start gap-3 rounded-md py-2 text-sm leading-5"
            >
              <input
                className="mt-0.5 h-4 w-4 shrink-0"
                type="checkbox"
                checked={checked.includes(row.id)}
                disabled={locked || !canExecute || item.canSubmit !== true}
                onChange={(event) =>
                  setChecked((current) =>
                    event.target.checked
                      ? [...current, row.id]
                      : current.filter((id) => id !== row.id),
                  )
                }
              />
              {row.title}
              {row.required && <span className="text-ink-400">*</span>}
            </label>
          ))}
        </div>
      )}
      {attempt && (
        <>
          <p className="text-[12px] font-semibold text-ink-500">
            Текущая попытка №{attempt.number}: {taskStatusLabel(attempt.status)}
          </p>
          <EvidenceList rows={attempt.evidence} gateway={gateway} />
          <ReviewNote attempt={attempt} />
        </>
      )}
      {item.legacyEvidence.length > 0 && (
        <details>
          <summary className="cursor-pointer text-[12px] font-semibold">
            Исторические фото · {item.legacyEvidence.length}
          </summary>
          <EvidenceList rows={item.legacyEvidence} gateway={gateway} />
          <p className="text-[12px] text-ink-500">
            Исторические файлы сохранены отдельно и не заменяют фото текущей попытки.
          </p>
        </details>
      )}
      {gateway.realm === 'admin' &&
        gateway.continueSource &&
        (item.canResumeSource === true || item.canAdoptSourceReview === true) && (
          <div className="rounded-lg border border-paper-line p-3 text-[13px]">
            <p>
              Историческое выполнение сохранено. Продолжение не присваивает его текущему сотруднику
              и не начисляет вознаграждение.
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              {item.canResumeSource === true && (
                <Button
                  variant="outline"
                  disabled={locked}
                  onClick={() => {
                    setContinuation('resume')
                    setReason('')
                  }}
                >
                  Запросить новую попытку
                </Button>
              )}
              {item.canAdoptSourceReview === true && (
                <Button
                  variant="outline"
                  disabled={locked}
                  onClick={() => {
                    setContinuation('review')
                    setReason('')
                  }}
                >
                  Проверить исторический отчёт
                </Button>
              )}
            </div>
          </div>
        )}
      {canExecute && item.canStart === true && (
        <div>
          <Button disabled={locked} onClick={() => perform(() => gateway.start(item.id))}>
            {item.status === 'blocked'
              ? 'Продолжить после препятствия'
              : item.status === 'revision_required'
                ? 'Начать новую попытку'
                : 'Начать выполнение'}
          </Button>
        </div>
      )}
      {canExecute && item.canUpload === true && (
        <div className="rounded-lg border border-paper-line p-3">
          <Field
            label={
              item.execution?.requiresVideo ? 'Видео MP4, MOV или WebM' : 'Фото JPG, PNG или WebP'
            }
            hint={
              item.execution?.requiresVideo
                ? 'До 30 МБ. Видео просматривает проверяющий.'
                : 'До 20 МБ и 12 Мп; исходное фото проверяется сервером.'
            }
          >
            <input
              type="file"
              accept={
                item.execution?.requiresVideo
                  ? 'video/mp4,video/quicktime,video/webm'
                  : 'image/jpeg,image/png,image/webp'
              }
              capture="environment"
              disabled={locked}
              aria-label={`${item.execution?.requiresVideo ? 'Видео' : 'Фото'} для ${item.title}`}
              onChange={(event) => {
                const value = event.target.files?.[0]
                if (!value) return
                if (
                  !(
                    item.execution?.requiresVideo
                      ? ['video/mp4', 'video/quicktime', 'video/webm']
                      : ['image/jpeg', 'image/png', 'image/webp']
                  ).includes(value.type) ||
                  value.size === 0 ||
                  value.size > (item.execution?.requiresVideo ? 30 : 20) * 1024 * 1024
                ) {
                  setFailure(
                    item.execution?.requiresVideo
                      ? 'Выберите видео до 30 МБ.'
                      : 'Выберите JPG, PNG или WebP до 20 МБ.',
                  )
                  setFile(null)
                  return
                }
                setFailure('')
                setFile({ value, uploadId: crypto.randomUUID() })
              }}
            />
          </Field>
          <Button
            variant="ghost"
            disabled={locating || locked}
            onClick={() => {
              setLocating(true)
              if (!navigator.geolocation) {
                setCapture({ status: 'unavailable', reason: 'Браузер не поддерживает геолокацию' })
                setLocating(false)
                return
              }
              navigator.geolocation.getCurrentPosition(
                (position) => {
                  setCapture({
                    status: 'available',
                    latitude: position.coords.latitude,
                    longitude: position.coords.longitude,
                    accuracy: position.coords.accuracy,
                    measuredAt: new Date(position.timestamp).toISOString(),
                    captureSource: 'file_picker',
                  })
                  setLocating(false)
                },
                () => {
                  setCapture({ status: 'unavailable', reason: 'Местоположение не получено' })
                  setLocating(false)
                },
                { timeout: 8000, maximumAge: 0 },
              )
            }}
          >
            {locating ? 'Определяем местоположение…' : 'Прикрепить местоположение'}
          </Button>
          {capture && (
            <p className="text-xs text-ink-500">
              {capture.status === 'available'
                ? 'Местоположение прикреплено; требуется ручная проверка.'
                : 'Местоположение недоступно; потребуется ручная проверка.'}
            </p>
          )}
          <Button
            className="mt-2"
            variant="outline"
            disabled={!file || locked}
            onClick={() => {
              if (file)
                void perform(() =>
                  capture
                    ? gateway.upload(item.id, file.uploadId, file.value, capture)
                    : gateway.upload(item.id, file.uploadId, file.value),
                )
            }}
          >
            {pending
              ? 'Загружаем…'
              : item.execution?.requiresVideo
                ? 'Загрузить видео'
                : 'Загрузить фото'}
          </Button>
        </div>
      )}
      {canExecute && item.canSubmit === true && (
        <>
          <Field label="Комментарий к фотоотчёту" optional>
            <Input
              aria-label={`Комментарий для ${item.title}`}
              maxLength={1200}
              value={comment}
              disabled={locked}
              onChange={(event) => setComment(event.target.value)}
            />
          </Field>
          <div>
            <Button
              disabled={!complete || locked}
              onClick={() => {
                if (attempt)
                  void perform(() => gateway.submit(item.id, attempt.number, checked, comment))
              }}
            >
              Отправить пункт на проверку
            </Button>
          </div>
          {!complete && (
            <div className="rounded-lg bg-paper-input p-3 text-sm text-ink-600" role="status">
              <p className="font-semibold">Перед отправкой</p>
              <ul className="mt-2 list-inside list-disc space-y-1">
                {missingRequirements.map((requirement) => (
                  <li key={requirement}>{requirement}</li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
      {gateway.realm === 'admin' && canReview && item.canReview === true && attempt && (
        <div className="flex flex-wrap gap-2 border-t border-ink-200 pt-4">
          <Button
            disabled={locked}
            onClick={() => {
              setReview('approve')
              setReason('')
              setManual(false)
            }}
          >
            Принять пункт
          </Button>
          <Button
            variant="outline"
            disabled={locked}
            onClick={() => {
              setReview('revision')
              setReason('')
              setManual(false)
            }}
          >
            Вернуть пункт на доработку
          </Button>
        </div>
      )}
      {failureStatus === 409 && (
        <Button variant="outline" onClick={refresh}>
          Загрузить актуальную попытку
        </Button>
      )}
      {failure && (
        <p
          role="alert"
          className="rounded-lg bg-surface-danger p-3 text-[13px] text-surface-danger-strong"
        >
          {failure} Повторное действие не выполняется автоматически.
        </p>
      )}
      {history && gateway.history && <AttemptHistory itemId={item.id} gateway={gateway} />}
      {review && attempt && gateway.review && (
        <Modal
          open
          title={review === 'approve' ? 'Принять пункт задания' : 'Вернуть пункт на доработку'}
          onClose={() => {
            if (!pending) setReview(null)
          }}
          footer={
            <>
              <Button variant="ghost" disabled={locked} onClick={() => setReview(null)}>
                Отмена
              </Button>
              <Button
                disabled={locked || reason.trim().length < 3 || (needsManualReview && !manual)}
                onClick={() =>
                  perform(() =>
                    gateway.review!(item.id, {
                      attemptId: attempt.id,
                      expectedItemVersion: item.version,
                      decision: review,
                      reason: reason.trim(),
                      ...(unverifiedExecutor ? { manualUnverifiedExecutor: manual } : {}),
                      ...(manualSource ? { manualSourcePreserved: manual } : {}),
                    }),
                  )
                }
              >
                Подтвердить решение
              </Button>
            </>
          }
        >
          <p className="mb-3 text-[13px]">
            {item.title} · Попытка №{attempt.number}. Решение относится только к этому пункту.
          </p>
          {needsManualReview && (
            <label className="mb-3 flex gap-2 text-[13px]">
              <input
                type="checkbox"
                checked={manual}
                disabled={locked}
                onChange={(event) => setManual(event.target.checked)}
              />
              {manualSource
                ? 'Я проверил сохранённый исторический отчёт; исполнитель не подтверждён, денежное начисление остаётся на проверке HQ.'
                : 'Я проверил фотоотчёт вручную; личность исполнителя не подтверждена.'}
            </label>
          )}
          <Field label="Причина решения" hint="От 3 до 1200 символов">
            <Input
              aria-label="Причина проверки пункта"
              maxLength={1200}
              value={reason}
              disabled={locked}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        </Modal>
      )}
      {continuation && gateway.continueSource && (
        <Modal
          open
          title={
            continuation === 'resume' ? 'Запросить новую попытку' : 'Проверить исторический отчёт'
          }
          onClose={() => {
            if (!pending) setContinuation(null)
          }}
          footer={
            <>
              <Button variant="ghost" disabled={locked} onClick={() => setContinuation(null)}>
                Отмена
              </Button>
              <Button
                disabled={locked || reason.trim().length < 3}
                onClick={() =>
                  perform(() =>
                    gateway.continueSource!(item.id, {
                      expectedItemVersion: item.version,
                      mode: continuation,
                      reason: reason.trim(),
                    }),
                  )
                }
              >
                Подтвердить продолжение
              </Button>
            </>
          }
        >
          <p className="mb-3 text-[13px]">
            {continuation === 'resume'
              ? 'Старые фото и история останутся неизменными. Исполнитель сможет начать отдельную новую попытку.'
              : 'Сохранённый отчёт станет доступен для отдельного ручного решения. Это не подтверждает личность исполнителя и не выполняет начисление.'}
          </p>
          <Field label="Основание продолжения" hint="От 3 до 1200 символов">
            <Input
              aria-label="Основание продолжения исторического задания"
              maxLength={1200}
              value={reason}
              disabled={locked}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        </Modal>
      )}
    </section>
  )
}
function ReviewNote({ attempt }: { attempt: TaskAttempt }) {
  return (
    <>
      {attempt.executorIdentityStatus === 'staff_access_unverified' &&
        attempt.staffSubmittedName && (
          <p className="text-[13px]">
            Подпись при сдаче: {attempt.staffSubmittedName}. Личность не подтверждена.
          </p>
        )}
      {attempt.comment && <p className="text-[13px]">Комментарий исполнителя: {attempt.comment}</p>}
      {attempt.review ? (
        <p className="rounded-lg bg-paper-input p-3 text-[13px]">
          {attempt.review.decision === 'approve' ? 'Пункт принят' : 'Пункт возвращён'}:{' '}
          {attempt.review.reason}
        </p>
      ) : null}
    </>
  )
}
function AttemptHistory({ itemId, gateway }: { itemId: string; gateway: TaskGateway }) {
  const [offset, setOffset] = useState(0)
  const load = useCallback(
    (signal: AbortSignal) => gateway.history!(itemId, offset, signal),
    [gateway, itemId, offset],
  )
  const read = usePlacementRead(load)
  return (
    <div className="rounded-lg border border-paper-line p-3">
      {read.pending && <p role="status">Загружаем историю…</p>}
      {!!read.error && <p role="alert">{describeError(read.error)}</p>}
      {read.data?.items.map((attempt) => (
        <div key={attempt.id} className="border-b py-3">
          <p className="text-[12px] font-bold">
            Попытка №{attempt.number} · {taskStatusLabel(attempt.status)}
          </p>
          <EvidenceList rows={attempt.evidence} gateway={gateway} />
          <ReviewNote attempt={attempt} />
        </div>
      ))}
      <div className="mt-2 flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={!offset}
          onClick={() => setOffset((value) => Math.max(0, value - 20))}
        >
          Назад
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={!read.data?.hasMore}
          onClick={() => setOffset((value) => value + 20)}
        >
          Далее
        </Button>
      </div>
    </div>
  )
}
function EvidenceList({ rows, gateway }: { rows: TaskEvidence[]; gateway: TaskGateway }) {
  return (
    <div className="my-2 grid grid-cols-1 gap-3 min-[440px]:grid-cols-2 lg:grid-cols-3">
      {rows.map((row) => (
        <PrivateEvidence key={row.id} row={row} gateway={gateway} />
      ))}
    </div>
  )
}
function PrivateEvidence({ row, gateway }: { row: TaskEvidence; gateway: TaskGateway }) {
  const video = row.mimeType.startsWith('video/')
  const [opened, setOpened] = useState(!video)
  const [image, setImage] = useState<{ id: string; url: string } | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!opened) return
    const controller = new AbortController()
    let url: string | null = null
    gateway
      .evidence(row.id, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        if (
          ![
            'image/jpeg',
            'image/png',
            'image/webp',
            'video/mp4',
            'video/quicktime',
            'video/webm',
          ].includes(blob.type)
        )
          throw new Error('Предпросмотр этого формата недоступен.')
        url = URL.createObjectURL(blob)
        setImage({ id: row.id, url })
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(describeError(failure))
      })
    return () => {
      controller.abort()
      if (url) URL.revokeObjectURL(url)
    }
  }, [gateway, row.id, opened])
  return (
    <figure className="rounded-lg border border-paper-line bg-paper-input p-2">
      {video && !opened ? (
        <Button variant="outline" onClick={() => setOpened(true)}>
          Открыть видео
        </Button>
      ) : image?.id === row.id ? (
        video ? (
          <div>
            <video
              src={image.url}
              controls
              playsInline
              preload="metadata"
              className="max-h-60 w-full"
            />
            <a href={image.url} download>
              Скачать видео
            </a>
            <Button
              variant="ghost"
              onClick={() => {
                setOpened(false)
                setImage(null)
              }}
            >
              Закрыть видео
            </Button>
          </div>
        ) : (
          <a href={image.url} target="_blank" rel="noreferrer">
            <img
              src={image.url}
              alt="Фотоотчёт по пункту задания"
              className="h-40 w-full rounded-md object-contain"
            />
          </a>
        )
      ) : error ? (
        <p role="alert" className="text-[12px]">
          {error}
        </p>
      ) : (
        <p className="py-5 text-[12px]">Загружаем закрытое фото…</p>
      )}
      <figcaption className="mt-2 text-[11px] text-ink-500">
        Доказательство требует ручной проверки.{' '}
        {row.capture?.distanceMeters !== undefined &&
          `Расстояние: ${row.capture.distanceMeters} м. `}
        {row.capture?.location?.reason}
      </figcaption>
    </figure>
  )
}
