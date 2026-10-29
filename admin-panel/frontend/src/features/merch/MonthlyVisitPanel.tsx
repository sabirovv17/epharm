import { useCallback, useState } from 'react'
import { Button, Field, Input, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from './usePlacementRead'
import { adminTaskApi, type TaskBranch } from './tasks-api'

type Request = <T>(path: string, init?: RequestInit) => Promise<T>
export default function MonthlyVisitPanel({
  request,
  onStarted,
}: {
  request: Request
  onStarted: (id: string) => void
}) {
  const load = useCallback(
    (signal: AbortSignal) =>
      request<TaskBranch[]>('/api/mobile/merch/tasks/visits/branches', { signal }),
    [request],
  )
  return (
    <VisitForm
      load={load}
      start={(body) =>
        request<{ dispatchId: string; resumed: boolean }>('/api/mobile/merch/tasks/visits', {
          method: 'POST',
          body: JSON.stringify(body),
        })
      }
      onStarted={onStarted}
    />
  )
}
export function AdminMonthlyVisitPanel({ onStarted }: { onStarted: (id: string) => void }) {
  const load = useCallback((signal: AbortSignal) => adminTaskApi.visitBranches(signal), [])
  return <VisitForm load={load} start={adminTaskApi.visit} onStarted={onStarted} />
}
function VisitForm({
  load,
  start,
  onStarted,
}: {
  load: (signal: AbortSignal) => Promise<TaskBranch[]>
  start: (body: {
    branchId: string
    month: string
  }) => Promise<{ dispatchId: string; resumed: boolean }>
  onStarted: (id: string) => void
}) {
  const read = usePlacementRead(load)
  const [branch, setBranch] = useState('')
  const [month, setMonth] = useState(
    (() => {
      const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Almaty',
        year: 'numeric',
        month: '2-digit',
      }).formatToParts(new Date())
      return `${parts.find((p) => p.type === 'year')!.value}-${parts.find((p) => p.type === 'month')!.value}`
    })(),
  )
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const selected = read.data?.find((item) => item.id === branch && item.active)
  return (
    <section className="card space-y-4 p-5">
      <div>
        <h2 className="text-xl font-bold">Месячный визит</h2>
        <p className="mt-2 text-sm text-ink-500">
          Фотоотчёт по подтверждённым размещениям месяца. Повторный вход продолжает ваш сохранённый
          визит. Вознаграждение не предусмотрено.
        </p>
      </div>
      {read.pending && <p role="status">Проверяем доступные аптеки…</p>}
      {!!read.error && <p role="alert">{describeError(read.error)}</p>}
      <Field label="Аптека визита">
        <Select
          ariaLabel="Аптека визита"
          value={branch}
          onChange={setBranch}
          options={[
            { value: '', label: 'Выберите свою аптеку' },
            ...(read.data ?? []).map((item) => ({
              value: item.id,
              label: `${item.name} · ${item.city} · ${item.address}`,
            })),
          ]}
        />
      </Field>
      <Field label="Месяц визита">
        <Input
          aria-label="Месяц визита"
          type="month"
          value={month}
          disabled={pending}
          onChange={(event) => setMonth(event.target.value)}
        />
      </Field>
      {error && <p role="alert">{error}</p>}
      <Button
        disabled={pending || !selected || !/^\d{4}-\d{2}$/.test(month) || !!read.error}
        onClick={async () => {
          if (!selected) return
          setPending(true)
          setError('')
          try {
            const result = await start({ branchId: selected.id, month: `${month}-01` })
            onStarted(result.dispatchId)
          } catch (failure) {
            setError(describeError(failure))
          } finally {
            setPending(false)
          }
        }}
      >
        {pending ? 'Открываем визит…' : 'Начать или продолжить визит'}
      </Button>
    </section>
  )
}
