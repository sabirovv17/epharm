import { useEffect, useRef, useState } from 'react'
import { Button } from '@/ui'
import { describeError } from '@/lib/describeError'
import type { TaskGateway, TaskItem } from './tasks-api'

export default function TaskReference({ item, gateway }: { item: TaskItem; gateway: TaskGateway }) {
  const [url, setUrl] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const current = useRef<{ controller: AbortController | null; url: string }>({
    controller: null,
    url: '',
  })
  useEffect(() => {
    const active = current.current
    return () => {
      active.controller?.abort()
      if (active.url) URL.revokeObjectURL(active.url)
    }
  }, [])
  if (!item.execution?.clientReferencePhotoId || !gateway.reference) return null
  return (
    <div className="space-y-3 rounded-lg border border-paper-line p-3">
      <p className="text-sm">
        Эталон производителя, зафиксированный для этого пункта. Замена эталона не меняет уже
        выданное задание.
      </p>
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
            const blob = await gateway.reference!(item.id, controller.signal)
            if (controller.signal.aborted) return
            if (!['image/jpeg', 'image/png', 'image/webp'].includes(blob.type))
              throw new Error('Сервис не вернул фотографию эталона')
            if (current.current.url) URL.revokeObjectURL(current.current.url)
            const next = URL.createObjectURL(blob)
            current.current.url = next
            setUrl(next)
          } catch (failure) {
            if (!controller.signal.aborted) setError(describeError(failure))
          } finally {
            if (!controller.signal.aborted) setPending(false)
          }
        }}
      >
        {pending ? 'Загружаем эталон…' : 'Посмотреть эталон'}
      </Button>
      {url && (
        <img
          src={url}
          alt={`Эталон для ${item.title}`}
          className="max-h-96 max-w-full rounded-lg object-contain"
        />
      )}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
