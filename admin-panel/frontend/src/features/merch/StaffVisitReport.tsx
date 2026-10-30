import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { usePlacementRead } from './usePlacementRead'
import { StaffError, type StaffClient } from './staff-access-api'

const failureText = (error: unknown) =>
  error instanceof Error ? error.message : 'Не удалось выполнить действие.'
const portions: Record<string, string> = {
  whole: 'Целая полка',
  left: 'Левая половина',
  right: 'Правая половина',
}
const statuses: Record<string, string> = {
  assigned: 'Новый отчёт',
  in_progress: 'В работе',
  submitted: 'На проверке',
  completed: 'Принят',
  revision_required: 'На доработке',
  cancelled: 'Закрыт',
}
export default function StaffVisitReport({
  client,
  id,
  initialEmployeeName,
  readOnly,
  onBack,
}: {
  client: StaffClient
  id: string
  initialEmployeeName?: string
  readOnly: boolean
  onBack: () => void
}) {
  const load = useCallback((signal: AbortSignal) => client.visit(id, signal), [client, id])
  const read = usePlacementRead(load)
  const [employeeName, setEmployeeName] = useState(initialEmployeeName ?? '')
  const [confirmed, setConfirmed] = useState(!!initialEmployeeName)
  const [notes, setNotes] = useState<string | null>(null)
  const [cameraItem, setCameraItem] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const visit = read.data
  const editable = !!visit?.canEdit && !readOnly
  const required = visit?.dispatch.items.filter((item) => item.required) ?? []
  const awaiting =
    visit?.dispatch.items.filter(
      (item) => !['completed', 'cancelled', 'submitted'].includes(item.status),
    ) ?? []
  const complete = awaiting.length > 0 && awaiting.every((item) => item.canSubmit === true)
  const act = async (operation: () => Promise<unknown>) => {
    if (pending) return
    setPending(true)
    setError('')
    try {
      await operation()
      read.reload()
    } catch (failure) {
      setError(failureText(failure))
      if (failure instanceof StaffError && [403, 409].includes(failure.status)) read.reload()
    } finally {
      setPending(false)
    }
  }
  return (
    <section className="space-y-4">
      <Button variant="outline" onClick={onBack}>
        К аптекам
      </Button>
      {read.pending && <p role="status">Загружаем фотоотчёт…</p>}
      {!!read.error && <p role="alert">{failureText(read.error)}</p>}
      {visit && (
        <>
          <header className="card space-y-2 p-5">
            <h1 className="text-xl font-bold">Фотоотчёт · {visit.month.slice(0, 7)}</h1>
            <p>
              Автор: {visit.employeeName || 'Не указан'}. Личность общего доступа не подтверждена.
            </p>
            <p>
              Состояние: {statuses[visit.dispatch.status] ?? visit.dispatch.status}. Готово{' '}
              {
                required.filter(
                  (item) =>
                    item.canSubmit === true || ['completed', 'submitted'].includes(item.status),
                ).length
              }{' '}
              / {required.length} обязательных пунктов.
            </p>
          </header>
          {!editable && (
            <p role="status" className="card p-4">
              Отчёт доступен только для просмотра. Его снимки и история сохраняются.
            </p>
          )}
          {visit.reason && <p className="card p-4">Комментарий проверки: {visit.reason}</p>}
          {editable && !confirmed && (
            <div className="card space-y-3 p-4">
              <Field label="Имя автора фотоотчёта">
                <Input
                  value={employeeName}
                  maxLength={120}
                  onChange={(event) => setEmployeeName(event.target.value)}
                />
              </Field>
              <p className="text-xs text-ink-500">
                Подпись отчёта не подтверждает личность и не даёт право на вознаграждение.
              </p>
              <Button
                disabled={pending || employeeName.trim().length < 2}
                onClick={() =>
                  void act(async () => {
                    await client.open(visit.branchId, visit.month, employeeName.trim())
                    setConfirmed(true)
                  })
                }
              >
                Продолжить фотоотчёт
              </Button>
            </div>
          )}
          {visit.places.map((place) => {
            const item = visit.dispatch.items.find((row) => row.id === place.taskItemId)
            if (!item) return null
            const evidence = item.currentAttempt?.evidence ?? []
            return (
              <article className="card space-y-3 p-4" key={place.taskItemId}>
                <h2 className="font-bold">
                  Полка {place.shelfCode} · {portions[place.portion] ?? place.portion}
                </h2>
                <p className="text-sm text-ink-500">
                  {[
                    place.category,
                    place.width === null ? null : `${place.width} м`,
                    place.advertiserName,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </p>
                <p>{item.instructions}</p>
                <p>
                  {statuses[item.status] ?? item.status} · Снимков текущей попытки:{' '}
                  {evidence.length} / {item.requiredPhotos}
                </p>
                {item.status === 'revision_required' && (
                  <p className="text-sm">
                    Снимки возвращённой попытки сохранены. Для повторной сдачи нужен новый снимок.
                  </p>
                )}
                {place.referenceAvailable && (
                  <PrivateImage
                    label="Посмотреть эталон"
                    alt={`Эталон полки ${place.shelfCode}`}
                    load={(signal) => client.reference(id, item.id, signal)}
                  />
                )}
                {evidence.map((photo, index) => (
                  <PrivateImage
                    key={photo.id}
                    label={`Снимок ${index + 1}`}
                    alt={`Снимок полки ${place.shelfCode}`}
                    load={(signal) => client.evidence(id, photo.id, signal)}
                  />
                ))}
                {editable && confirmed && (item.canUpload === true || item.canStart === true) && (
                  <Button disabled={pending} onClick={() => setCameraItem(item.id)}>
                    {evidence.length ? 'Сделать новый снимок' : 'Сфотографировать полку'}
                  </Button>
                )}
              </article>
            )
          })}
          {editable && confirmed && (
            <section className="card space-y-3 p-4">
              <Field label="Комментарий к отчёту">
                <textarea
                  className="inp min-h-24"
                  value={notes ?? visit.notes}
                  maxLength={1000}
                  onChange={(event) => setNotes(event.target.value)}
                />
              </Field>
              <p className="text-sm text-ink-500">
                Отправляется весь отчёт. Сервер повторно проверит состав размещений, срок и каждый
                обязательный снимок.
              </p>
              <Button
                disabled={pending || !complete}
                onClick={() =>
                  void act(() =>
                    client.submit(id, visit.version, employeeName.trim(), notes ?? visit.notes),
                  )
                }
              >
                {pending ? 'Отправляем…' : 'Отправить весь отчёт на проверку'}
              </Button>
            </section>
          )}
        </>
      )}
      {error && <p role="alert">{error}</p>}
      {!!read.error && (
        <Button variant="outline" onClick={read.reload}>
          Обновить отчёт
        </Button>
      )}
      {cameraItem && visit && editable && confirmed && (
        <CameraCapture
          onClose={() => setCameraItem(null)}
          onUpload={async (file, uploadId) => {
            try {
              await client.upload(id, cameraItem, uploadId, file, {
                status: 'unavailable',
                reason: 'location_not_requested',
                captureSource: 'web_camera',
              })
              setCameraItem(null)
              read.reload()
            } catch (failure) {
              if (failure instanceof StaffError && [403, 409].includes(failure.status)) {
                setCameraItem(null)
                read.reload()
              }
              throw failure
            }
          }}
        />
      )}
    </section>
  )
}

function PrivateImage({
  label,
  alt,
  load,
}: {
  label: string
  alt: string
  load: (signal: AbortSignal) => Promise<Blob>
}) {
  const current = useRef<{ url: string; controller?: AbortController }>({ url: '' })
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  useEffect(() => {
    const resource = current.current
    return () => {
      resource.controller?.abort()
      if (resource.url) URL.revokeObjectURL(resource.url)
    }
  }, [])
  return (
    <div className="space-y-2">
      <Button
        variant="outline"
        disabled={pending}
        onClick={async () => {
          const controller = new AbortController()
          current.current.controller?.abort()
          current.current.controller = controller
          setPending(true)
          setError('')
          try {
            const blob = await load(controller.signal)
            if (controller.signal.aborted) return
            if (!['image/jpeg', 'image/png', 'image/webp'].includes(blob.type))
              throw new Error('Сервис не вернул фотографию.')
            if (current.current.url) URL.revokeObjectURL(current.current.url)
            current.current.url = URL.createObjectURL(blob)
            setUrl(current.current.url)
          } catch (failure) {
            if (!controller.signal.aborted) setError(failureText(failure))
          } finally {
            if (!controller.signal.aborted) setPending(false)
          }
        }}
      >
        {pending ? 'Загружаем…' : label}
      </Button>
      {url && <img src={url} alt={alt} className="max-h-96 max-w-full rounded-lg object-contain" />}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}

function CameraCapture({
  onClose,
  onUpload,
}: {
  onClose: () => void
  onUpload: (file: File, uploadId: string) => Promise<void>
}) {
  const video = useRef<HTMLVideoElement | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const preview = useRef('')
  const alive = useRef(true)
  const [ready, setReady] = useState(false)
  const [photo, setPhoto] = useState<{ file: File; uploadId: string; preview: string } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const stop = () => {
    stream.current?.getTracks().forEach((track) => track.stop())
    stream.current = null
  }
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      stop()
      if (preview.current) URL.revokeObjectURL(preview.current)
    }
  }, [])
  return (
    <section
      role="dialog"
      aria-modal="true"
      aria-label="Снимок полки"
      className="fixed inset-0 z-50 overflow-y-auto bg-ink-900/60 p-4"
    >
      <div className="card mx-auto max-w-xl space-y-4 p-5">
        <h2 className="text-xl font-bold">Снимок полки</h2>
        <p className="text-sm">
          Снимок с камеры будет сохранён в закрытом фотоотчёте. Данные общего доступа не
          подтверждают личность автора.
        </p>
        <video
          ref={video}
          autoPlay
          playsInline
          muted
          className={photo ? 'hidden' : 'max-h-96 w-full rounded bg-black'}
        />
        {photo && (
          <img
            src={photo.preview}
            alt="Предпросмотр снимка"
            className="max-h-96 w-full rounded object-contain"
          />
        )}
        {!ready && !photo && (
          <Button
            disabled={pending}
            onClick={async () => {
              setError('')
              setPending(true)
              try {
                if (!navigator.mediaDevices?.getUserMedia)
                  throw new Error(
                    'Камера недоступна. Откройте страницу по HTTPS в браузере с поддержкой камеры.',
                  )
                const next = await navigator.mediaDevices.getUserMedia({
                  audio: false,
                  video: {
                    facingMode: { ideal: 'environment' },
                    width: { ideal: 1920 },
                    height: { ideal: 1080 },
                  },
                })
                if (!alive.current) {
                  next.getTracks().forEach((track) => track.stop())
                  return
                }
                stop()
                stream.current = next
                if (video.current) {
                  video.current.srcObject = next
                  await video.current.play()
                }
                if (alive.current) setReady(true)
              } catch {
                if (alive.current)
                  setError('Не удалось открыть камеру. Проверьте разрешение браузера и повторите.')
              } finally {
                if (alive.current) setPending(false)
              }
            }}
          >
            Включить камеру
          </Button>
        )}
        {ready && (
          <Button
            disabled={pending}
            onClick={() => {
              const source = video.current
              if (!source?.videoWidth || !source.videoHeight) {
                setError('Камера ещё не передала изображение.')
                return
              }
              const scale = Math.min(1, 1600 / source.videoWidth, 1600 / source.videoHeight)
              const canvas = document.createElement('canvas')
              canvas.width = Math.max(1, Math.round(source.videoWidth * scale))
              canvas.height = Math.max(1, Math.round(source.videoHeight * scale))
              const context = canvas.getContext('2d')
              if (!context) {
                setError('Не удалось подготовить снимок.')
                return
              }
              context.drawImage(source, 0, 0, canvas.width, canvas.height)
              canvas.toBlob(
                (blob) => {
                  if (!alive.current) return
                  if (!blob || blob.size > 8 * 1024 * 1024) {
                    setError('Не удалось подготовить снимок до 8 МБ.')
                    return
                  }
                  if (preview.current) URL.revokeObjectURL(preview.current)
                  preview.current = URL.createObjectURL(blob)
                  setPhoto({
                    file: new File([blob], 'camera.jpg', { type: 'image/jpeg' }),
                    uploadId: crypto.randomUUID(),
                    preview: preview.current,
                  })
                  stop()
                  setReady(false)
                },
                'image/jpeg',
                0.9,
              )
            }}
          >
            Сделать снимок
          </Button>
        )}
        {photo && (
          <>
            <p>Снимок готов. Перед отправкой его можно переснять.</p>
            <Button
              variant="outline"
              disabled={pending}
              onClick={() => {
                if (preview.current) URL.revokeObjectURL(preview.current)
                preview.current = ''
                setPhoto(null)
                setError('')
              }}
            >
              Переснять
            </Button>
            <Button
              disabled={pending}
              onClick={async () => {
                setPending(true)
                setError('')
                try {
                  await onUpload(photo.file, photo.uploadId)
                } catch (failure) {
                  if (alive.current) setError(failureText(failure))
                } finally {
                  if (alive.current) setPending(false)
                }
              }}
            >
              {pending ? 'Сохраняем…' : 'Сохранить снимок'}
            </Button>
          </>
        )}
        {error && <p role="alert">{error}</p>}
        <Button variant="ghost" disabled={pending && !!photo} onClick={onClose}>
          Закрыть камеру
        </Button>
      </div>
    </section>
  )
}
