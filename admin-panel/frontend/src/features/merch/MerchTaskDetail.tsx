import { useCallback, useEffect, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import { usePlacementRead } from './usePlacementRead'
import { TaskStockFields, InstructionDownload } from './TaskStockFields'
import type { TaskCapture } from './tasks-api'
import type { MerchTask, TaskAttempt, TaskEvidence, TaskGateway, TaskItem } from './tasks-api'

const taskStatusLabel = (value: string) =>
  (
    ({
      ready: 'Назначено',
      sent: 'Отправлено',
      assigned: 'Назначено',
      accepted: 'Принято в работу',
      in_progress: 'В работе',
      submitted: 'На проверке',
      completed: 'Выполнено',
      approved: 'Одобрено',
      revision_required: 'Нужна доработка',
      draft: 'Черновик',
      cancelled: 'Отменено',
      overdue: 'Просрочено',
      rejected: 'Отклонено',
    }) as Record<string, string>
  )[value] ?? 'Исторический статус'
const rewardReasons: Record<string, string> = {
  zero_reward: 'Задание без вознаграждения',
  historical_approval_or_outbox_requires_HQ:
    'Историческое начисление требует отдельной проверки HQ',
  historical_due_at_not_recorded: 'В источнике не записан срок задания',
  executor_unresolved: 'Исполнитель не подтверждён',
  executor_mismatch: 'Исполнители пунктов требуют проверки',
  pharmacy_binding_unverified: 'Связь аптеки ещё не подтверждена',
}

export default function MerchTaskDetail({
  id,
  gateway,
  canExecute = true,
  canReview = false,
  onChanged,
}: {
  id: string
  gateway: TaskGateway
  canExecute?: boolean
  canReview?: boolean
  onChanged?: () => void
}) {
  const load = useCallback((signal: AbortSignal) => gateway.get(id, signal), [gateway, id])
  const read = usePlacementRead(load)
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
          <div className="card p-4">
            <h2 className="text-xl font-bold">{read.data.title}</h2>
            <p className="mt-2 text-[13px] text-ink-500">
              {taskStatusLabel(read.data.status)}
              {read.data.dueAt &&
                ` · До ${new Date(read.data.dueAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })}, время Алматы`}
            </p>
            <TaskReward task={read.data} />
          </div>
          {read.data.items.map((item) => (
            <TaskItemCard
              key={item.id}
              item={item}
              gateway={gateway}
              canExecute={canExecute}
              canReview={canReview}
              refresh={refresh}
            />
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
  gateway,
  canExecute,
  canReview,
  refresh,
}: {
  item: TaskItem
  gateway: TaskGateway
  canExecute: boolean
  canReview: boolean
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
      refresh()
    } catch (error) {
      setFailure(describeError(error))
      setReview(null)
      setContinuation(null)
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
  const needsManualReview =
    manualSource || attempt?.executorIdentityStatus === 'capability_unverified'
  return (
    <section className="card flex flex-col gap-3 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-bold">
            {item.title}
            {item.required && (
              <span className="ml-2 text-[11px] text-ink-500">Обязательный пункт</span>
            )}
          </h3>
          <p className="mt-1 text-[12px] text-ink-500">
            {taskStatusLabel(item.status)} · Фото: {attempt?.evidence.length ?? 0} /{' '}
            {item.requiredPhotos}
          </p>
        </div>
        {gateway.history && (
          <Button variant="ghost" size="sm" onClick={() => setHistory((value) => !value)}>
            {history ? 'Скрыть историю' : 'История попыток'}
          </Button>
        )}
      </div>
      <p className="whitespace-pre-wrap break-words text-[13px] leading-6">{item.instructions}</p>
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
        <div className="flex flex-col gap-2">
          {item.checklist.map((row) => (
            <label key={row.id} className="flex gap-2 text-[13px]">
              <input
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
            {item.status === 'revision_required' ? 'Начать новую попытку' : 'Начать выполнение'}
          </Button>
        </div>
      )}
      {canExecute && item.canUpload === true && (
        <div className="rounded-lg border border-paper-line p-3">
          <Field
            label={item.execution?.requiresVideo ? 'Видео MP4, MOV или WebM' : 'Фото JPG или PNG'}
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
                  : 'image/jpeg,image/png'
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
                      : ['image/jpeg', 'image/png']
                  ).includes(value.type) ||
                  value.size === 0 ||
                  value.size > (item.execution?.requiresVideo ? 30 : 20) * 1024 * 1024
                ) {
                  setFailure(
                    item.execution?.requiresVideo
                      ? 'Выберите видео до 30 МБ.'
                      : 'Выберите JPG или PNG до 20 МБ.',
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
            <p className="text-[12px] text-ink-500">
              Для отправки нужны доказательства текущей попытки, сохранённые остатки (если
              запрошены) и обязательные пункты чек-листа.
            </p>
          )}
        </>
      )}
      {gateway.realm === 'admin' && canReview && item.canReview === true && attempt && (
        <div className="flex gap-2">
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
                      ...(attempt.executorIdentityStatus === 'capability_unverified'
                        ? { manualUnverifiedExecutor: manual }
                        : {}),
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
    <div className="my-2 grid grid-cols-2 gap-3 sm:grid-cols-3">
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
          !['image/jpeg', 'image/png', 'video/mp4', 'video/quicktime', 'video/webm'].includes(
            blob.type,
          )
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
