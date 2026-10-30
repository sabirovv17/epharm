import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Button, Field, Input } from '@/ui'
import { Logo } from '@/layout/Logo'
import { clearStaffEntry, staffEntry } from './staffEntryNavigation'
import { staffClient, type StaffClient, type StaffScope } from './staff-access-api'
import { usePlacementRead } from './usePlacementRead'
import StaffVisitReport from './StaffVisitReport'

const message = (failure: unknown) =>
  failure instanceof Error ? failure.message : 'Кабинет временно недоступен.'
const statusLabels: Record<string, string> = {
  draft: 'Черновик',
  assigned: 'Новый отчёт',
  in_progress: 'В работе',
  submitted: 'На проверке',
  pending: 'На проверке',
  completed: 'Принят',
  approved: 'Принят',
  rejected: 'На доработке',
  revision_required: 'На доработке',
  cancelled: 'Закрыт',
}
export default function StaffAccessPage() {
  const [intent, setIntent] = useState(staffEntry)
  const [scope, setScope] = useState<StaffScope | null>(null)
  const [pending, setPending] = useState(true)
  const [error, setError] = useState('')
  const [code, setCode] = useState('')
  const loginRequest = useRef<AbortController | null>(null)
  const client = useMemo(
    () =>
      staffClient(() => {
        setScope(null)
        setError('Сеанс завершён или отозван. Получите новый код либо QR.')
      }, setScope),
    [],
  )
  useEffect(() => {
    const controller = new AbortController()
    const timer = window.setTimeout(async () => {
      try {
        if (intent.kind === 'invalid' || intent.kind === 'expired_claim') return
        if (intent.kind === 'access') {
          client.clear()
          await client.qr(intent.access, controller.signal)
          clearStaffEntry()
          if (!controller.signal.aborted) setIntent({ kind: 'plain' })
        } else if (client.hasSession()) {
          const next = await client.me(controller.signal)
          if (!controller.signal.aborted) setScope(next)
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          client.clear()
          setScope(null)
          setError(message(failure))
          clearStaffEntry()
          if (intent.kind === 'access') setIntent({ kind: 'plain' })
        }
      } finally {
        if (!controller.signal.aborted) setPending(false)
      }
    }, 0)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [client, intent])
  const sessionId = scope?.sessionId
  const sessionExpiresAt = scope?.sessionExpiresAt
  useEffect(
    () => () => {
      loginRequest.current?.abort()
      clearStaffEntry()
    },
    [],
  )
  useEffect(() => {
    if (!sessionId || !sessionExpiresAt) return
    let timer: number
    const expire = () => {
      const remaining = Date.parse(sessionExpiresAt) - Date.now()
      if (remaining > 0) {
        timer = window.setTimeout(expire, Math.min(remaining, 2_147_483_647))
        return
      }
      client.clear()
      setScope(null)
      setError('Срок сеанса истёк. Получите новый код либо QR.')
    }
    expire()
    return () => window.clearTimeout(timer)
  }, [client, sessionId, sessionExpiresAt])
  useEffect(() => {
    if (!sessionId) return
    const controller = new AbortController()
    const refresh = () => {
      if (document.visibilityState === 'hidden') return
      void client
        .me(controller.signal)
        .then((next) => {
          if (!controller.signal.aborted) setScope(next)
        })
        .catch(() => {
          /* The client closes revoked/expired sessions; transient errors retain the form. */
        })
    }
    const timer = window.setInterval(refresh, 60_000)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      controller.abort()
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [client, sessionId])
  async function login(event: FormEvent) {
    event.preventDefault()
    setPending(true)
    setError('')
    loginRequest.current?.abort()
    const controller = new AbortController()
    loginRequest.current = controller
    try {
      const next = await client.code(code.replace(/\D/g, ''), controller.signal)
      if (!controller.signal.aborted) {
        setScope(next)
        setCode('')
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(message(failure))
        setCode('')
      }
    } finally {
      if (!controller.signal.aborted) setPending(false)
    }
  }
  return (
    <div className="min-h-screen bg-paper">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-paper-line bg-white px-5 py-4">
        <div className="flex items-center gap-3">
          <Logo size={28} />
          <strong>ePharm · Фотоотчёты аптек</strong>
        </div>
        {scope && (
          <Button
            variant="outline"
            onClick={() => {
              void client.logout().catch(() => undefined)
              client.clear()
              setScope(null)
              setCode('')
              setError('')
            }}
          >
            Выйти
          </Button>
        )}
      </header>
      <main className="mx-auto max-w-5xl space-y-4 p-4 sm:p-6">
        {intent.kind === 'invalid' || intent.kind === 'expired_claim' ? (
          <section className="card space-y-4 p-6">
            <h1 className="text-xl font-bold">
              {intent.kind === 'expired_claim'
                ? 'Подтверждение задания устарело'
                : 'Ссылка недействительна'}
            </h1>
            <p>Повторно откройте QR-код задания. Неотправленные действия не подтверждены.</p>
            <Button
              variant="outline"
              onClick={() => {
                clearStaffEntry()
                setIntent({ kind: 'plain' })
              }}
            >
              Вход по коду сотрудника
            </Button>
          </section>
        ) : pending && !scope ? (
          <p role="status">Проверяем доступ…</p>
        ) : scope ? (
          <StaffWorkspaceScreen
            key={`${scope.sessionId}:${scope.readOnly}:${scope.branchIds.join(',')}`}
            client={client}
            scope={scope}
          />
        ) : (
          <form
            onSubmit={(event) => void login(event)}
            className="card mx-auto max-w-md space-y-4 p-6"
          >
            <h1 className="text-xl font-bold">Временный доступ сотрудника</h1>
            <p className="text-sm text-ink-500">
              Введите разовый код для фотоотчётов выбранных аптек и месяца. Этот доступ не
              подтверждает личность исполнителя.
            </p>
            <Field label="Код доступа">
              <Input
                aria-label="Код доступа"
                inputMode="numeric"
                autoComplete="one-time-code"
                value={code}
                maxLength={7}
                placeholder="000-000"
                onChange={(event) => {
                  const digits = event.target.value.replace(/\D/g, '').slice(0, 6)
                  setCode(digits.length > 3 ? `${digits.slice(0, 3)}-${digits.slice(3)}` : digits)
                }}
              />
            </Field>
            {error && <p role="alert">{error}</p>}
            <Button type="submit" disabled={pending || code.replace(/\D/g, '').length !== 6}>
              {pending ? 'Проверяем…' : 'Войти по коду'}
            </Button>
            <p className="text-sm">
              <Link className="underline" to="/learn">
                Личный кабинет фармацевта
              </Link>{' '}
              открывается отдельно.
            </p>
          </form>
        )}
      </main>
    </div>
  )
}

function StaffWorkspaceScreen({ client, scope }: { client: StaffClient; scope: StaffScope }) {
  const [offset, setOffset] = useState(0)
  const [search, setSearch] = useState('')
  const [startBranch, setStartBranch] = useState<string | null>(null)
  const [employeeName, setEmployeeName] = useState('')
  const [selected, setSelected] = useState<{ id: string; employeeName?: string } | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const load = useCallback(
    (signal: AbortSignal) => client.workspace(scope.placementMonth, offset, signal),
    [client, scope.placementMonth, offset],
  )
  const read = usePlacementRead(load)
  if (selected)
    return (
      <StaffVisitReport
        client={client}
        id={selected.id}
        initialEmployeeName={selected.employeeName}
        readOnly={scope.readOnly}
        onBack={() => {
          setSelected(null)
          setEmployeeName('')
          read.reload()
        }}
      />
    )
  const branches = (read.data?.branches ?? []).filter((branch) =>
    `${branch.name} ${branch.code} ${branch.city} ${branch.address}`
      .toLocaleLowerCase('ru-RU')
      .includes(search.trim().toLocaleLowerCase('ru-RU')),
  )
  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-2xl font-bold">{read.data?.organization.name ?? 'Фотоотчёты аптек'}</h1>
        <p className="mt-2 text-sm">
          Месяц доступа: {scope.placementMonth.slice(0, 7)}. Месяц и аптеки закреплены выданным
          доступом.
        </p>
      </header>
      {(scope.readOnly || read.data?.readOnly) && (
        <p role="status" className="card p-4">
          Срок сдачи истёк. Доступен просмотр сохранённых отчётов; новые снимки и отправка закрыты.
        </p>
      )}
      {scope.deadlineAt && (
        <p className="text-sm">
          Сдать до {new Date(scope.deadlineAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })}{' '}
          (Алматы).
        </p>
      )}
      <Field label="Найти аптеку на этой странице">
        <Input value={search} onChange={(event) => setSearch(event.target.value)} />
      </Field>
      {read.pending && <p role="status">Загружаем аптеки…</p>}
      {!!read.error && <p role="alert">{message(read.error)}</p>}
      {error && <p role="alert">{error}</p>}
      {branches.map((branch) => (
        <article className="card space-y-3 p-4" key={branch.id}>
          <div>
            <h2 className="font-bold">{branch.name}</h2>
            <p className="text-sm text-ink-500">
              {branch.city} · {branch.address}
            </p>
          </div>
          <p>
            {statusLabels[branch.status ?? 'assigned'] ?? branch.status} · {branch.photoCount} /{' '}
            {branch.requiredCount} снимков
          </p>
          {branch.reason && <p>Комментарий проверки: {branch.reason}</p>}
          <div className="flex flex-wrap gap-2">
            {branch.visitId && (
              <Button
                variant="outline"
                onClick={() => {
                  setSelected({ id: branch.visitId! })
                  setError('')
                }}
              >
                Открыть отчёт
              </Button>
            )}
            {branch.canStart && !scope.readOnly && !read.data?.readOnly && (
              <Button
                onClick={() => {
                  setStartBranch(branch.id)
                  setEmployeeName('')
                  setError('')
                }}
              >
                {branch.visitId ? 'Продолжить фотоотчёт' : 'Начать фотоотчёт'}
              </Button>
            )}
          </div>
          {startBranch === branch.id && (
            <form
              className="space-y-3 border-t border-paper-line pt-3"
              onSubmit={(event) => {
                event.preventDefault()
                if (pending) return
                setPending(true)
                setError('')
                void client
                  .open(branch.id, scope.placementMonth, employeeName.trim())
                  .then((visit) => {
                    setSelected({ id: visit.id, employeeName: employeeName.trim() })
                    setStartBranch(null)
                  })
                  .catch((failure) => setError(message(failure)))
                  .finally(() => setPending(false))
              }}
            >
              <Field label="Имя автора фотоотчёта">
                <Input
                  value={employeeName}
                  maxLength={120}
                  onChange={(event) => setEmployeeName(event.target.value)}
                />
              </Field>
              <p className="text-xs text-ink-500">
                Имя — подпись отчёта, а не подтверждённая личность.
              </p>
              <Button type="submit" disabled={pending || employeeName.trim().length < 2}>
                {pending ? 'Открываем…' : 'Открыть и продолжить'}
              </Button>
            </form>
          )}
        </article>
      ))}
      {!read.pending && !read.error && !branches.length && (
        <p>На этой странице нет подходящих аптек или фотоотчётов.</p>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="outline"
          disabled={offset === 0 || read.pending}
          onClick={() => setOffset((value) => Math.max(0, value - 50))}
        >
          Назад
        </Button>
        <span>Страница {Math.floor(offset / 50) + 1}</span>
        <Button
          variant="outline"
          disabled={!read.data?.hasMore || read.pending}
          onClick={() => setOffset((value) => value + 50)}
        >
          Далее
        </Button>
        <Button variant="ghost" onClick={read.reload}>
          Обновить
        </Button>
      </div>
    </section>
  )
}
