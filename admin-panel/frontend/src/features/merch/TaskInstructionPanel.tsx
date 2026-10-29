import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import type { Instruction, InstructionApi, InstructionTarget } from './task-instructions-api'
import { usePlacementRead } from './usePlacementRead'

const labels = { pending: 'На согласовании', approved: 'Подтверждён', rejected: 'Отклонён' }
export default function TaskInstructionPanel({ api }: { api: InstructionApi }) {
  const [offset, setOffset] = useState(0)
  const [actionError, setError] = useState('')
  const load = useCallback((signal: AbortSignal) => api.list(offset, signal), [api, offset])
  const query = usePlacementRead(load)
  const error = actionError || (query.error ? describeError(query.error) : '')
  const items = error ? [] : (query.data?.items ?? [])
  const total = error ? 0 : (query.data?.total ?? 0)
  const loading = query.pending
  const [busy, setBusy] = useState(false)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [review, setReview] = useState<Instruction | null>(null)
  const [decision, setDecision] = useState<'approved' | 'rejected'>('approved')
  const [reason, setReason] = useState('')
  const [results, setResults] = useState('')
  const epoch = useRef(0)
  const downloads = useRef(new Set<AbortController>())
  useEffect(() => {
    const activeDownloads = downloads.current
    epoch.current += 1
    return () => {
      epoch.current += 1
      activeDownloads.forEach((controller) => controller.abort())
      activeDownloads.clear()
    }
  }, [api])
  async function download(work: (signal: AbortSignal) => Promise<Blob>, filename: string) {
    const controller = new AbortController()
    const version = epoch.current
    downloads.current.add(controller)
    try {
      const blob = await work(controller.signal)
      if (version !== epoch.current || controller.signal.aborted) return
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filename
      anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (failure) {
      if (!controller.signal.aborted && version === epoch.current) setError(describeError(failure))
    } finally {
      downloads.current.delete(controller)
    }
  }
  const activeResult = items.find((item) => item.id === results && item.status === 'approved')
  return (
    <section className="card space-y-4 p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">PDF-инструктаж и видеоотчёты</h2>
          <p className="mt-1 text-sm text-ink-500">
            После согласования PDF создаются задания без бонуса. Видео становится доступно
            производителю после приёмки.
          </p>
        </div>
        <Button
          variant="outline"
          disabled={loading || busy || !!query.error}
          onClick={() => setUploadOpen(true)}
        >
          Добавить PDF-инструктаж
        </Button>
      </div>
      {loading && <p role="status">Загружаем материалы…</p>}
      {error && (
        <div role="alert" className="rounded-xl bg-surface-danger p-3">
          <p>{error}</p>
          <Button
            variant="outline"
            onClick={() => {
              setReview(null)
              setResults('')
              setError('')
              query.reload()
            }}
          >
            Обновить материалы
          </Button>
        </div>
      )}
      {!loading && !error && !items.length && (
        <p className="py-3 text-sm text-ink-500">Материалы пока не добавлены.</p>
      )}
      {!loading && (
        <div className="space-y-3">
          {items.map((item) => (
            <article key={item.id} className="rounded-xl border border-ink-100 p-4">
              <div className="flex flex-wrap justify-between gap-2">
                <strong>{item.title}</strong>
                <span className="rounded-md bg-paper-input px-2 py-1 text-xs">
                  {labels[item.status]}
                </span>
              </div>
              <p className="mt-2 whitespace-pre-wrap text-sm text-ink-600">{item.instructions}</p>
              <p className="mt-2 text-xs text-ink-500">
                Срок: {new Date(item.dueAt).toLocaleString('ru-RU')} · Завершено заданий:{' '}
                {item.completed} из {item.totalTasks}
              </p>
              {item.reviewNote && (
                <p className="mt-2 rounded-lg bg-paper-input p-2 text-sm">
                  Решение: {item.reviewNote}
                </p>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                <Button
                  variant="outline"
                  onClick={() =>
                    void download((signal) => api.pdf(item.id, signal), `${item.title}.pdf`)
                  }
                >
                  Скачать PDF
                </Button>
                {item.status === 'approved' && (
                  <Button variant="outline" onClick={() => setResults(item.id)}>
                    Подтверждённые видео
                  </Button>
                )}
                {api.realm === 'admin' && item.canReview && (
                  <Button
                    disabled={busy}
                    onClick={() => {
                      setReview(item)
                      setReason('')
                      setDecision('approved')
                    }}
                  >
                    Проверить материал
                  </Button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="outline"
          disabled={loading || offset === 0}
          onClick={() => setOffset((value) => Math.max(0, value - 20))}
        >
          Предыдущие материалы
        </Button>
        <Button
          variant="outline"
          disabled={loading || offset + 20 >= total}
          onClick={() => setOffset((value) => value + 20)}
        >
          Следующие материалы
        </Button>
        <span className="text-xs text-ink-500">Всего: {total}</span>
      </div>
      {uploadOpen && (
        <UploadInstruction
          api={api}
          onClose={() => setUploadOpen(false)}
          onSaved={() => {
            setUploadOpen(false)
            setError('')
            query.reload()
          }}
        />
      )}
      {review && (
        <Modal
          open
          onClose={() => {
            if (!busy) setReview(null)
          }}
          title="Проверка PDF-инструктажа"
          subtitle={review.title}
        >
          <form
            className="space-y-4"
            onSubmit={async (event) => {
              event.preventDefault()
              const current = review
              setBusy(true)
              setError('')
              try {
                await api.review(current.id, current.revision, decision, reason)
                setReview(null)
                query.reload()
              } catch (failure) {
                setReview(null)
                setError(describeError(failure))
              } finally {
                setBusy(false)
              }
            }}
          >
            <Field label="Решение по PDF">
              <select
                aria-label="Решение по PDF"
                className="inp"
                value={decision}
                onChange={(event) => setDecision(event.target.value as 'approved' | 'rejected')}
              >
                <option value="approved">Подтвердить и создать задания</option>
                <option value="rejected">Отклонить материал</option>
              </select>
            </Field>
            <Field label="Основание решения">
              <textarea
                aria-label="Основание решения"
                className="inp min-h-24 w-full"
                value={reason}
                required
                minLength={3}
                maxLength={1200}
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
            <p className="text-sm text-ink-500">
              Подтверждение охватывает все аптеки связанных размещений. По одной аптеке создаётся
              одно задание с видео, без начисления бонуса.
            </p>
            <Button type="submit" disabled={busy || reason.trim().length < 3}>
              Сохранить решение
            </Button>
          </form>
        </Modal>
      )}
      {activeResult && (
        <Results
          api={api}
          material={activeResult}
          onClose={() => setResults('')}
          download={download}
        />
      )}
    </section>
  )
}
function UploadInstruction({
  api,
  onClose,
  onSaved,
}: {
  api: InstructionApi
  onClose: () => void
  onSaved: () => void
}) {
  const [selected, setSelected] = useState<InstructionTarget[]>([])
  const [offset, setOffset] = useState(0)
  const [title, setTitle] = useState('')
  const [instructions, setInstructions] = useState('')
  const [dueAt, setDueAt] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [actionError, setError] = useState('')
  const load = useCallback((signal: AbortSignal) => api.targets(offset, signal), [api, offset])
  const query = usePlacementRead(load)
  const targets = query.data?.items ?? []
  const total = query.data?.total ?? 0
  const loading = query.pending
  const error = actionError || (query.error ? describeError(query.error) : '')
  const [busy, setBusy] = useState(false)
  const intent = useRef({ id: crypto.randomUUID(), attempted: false })
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])
  function changed() {
    if (intent.current.attempted) intent.current = { id: crypto.randomUUID(), attempted: false }
  }
  return (
    <Modal
      open
      onClose={() => {
        if (!busy) onClose()
      }}
      title="Новый PDF-инструктаж"
      subtitle="Выберите медиапланы одного производителя. Материал сначала попадёт на согласование."
      width={800}
    >
      <form
        className="space-y-4"
        onSubmit={async (event) => {
          event.preventDefault()
          if (!file || !selected.length) return
          if (file.size > 20 * 1024 * 1024) {
            setError('Уменьшите PDF до 20 МБ')
            return
          }
          const date = new Date(dueAt)
          if (!(date.getTime() > Date.now())) {
            setError('Укажите будущий срок выполнения')
            return
          }
          const controller = new AbortController()
          request.current = controller
          intent.current.attempted = true
          setBusy(true)
          setError('')
          try {
            await api.upload(
              {
                id: intent.current.id,
                ...(api.realm === 'admin' ? { producerId: selected[0].producerId } : {}),
                planIds: selected.map((item) => item.id),
                title,
                instructions,
                dueAt: date.toISOString(),
              },
              file,
              controller.signal,
            )
            if (!controller.signal.aborted) onSaved()
          } catch (failure) {
            if (!controller.signal.aborted) setError(describeError(failure))
          } finally {
            if (!controller.signal.aborted) setBusy(false)
          }
        }}
      >
        {error && (
          <p role="alert" className="rounded-lg bg-surface-danger p-3">
            {error}
          </p>
        )}
        <div>
          <h3 className="font-bold">Медиапланы для инструктажа</h3>
          {loading && <p role="status">Проверяем разрешённые планы…</p>}
          <div className="my-3 space-y-2">
            {targets.map((target) => (
              <label
                key={target.id}
                className="flex gap-2 rounded-lg border border-ink-100 p-3 text-sm"
              >
                <input
                  type="checkbox"
                  checked={selected.some((item) => item.id === target.id)}
                  disabled={
                    busy ||
                    !target.canUpload ||
                    (selected.length > 0 && selected[0].producerId !== target.producerId) ||
                    (selected.length >= 50 && !selected.some((item) => item.id === target.id))
                  }
                  onChange={(event) => {
                    changed()
                    setSelected(
                      event.target.checked
                        ? [...selected, target]
                        : selected.filter((item) => item.id !== target.id),
                    )
                  }}
                />
                {target.label}
              </label>
            ))}
          </div>
          {!loading && !targets.length && (
            <p className="text-sm text-ink-500">
              Нет медиапланов, для которых вам разрешена загрузка.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={loading || busy || offset === 0}
              onClick={() => setOffset((value) => Math.max(0, value - 50))}
            >
              Предыдущие планы
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={loading || busy || offset + 50 >= total}
              onClick={() => setOffset((value) => value + 50)}
            >
              Следующие планы
            </Button>
          </div>
          <p className="mt-2 text-xs text-ink-500">
            Выбрано: {selected.length}. Всего разрешено: {total}.
          </p>
        </div>
        <Field label="Название инструктажа">
          <Input
            aria-label="Название инструктажа"
            value={title}
            minLength={2}
            maxLength={160}
            required
            disabled={busy}
            onChange={(event) => {
              changed()
              setTitle(event.target.value)
            }}
          />
        </Field>
        <Field label="Задание для видео">
          <textarea
            aria-label="Задание для видео"
            className="inp min-h-24 w-full"
            value={instructions}
            minLength={3}
            maxLength={4000}
            required
            disabled={busy}
            onChange={(event) => {
              changed()
              setInstructions(event.target.value)
            }}
          />
        </Field>
        <Field label="Срок выполнения (местное время)">
          <Input
            aria-label="Срок выполнения (местное время)"
            type="datetime-local"
            value={dueAt}
            required
            disabled={busy}
            onChange={(event) => {
              changed()
              setDueAt(event.target.value)
            }}
          />
        </Field>
        <Field label="PDF до 20 МБ">
          <input
            aria-label="PDF до 20 МБ"
            type="file"
            accept="application/pdf,.pdf"
            required
            disabled={busy}
            onChange={(event) => {
              changed()
              setFile(event.target.files?.[0] || null)
            }}
          />
        </Field>
        <Button
          type="submit"
          disabled={busy || loading || !!query.error || !selected.length || !file}
        >
          Отправить PDF на согласование
        </Button>
      </form>
    </Modal>
  )
}
function Results({
  api,
  material,
  onClose,
  download,
}: {
  api: InstructionApi
  material: Instruction
  onClose: () => void
  download: (work: (signal: AbortSignal) => Promise<Blob>, filename: string) => Promise<void>
}) {
  const [offset, setOffset] = useState(0)
  const load = useCallback(
    (signal: AbortSignal) => api.results(material.id, offset, signal),
    [api, material.id, offset],
  )
  const query = usePlacementRead(load)
  const page = query.data
  const error = query.error ? describeError(query.error) : ''
  return (
    <Modal
      open
      onClose={onClose}
      title="Принятые видеоотчёты"
      subtitle={material.title}
      width={800}
    >
      {error && <p role="alert">{error}</p>}
      {!page && !error && <p role="status">Загружаем подтверждённые результаты…</p>}
      {page && (
        <>
          <div className="space-y-3">
            {page.items.map((result) => (
              <div
                key={result.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-ink-100 p-3"
              >
                <div>
                  <strong>{result.branchName}</strong>
                  <p className="text-sm text-ink-500">
                    {result.itemTitle} · Принято{' '}
                    {new Date(result.approvedAt).toLocaleString('ru-RU')}
                  </p>
                </div>
                <Button
                  variant="outline"
                  onClick={() =>
                    void download(
                      (signal) => api.video(material.id, result.id, signal),
                      `instruction-result.${result.mimeType === 'video/webm' ? 'webm' : result.mimeType === 'video/quicktime' ? 'mov' : 'mp4'}`,
                    )
                  }
                >
                  Скачать видео
                </Button>
              </div>
            ))}
            {!page.items.length && (
              <p className="text-sm text-ink-500">
                Принятых видеоотчётов пока нет. Черновики и возвращённые попытки здесь не
                показываются.
              </p>
            )}
          </div>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={offset === 0}
              onClick={() => setOffset((value) => Math.max(0, value - 20))}
            >
              Предыдущие результаты
            </Button>
            <Button
              variant="outline"
              disabled={offset + 20 >= page.total}
              onClick={() => setOffset((value) => value + 20)}
            >
              Следующие результаты
            </Button>
          </div>
        </>
      )}
    </Modal>
  )
}
