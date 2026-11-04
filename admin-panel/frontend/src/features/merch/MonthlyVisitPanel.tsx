import { useCallback, useState } from 'react'
import { Button, Field, Input, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from './usePlacementRead'
import { adminTaskApi, type TaskBranch } from './tasks-api'
import { useLearnerT } from '@/features/learner/learner-i18n'

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
  const t = useLearnerT()
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
  const activeBranches = read.data?.filter((item) => item.active) ?? []
  return (
    <section className="card space-y-5 p-4 sm:p-6">
      <div>
        <h2 className="text-xl font-bold">{t('Месячный визит')}</h2>
        <p className="mt-2 text-sm text-ink-500">
          {t(
            'Фотоотчёт по подтверждённым размещениям месяца. Повторный вход продолжает ваш сохранённый визит. Вознаграждение не предусмотрено.',
          )}
        </p>
      </div>
      {read.pending && <p role="status">{t('Проверяем доступные аптеки…')}</p>}
      {!!read.error && (
        <div role="alert" className="space-y-3 rounded-xl bg-surface-danger p-4 text-sm">
          <p>
            {t('Не удалось загрузить аптеки.')} {describeError(read.error)}
          </p>
          <Button variant="outline" onClick={read.reload}>
            {t('Повторить загрузку аптек')}
          </Button>
        </div>
      )}
      {read.data && activeBranches.length === 0 && (
        <div role="status" className="rounded-xl bg-paper-input p-4 text-sm text-ink-500">
          <p className="font-semibold text-ink-700">
            {t('Нет доступных аптек для месячного визита.')}
          </p>
          <p className="mt-1">
            {t(
              'Для визита нужна активная аптека с проверенной привязкой к вашему профилю. Если аптека уже указана в профиле, попросите ответственного за мерчендайзинг проверить её привязку.',
            )}
          </p>
        </div>
      )}
      <Field label={t('Аптека визита')}>
        <Select
          ariaLabel={t('Аптека визита')}
          value={branch}
          disabled={pending || read.pending || !!read.error || activeBranches.length === 0}
          onChange={setBranch}
          options={[
            { value: '', label: t('Выберите свою аптеку') },
            ...activeBranches.map((item) => ({
              value: item.id,
              label: `${item.name} · ${item.city} · ${item.address}`,
            })),
          ]}
        />
      </Field>
      <Field label={t('Месяц визита')} hint={t('Период по времени Алматы (Asia/Almaty).')}>
        <Input
          aria-label={t('Месяц визита')}
          type="month"
          value={month}
          disabled={pending}
          onChange={(event) => setMonth(event.target.value)}
        />
      </Field>
      {error && <p role="alert">{error}</p>}
      <Button
        className="w-full sm:w-auto"
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
        {pending ? t('Открываем визит…') : t('Начать или продолжить визит')}
      </Button>
    </section>
  )
}
