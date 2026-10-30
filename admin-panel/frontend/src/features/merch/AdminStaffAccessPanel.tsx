import { useCallback, useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { Button, Field, Input, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from './usePlacementRead'
import { adminStaffAccessApi, type StaffAccessCode } from './staff-access-api'

type AdminApi = typeof adminStaffAccessApi
export default function AdminStaffAccessPanel({
  enabled,
  principalKey,
  api = adminStaffAccessApi,
}: {
  enabled: boolean
  principalKey: string
  api?: AdminApi
}) {
  return enabled && principalKey ? <AccessPanel key={principalKey} api={api} /> : null
}
function currentMonth() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Almaty',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date())
  return `${parts.find((p) => p.type === 'year')!.value}-${parts.find((p) => p.type === 'month')!.value}`
}
function AccessPanel({ api }: { api: AdminApi }) {
  const optionsLoad = useCallback((signal: AbortSignal) => api.options(signal), [api])
  const options = usePlacementRead(optionsLoad)
  const [organization, setOrganization] = useState('')
  const [month, setMonth] = useState(currentMonth)
  return (
    <section className="space-y-5">
      <header>
        <h1 className="text-2xl font-bold">Коды доступа сотрудников</h1>
        <p className="mt-2 text-sm text-ink-500">
          Общий временный доступ к фотоотчётам выбранных аптек и месяца. Код и QR не подтверждают
          личность и не дают административных прав.
        </p>
      </header>
      {!!options.error && <p role="alert">{describeError(options.error)}</p>}
      {options.pending && <p role="status">Проверяем доступные организации…</p>}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Аптечная организация">
          <Select
            ariaLabel="Аптечная организация"
            value={organization}
            onChange={setOrganization}
            options={[
              { value: '', label: 'Выберите организацию' },
              ...(options.data?.organizations ?? []).map((row) => ({
                value: row.id,
                label: row.name,
              })),
            ]}
          />
        </Field>
        <Field label="Месяц доступа">
          <Input
            aria-label="Месяц доступа"
            type="month"
            value={month}
            onChange={(event) => setMonth(event.target.value)}
          />
        </Field>
      </div>
      {!!organization &&
        /^\d{4}-\d{2}$/.test(month) &&
        options.data?.organizations.some((row) => row.id === organization) && (
          <OrganizationAccess
            key={`${organization}:${month}`}
            api={api}
            organizationId={organization}
            month={`${month}-01`}
          />
        )}
    </section>
  )
}
function OrganizationAccess({
  api,
  organizationId,
  month,
}: {
  api: AdminApi
  organizationId: string
  month: string
}) {
  const load = useCallback(
    (signal: AbortSignal) => api.settings(organizationId, month, signal),
    [api, organizationId, month],
  )
  const read = usePlacementRead(load)
  const [branches, setBranches] = useState<string[]>([])
  const [deadline, setDeadline] = useState('')
  const [issued, setIssued] = useState<Awaited<ReturnType<AdminApi['create']>> | null>(null)
  const [revokeId, setRevokeId] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const settings = read.data
  return (
    <div className="space-y-4">
      {read.pending && <p role="status">Загружаем выданные доступы…</p>}
      {!!read.error && <p role="alert">{describeError(read.error)}</p>}
      {settings && (
        <>
          <p className="text-sm">
            Организация: {settings.organizationName}. Доступных записей за месяц:{' '}
            {settings.codes.length}.
          </p>
          {settings.canCreate && (
            <form
              className="card space-y-4 p-5"
              onSubmit={(event) => {
                event.preventDefault()
                if (pending) return
                setPending(true)
                setError('')
                setIssued(null)
                const eligible = settings.branches
                  .filter((row) => row.canCreate)
                  .map((row) => row.id)
                if (!branches.length || branches.some((id) => !eligible.includes(id))) {
                  setError('Выберите доступные аптеки.')
                  setPending(false)
                  return
                }
                void api
                  .create({
                    organizationId,
                    placementMonth: month,
                    branchIds: branches,
                    deadlineAt: deadline ? `${deadline}:00+05:00` : null,
                  })
                  .then((result) => {
                    setIssued(result)
                    read.reload()
                  })
                  .catch((failure) =>
                    setError(
                      `${describeError(failure)} Обновите список: доступ мог быть создан, но код повторно не отображается.`,
                    ),
                  )
                  .finally(() => setPending(false))
              }}
            >
              <h2 className="font-bold">Выдать новый доступ</h2>
              <fieldset className="space-y-2">
                <legend className="mb-2 text-sm font-semibold">Аптеки доступа</legend>
                {settings.branches.map((row) => (
                  <label className="flex items-start gap-2 text-sm" key={row.id}>
                    <input
                      type="checkbox"
                      checked={branches.includes(row.id)}
                      disabled={!row.canCreate || pending}
                      onChange={(event) =>
                        setBranches((current) =>
                          event.target.checked
                            ? [...current, row.id]
                            : current.filter((id) => id !== row.id),
                        )
                      }
                    />
                    <span>
                      {row.name} · {row.city} · {row.address}
                      {!row.canCreate ? ' — выдача недоступна' : ''}
                    </span>
                  </label>
                ))}
              </fieldset>
              <Field label="Срок сдачи (Алматы, UTC+5)" optional>
                <Input
                  aria-label="Срок сдачи"
                  type="datetime-local"
                  value={deadline}
                  onChange={(event) => setDeadline(event.target.value)}
                  disabled={pending}
                />
              </Field>
              <p className="text-xs text-ink-500">
                Пустой срок остаётся без ограничения по дедлайну. Сеанс всё равно ограничен по
                времени. Одноразовый код и QR показываются только в ответе на создание.
              </p>
              <Button type="submit" disabled={pending || !branches.length}>
                {pending ? 'Создаём…' : 'Создать код и QR'}
              </Button>
            </form>
          )}
          {settings.codes.map((code) => (
            <article className="card space-y-3 p-4" key={code.codeId}>
              <h3 className="font-bold">
                {code.manualRevoked
                  ? 'Доступ отозван'
                  : code.expired
                    ? 'Срок истёк'
                    : code.exhausted
                      ? 'Разовый код использован'
                      : code.status === 'active'
                        ? 'Доступ активен'
                        : 'Доступ закрыт'}
              </h3>
              <p className="text-sm">
                Код: скрыт после создания. Использований кода {code.useCount}/{code.maxUses}; QR:{' '}
                {code.qrUseCount}. Аптек:{' '}
                {code.allOrgBranches ? 'все в разрешённой организации' : code.branchIds.length}.
              </p>
              <p className="text-sm">
                Срок:{' '}
                {code.deadlineAt
                  ? new Date(code.deadlineAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })
                  : 'не задан'}
                . Создан{' '}
                {new Date(code.createdAt).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty' })}.
              </p>
              {code.exhausted && !code.manualRevoked && (
                <p className="text-xs text-ink-500">
                  Новый вход по этому коду и QR закрыт. Уже выданные сеансы продолжаются до
                  окончания срока или явного отзыва.
                </p>
              )}
              {code.canRevoke && (
                <Button
                  variant="danger"
                  disabled={pending}
                  onClick={() => {
                    setRevokeId(code.codeId)
                    setError('')
                  }}
                >
                  Отозвать доступ
                </Button>
              )}
              {revokeId === code.codeId && (
                <div className="space-y-2">
                  <p>Закрыть этот доступ и все выданные по нему сеансы?</p>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="danger"
                      disabled={pending}
                      onClick={() => {
                        setPending(true)
                        setError('')
                        setIssued(null)
                        void api
                          .revoke(code.codeId)
                          .then(() => {
                            setRevokeId(null)
                            read.reload()
                          })
                          .catch((failure) => setError(describeError(failure)))
                          .finally(() => setPending(false))
                      }}
                    >
                      Подтвердить отзыв
                    </Button>
                    <Button variant="outline" disabled={pending} onClick={() => setRevokeId(null)}>
                      Отмена
                    </Button>
                  </div>
                </div>
              )}
            </article>
          ))}
          {!settings.codes.length && <p>Доступов за этот месяц нет.</p>}
        </>
      )}
      {issued && settings?.canCreate && (
        <IssuedAccess
          key={issued.grant.codeId}
          code={issued.code}
          access={issued.accessToken}
          grant={issued.grant}
        />
      )}
      {error && <p role="alert">{error}</p>}
      <Button
        variant="outline"
        disabled={pending}
        onClick={() => {
          setIssued(null)
          read.reload()
        }}
      >
        Обновить список
      </Button>
    </div>
  )
}
function IssuedAccess({
  code,
  access,
  grant,
}: {
  code: string
  access: string
  grant: StaffAccessCode
}) {
  const [qr, setQr] = useState('')
  const [notice, setNotice] = useState('')
  const url = `https://epharm.inkar.kz/staff#access=${encodeURIComponent(access)}`
  useEffect(() => {
    let current = true
    void QRCode.toDataURL(url, { width: 280, margin: 1 })
      .then((result) => {
        if (current) setQr(result)
      })
      .catch(() => {
        if (current) setNotice('Не удалось сформировать QR.')
      })
    return () => {
      current = false
    }
  }, [url])
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setNotice('Скопировано.')
    } catch {
      setNotice('Не удалось скопировать. Выберите и скопируйте код вручную.')
    }
  }
  return (
    <section
      className="card space-y-3 border border-accent-success p-5"
      aria-label="Созданный доступ"
    >
      <h2 className="font-bold">Сохраните выданный доступ сейчас</h2>
      <p className="text-2xl font-bold tracking-widest">{code}</p>
      <p className="text-sm">
        Разовый код расходуется при первом входе. QR допускает повторный вход, пока доступ активен.
        Не публикуйте код или QR в открытом доступе.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => void copy(code)}>
          Скопировать код
        </Button>
        <Button variant="outline" onClick={() => void copy(url)}>
          Скопировать QR-ссылку
        </Button>
        {qr && (
          <a
            className="btn btn-md btn-outline"
            href={qr}
            download={`staff-access-${grant.placementMonth}-${grant.codeId.slice(0, 8)}.png`}
          >
            Скачать QR
          </a>
        )}
      </div>
      {qr && <img src={qr} alt="QR временного доступа сотрудника" width={280} height={280} />}
      <p className="text-xs text-ink-500">
        Код и QR не сохраняются в браузерном хранилище и исчезнут при смене организации, месяца или
        учётной записи.
      </p>
      {notice && <p role="status">{notice}</p>}
    </section>
  )
}
