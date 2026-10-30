import { useEffect, useRef, useState } from 'react'
import { Button, Field, Input } from '@/ui'
import { describeError } from '@/lib/describeError'
import type { TaskGateway, TaskItem } from './tasks-api'

export function TaskStockFields({
  item,
  gateway,
  editable,
  onSaved,
}: {
  item: TaskItem
  gateway: TaskGateway
  editable: boolean
  onSaved: () => void
}) {
  const policy = item.execution!
  const [counts, setCounts] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      policy.products.map((p) => [p.key, policy.stock?.counts[p.key]?.toString() ?? '']),
    ),
  )
  const [reason, setReason] = useState(policy.stock?.shortageReason ?? '')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const valid = policy.products.every(
    (p) => /^\d+$/.test(counts[p.key] ?? '') && Number(counts[p.key]) <= 100000,
  )
  const shortage = policy.products.some((p) => Number(counts[p.key]) < 5)
  const submit = async () => {
    if (
      !valid ||
      (shortage && reason.trim().length < 3) ||
      pending ||
      !item.currentAttempt ||
      !gateway.saveStock
    )
      return
    setPending(true)
    setError('')
    try {
      await gateway.saveStock(item.id, {
        expectedAttempt: item.currentAttempt.number,
        counts: Object.fromEntries(policy.products.map((p) => [p.key, Number(counts[p.key])])),
        shortageReason: reason.trim(),
      })
      onSaved()
    } catch (failure) {
      setError(describeError(failure))
    } finally {
      setPending(false)
    }
  }
  return (
    <div className="rounded-lg border border-paper-line p-3">
      <h4 className="mb-2 font-bold">Упаковки на полке</h4>
      {policy.products.map((p) => (
        <Field key={p.key} label={p.name}>
          <Input
            aria-label={`Упаковок: ${p.name}`}
            type="number"
            min={0}
            max={100000}
            step={1}
            value={counts[p.key] ?? ''}
            disabled={!editable || pending}
            onChange={(e) => setCounts((old) => ({ ...old, [p.key]: e.target.value }))}
          />
        </Field>
      ))}
      <Field
        label={shortage ? 'Причина нехватки упаковок' : 'Комментарий к остаткам'}
        optional={!shortage}
        hint={shortage ? 'Если упаковок меньше пяти, объясните причину.' : undefined}
      >
        <Input
          aria-label="Причина нехватки упаковок"
          value={reason}
          maxLength={1200}
          disabled={!editable || pending}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      {editable && gateway.saveStock && (
        <Button
          variant="outline"
          className="mt-2"
          disabled={!valid || (shortage && reason.trim().length < 3) || pending}
          onClick={() => void submit()}
        >
          {pending ? 'Сохраняем…' : 'Сохранить количество упаковок'}
        </Button>
      )}
      {policy.stock?.source === 'preserved_source' && (
        <p className="mt-2 text-xs text-ink-500">
          Исторические значения: для новой попытки потребуется повторный подсчёт.
        </p>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm">
          {error}
        </p>
      )}
    </div>
  )
}

export function InstructionDownload({ item, gateway }: { item: TaskItem; gateway: TaskGateway }) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const active = useRef<AbortController | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  return (
    <div className="rounded-lg border border-paper-line p-3">
      <p className="text-sm">
        Прочитайте PDF-инструкцию и запишите видео выполнения. Для каждой новой попытки нужно новое
        видео.
      </p>
      {gateway.instruction && (
        <Button
          variant="outline"
          className="mt-2"
          disabled={pending}
          onClick={async () => {
            setPending(true)
            setError('')
            const controller = new AbortController()
            active.current = controller
            try {
              const blob = await gateway.instruction!(item.id, controller.signal)
              if (controller.signal.aborted) return
              if (blob.type !== 'application/pdf')
                throw new Error('Сервис не вернул PDF-инструкцию')
              const url = URL.createObjectURL(blob)
              const a = document.createElement('a')
              a.href = url
              a.download = 'instruction.pdf'
              a.click()
              setTimeout(() => URL.revokeObjectURL(url), 0)
            } catch (failure) {
              if (!controller.signal.aborted) setError(describeError(failure))
            } finally {
              if (!controller.signal.aborted) setPending(false)
            }
          }}
        >
          {pending ? 'Загружаем…' : 'Скачать PDF-инструкцию'}
        </Button>
      )}
      {error && (
        <p role="alert" className="mt-2 text-sm">
          {error}
        </p>
      )}
    </div>
  )
}
