import { useCallback, useRef, useState } from 'react'
import { Button, Field } from '@/ui'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from './usePlacementRead'
import type { TaskGateway } from './tasks-api'

export default function TaskConversation({
  itemId,
  gateway,
  canWrite,
}: {
  itemId: string
  gateway: TaskGateway
  canWrite: boolean
}) {
  const [offset, setOffset] = useState(0)
  const [text, setText] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const intent = useRef<{ id: string; body: string } | null>(null)
  const load = useCallback(
    (signal: AbortSignal) => gateway.comments!(itemId, offset, signal),
    [gateway, itemId, offset],
  )
  const read = usePlacementRead(load)
  return (
    <div className="space-y-3 rounded-lg border border-paper-line p-3">
      <h4 className="font-bold">Обсуждение пункта</h4>
      {read.pending && <p role="status">Загружаем комментарии…</p>}
      {!!read.error && <p role="alert">{describeError(read.error)}</p>}
      {read.data?.items.map((item) => (
        <article className="rounded-lg bg-paper-input p-3 text-sm" key={item.id}>
          <p className="text-xs text-ink-500">
            {item.isOwn ? 'Вы' : item.authorLabel} ·{' '}
            {new Date(item.createdAt).toLocaleString('ru-RU')}
          </p>
          <p className="mt-1 whitespace-pre-wrap break-words">{item.body}</p>
        </article>
      ))}
      {!read.pending && !read.error && !read.data?.items.length && (
        <p className="text-sm text-ink-500">Комментариев пока нет.</p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          disabled={!offset || read.pending}
          onClick={() => setOffset((value) => Math.max(0, value - 20))}
        >
          Предыдущие комментарии
        </Button>
        <Button
          variant="outline"
          disabled={!read.data?.hasMore || read.pending}
          onClick={() => setOffset((value) => value + 20)}
        >
          Следующие комментарии
        </Button>
      </div>
      {canWrite && gateway.comment && (
        <form
          className="space-y-2"
          onSubmit={async (event) => {
            event.preventDefault()
            const body = text.trim()
            if (!body || pending) return
            if (intent.current?.body !== body) intent.current = { id: crypto.randomUUID(), body }
            setPending(true)
            setError('')
            try {
              await gateway.comment!(itemId, intent.current.id, body)
              setText('')
              intent.current = null
              setOffset(0)
              read.reload()
            } catch (failure) {
              setError(describeError(failure))
            } finally {
              setPending(false)
            }
          }}
        >
          <Field label="Новый комментарий">
            <textarea
              aria-label="Новый комментарий"
              className="inp min-h-20 w-full"
              maxLength={1200}
              required
              disabled={pending || !!read.error}
              value={text}
              onChange={(event) => setText(event.target.value)}
            />
          </Field>
          <Button type="submit" disabled={pending || !text.trim() || !!read.error}>
            Отправить комментарий
          </Button>
          {error && <p role="alert">{error}</p>}
        </form>
      )}
    </div>
  )
}
