import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '@/ui'
import { usePlacementRead } from './usePlacementRead'
import type { ReferencePhotoApi } from './placement-reference-api'

export default function PlacementReferencePanel({
  planId,
  itemId,
  api,
  onChanged,
}: {
  planId: string
  itemId: string
  api: ReferencePhotoApi
  onChanged: () => void
}) {
  const read = usePlacementRead(
    useCallback((signal) => api.view(planId, itemId, signal), [api, planId, itemId]),
  )
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [denied, setDenied] = useState(false)
  const [url, setUrl] = useState('')
  const controller = useRef<AbortController | null>(null)
  const intent = useRef<{ file: File; id: string } | null>(null)
  const objectUrl = useRef('')
  const clearImage = () => {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current)
    objectUrl.current = ''
    setUrl('')
  }
  useEffect(
    () => () => {
      controller.current?.abort()
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current)
    },
    [],
  )
  const act = async (work: (signal: AbortSignal) => Promise<void>) => {
    if (busy) return
    setBusy(true)
    setError('')
    controller.current?.abort()
    const next = new AbortController()
    controller.current = next
    try {
      await work(next.signal)
    } catch (e) {
      if (!next.signal.aborted) {
        clearImage()
        const body = e as {
          status?: number
          response?: { status?: number; data?: { message?: string } }
          message?: string
        }
        const status = body?.status ?? body?.response?.status
        if (status === 401 || status === 403) setDenied(true)
        setError(
          status === 413
            ? 'Уменьшите фото до 8 МБ и 12 мегапикселей.'
            : status === 429
              ? 'Другая фотография обрабатывается. Повторите позже.'
              : status === 409
                ? 'Размещение изменилось. Обновите сведения перед повторной загрузкой.'
                : status === 401 || status === 403
                  ? 'Действующий доступ к фотографии отсутствует.'
                  : (body?.response?.data?.message ??
                    body?.message ??
                    'Не удалось сохранить фотографию.'),
        )
      }
    } finally {
      if (!next.signal.aborted) setBusy(false)
    }
  }
  const state = read.data
  return (
    <div className="space-y-3 rounded-lg border border-paper-line p-3">
      <p className="font-bold">Фото заказчика — эталон размещения</p>
      <p className="text-xs text-ink-500">
        Приватное JPG/PNG/WebP до 8 МБ и 12 мегапикселей. Исходный файл сохраняется без
        перекодирования. Заменить или убрать можно только ожидающую утверждения позицию. Старые
        версии сохраняются.
      </p>
      {read.pending && <p role="status">Загружаем сведения о фотографии…</p>}
      {(!!error || !!read.error) && (
        <p role="alert">{error || 'Фотография недоступна. Проверьте действующий доступ.'}</p>
      )}
      {(!!error || !!read.error) && (
        <Button
          variant="outline"
          disabled={busy}
          onClick={() => {
            clearImage()
            setFile(null)
            intent.current = null
            setError('')
            setDenied(false)
            read.reload()
          }}
        >
          Обновить сведения о фото
        </Button>
      )}
      {state && !read.error && !denied && (
        <>
          <p>
            {state.photo
              ? `Текущая фотография: ${state.photo.fileName}`
              : 'Фото заказчика не добавлено.'}
          </p>
          {url && (
            <a
              href={url}
              target="_blank"
              rel="noreferrer"
              download={state.photo?.fileName ?? 'reference-photo'}
            >
              <img
                src={url}
                alt="Приватное фото заказчика"
                className="max-h-64 max-w-full object-contain"
              />
            </a>
          )}
          {state.photo && (
            <Button
              variant="outline"
              disabled={busy}
              onClick={() =>
                void act(async (signal) => {
                  const blob = await api.file(planId, itemId, state.photo!.id, signal)
                  if (!signal.aborted) {
                    clearImage()
                    objectUrl.current = URL.createObjectURL(blob)
                    setUrl(objectUrl.current)
                  }
                })
              }
            >
              Открыть фотографию
            </Button>
          )}
          {state.canManage && (
            <div className="flex flex-wrap items-center gap-2">
              <input
                key={state.revision}
                aria-label="Фото заказчика JPG/PNG/WebP"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                disabled={busy}
                onChange={(event) => {
                  const next = event.target.files?.[0] ?? null
                  setFile(next)
                  intent.current = null
                  setError(next && next.size > 8 * 1024 * 1024 ? 'Уменьшите файл до 8 МБ.' : '')
                }}
              />
              <Button
                disabled={busy || !file || file.size > 8 * 1024 * 1024}
                onClick={() =>
                  void act(async (signal) => {
                    if (!file) return
                    if (intent.current?.file !== file)
                      intent.current = { file, id: crypto.randomUUID() }
                    await api.upload(
                      planId,
                      itemId,
                      state.revision,
                      intent.current.id,
                      file,
                      signal,
                    )
                    if (!signal.aborted) {
                      setFile(null)
                      intent.current = null
                      clearImage()
                      read.reload()
                      onChanged()
                    }
                  })
                }
              >
                {state.photo ? 'Заменить фотографию' : 'Загрузить фотографию'}
              </Button>
              {state.photo && (
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    void act(async (signal) => {
                      await api.archive(planId, itemId, state.revision, state.photo!.id)
                      if (!signal.aborted) {
                        clearImage()
                        read.reload()
                        onChanged()
                      }
                    })
                  }
                >
                  Убрать текущую фотографию
                </Button>
              )}
            </div>
          )}
          {!state.canManage && (
            <p className="text-xs">Изменение фотографии для этой позиции недоступно.</p>
          )}
          <details>
            <summary>Сохранённые версии: {state.history.length}</summary>
            {state.history.map((photo) => (
              <div key={photo.id} className="flex flex-wrap items-center gap-2 py-1">
                <span>
                  {photo.fileName} · {photo.archivedAt ? 'В архиве' : 'Текущая'}
                </span>
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    void act(async (signal) => {
                      const blob = await api.file(planId, itemId, photo.id, signal)
                      if (!signal.aborted) {
                        clearImage()
                        objectUrl.current = URL.createObjectURL(blob)
                        setUrl(objectUrl.current)
                      }
                    })
                  }
                >
                  Посмотреть версию
                </Button>
              </div>
            ))}
          </details>
        </>
      )}
    </div>
  )
}
