import { useCallback, useState } from 'react'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { roleLabel } from '@/mocks/fixtures'
import { usePlacementRead } from '@/features/merch/usePlacementRead'
import {
  accessApi,
  accessFailure,
  type AccessAccount,
  type AccessCatalog,
  type SourceAccessAccount,
  type VerifiedPerson,
} from './access-api'
import type { AdminRole } from '@/lib/api-types'

type Operation =
  | { mode: 'person' | 'pharmacist' }
  | { mode: 'bind' | 'provision'; source: SourceAccessAccount }

export default function IdentityManagement({
  accounts,
  people,
  catalog,
  canManage,
  onChanged,
}: {
  accounts: AccessAccount[]
  people: VerifiedPerson[]
  catalog: AccessCatalog
  canManage: boolean
  onChanged: () => void
}) {
  const load = useCallback((signal: AbortSignal) => accessApi.sourceAccounts(signal), [])
  const source = usePlacementRead(load)
  const [operation, setOperation] = useState<Operation | null>(null)
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-4">
        <p className="max-w-3xl text-[13px] text-ink-500">
          Сначала подтвердите личность человека по проверенному основанию, затем явно сопоставьте
          исходный аккаунт. Совпадение имени или email не объединяет записи и не выдаёт права.
        </p>
        {canManage && (
          <div className="flex shrink-0 gap-2">
            <Button
              variant="outline"
              disabled={!people.length}
              onClick={() => setOperation({ mode: 'pharmacist' })}
            >
              Связать фармацевта
            </Button>
            <Button onClick={() => setOperation({ mode: 'person' })}>
              Подтвердить нового человека
            </Button>
          </div>
        )}
      </div>
      {!people.length && (
        <p className="rounded-lg bg-paper-input p-3 text-[13px]">
          Проверенных личностей пока нет. Сопоставление и перенос аккаунта доступны только после
          явного подтверждения человека.
        </p>
      )}
      {source.pending && <p role="status">Загружаем исходные аккаунты…</p>}
      {!!source.error && (
        <div role="alert" className="rounded-lg bg-surface-danger p-3 text-[13px]">
          <p>{accessFailure(source.error).message}</p>
          <Button variant="outline" className="mt-2" onClick={source.reload}>
            Повторить
          </Button>
        </div>
      )}
      {source.data && (
        <div className="overflow-auto">
          <table className="w-full text-left text-[13px]">
            <thead className="border-b text-ink-500">
              <tr>
                <th className="py-2">Исходный сотрудник</th>
                <th>Роль в CRM</th>
                <th>Связь</th>
                <th className="text-right">Действие</th>
              </tr>
            </thead>
            <tbody>
              {source.data.map((row) => (
                <tr key={row.sourceAccountId} className="border-b last:border-0">
                  <td className="py-3">
                    <div className="font-semibold">{row.name}</div>
                    <div className="text-ink-500">{row.email ?? 'Email не указан'}</div>
                    <div className="text-[11px] text-ink-400">
                      {row.active ? 'Активен в источнике' : 'Неактивен в источнике'}
                    </div>
                  </td>
                  <td>{sourceRole(row.sourceRole)}</td>
                  <td>
                    {row.accountId ? (
                      <>
                        <span>Сопоставлен</span>
                        <div className="text-[11px] text-ink-500">
                          {accounts.find((account) => account.accountId === row.accountId)?.name ??
                            'Аккаунт подтверждён сервером'}
                        </div>
                      </>
                    ) : (
                      'Не сопоставлен'
                    )}
                  </td>
                  <td className="py-3 text-right">
                    {row.sourceRole === 'advertiser' ? (
                      <span className="text-ink-500">Отдельный кабинет производителя по SMS</span>
                    ) : !row.accountId && canManage ? (
                      <div className="flex justify-end gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={
                            !people.length ||
                            !accounts.some((account) => account.managed && account.personId)
                          }
                          onClick={() => setOperation({ mode: 'bind', source: row })}
                        >
                          Сопоставить аккаунт
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={
                            !people.length || !row.hasPassword || !catalog.assignableRoles.length
                          }
                          onClick={() => setOperation({ mode: 'provision', source: row })}
                        >
                          Перенести без прав
                        </Button>
                      </div>
                    ) : (
                      <span className="text-ink-500">
                        {row.accountId ? 'Связь сохранена' : 'Только просмотр'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!source.data.length && <p className="py-4 text-ink-500">Исходных аккаунтов нет.</p>}
        </div>
      )}
      <p className="text-[12px] text-ink-500">
        Пароли и их хеши не отображаются. Перенос сохраняет исходный статус; новый административный
        аккаунт получает пустые права. При занятом email используйте проверенное сопоставление, а не
        автоматическое объединение.
      </p>
      {operation && (
        <IdentityDialog
          key={
            'source' in operation
              ? `${operation.mode}:${operation.source.sourceAccountId}`
              : operation.mode
          }
          operation={operation}
          accounts={accounts}
          people={people}
          catalog={catalog}
          onClose={() => setOperation(null)}
          onSaved={() => {
            setOperation(null)
            onChanged()
          }}
        />
      )}
    </div>
  )
}
function sourceRole(role: string) {
  return (
    (
      {
        admin: 'Администратор CRM',
        manager: 'Менеджер CRM',
        mas: 'МАС',
        divisional: 'Дивизионер',
        advertiser: 'Производитель',
      } as Record<string, string>
    )[role] ?? `Роль источника: ${role}`
  )
}
function IdentityDialog({
  operation,
  accounts,
  people,
  catalog,
  onClose,
  onSaved,
}: {
  operation: Operation
  accounts: AccessAccount[]
  people: VerifiedPerson[]
  catalog: AccessCatalog
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState('')
  const [personId, setPersonId] = useState('')
  const [accountId, setAccountId] = useState('')
  const [pharmacist, setPharmacist] = useState<{
    id: string
    name: string
    pharmacyName: string
    personId: string | null
  } | null>(null)
  const [role, setRole] = useState<AdminRole | ''>('')
  const [email, setEmail] = useState(
    operation.mode === 'provision' ? (operation.source.email ?? '') : '',
  )
  const [jobTitle, setJobTitle] = useState('')
  const [reference, setReference] = useState('')
  const [password, setPassword] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ReturnType<typeof accessFailure> | null>(null)
  const target = accounts.find(
    (account) =>
      account.accountId === accountId && account.managed && account.personId === personId,
  )
  const valid =
    confirmed &&
    reference.trim().length >= 3 &&
    password.length > 0 &&
    (operation.mode === 'person'
      ? name.trim().length > 0
      : people.some((person) => person.personId === personId) &&
        (operation.mode === 'bind'
          ? !!target
          : operation.mode === 'pharmacist'
            ? !!pharmacist && !pharmacist.personId
            : !!role &&
              catalog.assignableRoles.includes(role) &&
              /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())))
  const save = async () => {
    if (!valid || pending) return
    setPending(true)
    setError(null)
    const proof = {
      verifiedPersonId: personId,
      verificationReference: reference.trim(),
      currentPassword: password,
    }
    try {
      if (operation.mode === 'person')
        await accessApi.verifyPerson({
          name: name.trim(),
          verificationReference: reference.trim(),
          currentPassword: password,
        })
      else if (operation.mode === 'pharmacist' && pharmacist)
        await accessApi.bindPharmacist({ ...proof, pharmacistId: pharmacist.id })
      else if (operation.mode === 'bind')
        await accessApi.bindSource(operation.source.sourceAccountId, {
          ...proof,
          targetAccountId: accountId,
        })
      else if (operation.mode === 'provision' && role)
        await accessApi.provisionSource(operation.source.sourceAccountId, {
          ...proof,
          role,
          email: email.trim(),
          jobTitle: jobTitle.trim(),
        })
      setPassword('')
      onSaved()
    } catch (failure) {
      setError(accessFailure(failure))
      setPassword('')
    } finally {
      setPending(false)
    }
  }
  const field = (id: string) => error?.fields[id]
  return (
    <Modal
      open
      title={
        operation.mode === 'person'
          ? 'Подтвердить нового человека'
          : operation.mode === 'pharmacist'
            ? 'Связать фармацевта с человеком'
            : operation.mode === 'bind'
              ? 'Сопоставить исходный аккаунт'
              : 'Перенести аккаунт без прав'
      }
      onClose={() => {
        if (!pending) {
          setPassword('')
          onClose()
        }
      }}
      footer={
        <>
          <Button
            variant="ghost"
            disabled={pending}
            onClick={() => {
              setPassword('')
              onClose()
            }}
          >
            Отмена
          </Button>
          <Button disabled={!valid || pending} onClick={save}>
            {pending ? 'Сохраняем…' : 'Подтвердить действие'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {'source' in operation && (
          <p className="text-[13px]">
            Исходная запись: <strong>{operation.source.name}</strong>.{' '}
            {operation.mode === 'provision'
              ? 'Создаётся новый аккаунт с исходным паролем и статусом, без прав. Пароль не раскрывается.'
              : 'Выберите уже проверенный административный аккаунт того же человека. Его права не меняются.'}
          </p>
        )}
        {operation.mode === 'pharmacist' && (
          <PharmacistPicker disabled={pending} selected={pharmacist} onSelect={setPharmacist} />
        )}
        {operation.mode === 'person' ? (
          <Field label="ФИО человека" error={field('name')}>
            <Input
              aria-label="ФИО подтверждаемого человека"
              value={name}
              maxLength={255}
              disabled={pending}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>
        ) : (
          <>
            <Field label="Проверенный человек" error={field('verifiedPersonId')}>
              <Select
                ariaLabel="Проверенный человек для сопоставления"
                value={personId}
                disabled={pending}
                onChange={(value) => {
                  setPersonId(value)
                  setAccountId('')
                }}
                options={[
                  { value: '', label: 'Выберите явно' },
                  ...people.map((person) => ({
                    value: person.personId,
                    label: person.displayName,
                  })),
                ]}
              />
            </Field>
            {operation.mode === 'bind' ? (
              <Field label="Существующий аккаунт" error={field('targetAccountId')}>
                <Select
                  ariaLabel="Целевой административный аккаунт"
                  value={accountId}
                  disabled={pending || !personId}
                  onChange={setAccountId}
                  options={[
                    { value: '', label: 'Выберите аккаунт этого человека' },
                    ...accounts
                      .filter((account) => account.managed && account.personId === personId)
                      .map((account) => ({
                        value: account.accountId,
                        label: `${account.name} · ${account.email}`,
                      })),
                  ]}
                />
              </Field>
            ) : operation.mode === 'provision' ? (
              <>
                <Field label="Роль нового аккаунта" error={field('role')}>
                  <Select
                    ariaLabel="Роль переносимого аккаунта"
                    value={role}
                    disabled={pending}
                    onChange={(value) => setRole(value as AdminRole)}
                    options={[
                      { value: '', label: 'Выберите роль' },
                      ...catalog.assignableRoles.map((value) => ({
                        value,
                        label: roleLabel(value),
                      })),
                    ]}
                  />
                </Field>
                <Field label="Email нового аккаунта" error={field('email')}>
                  <Input
                    aria-label="Email переносимого аккаунта"
                    type="email"
                    value={email}
                    maxLength={255}
                    disabled={pending}
                    onChange={(event) => setEmail(event.target.value)}
                  />
                </Field>
                <Field label="Должность" optional error={field('jobTitle')}>
                  <Input
                    value={jobTitle}
                    maxLength={255}
                    disabled={pending}
                    onChange={(event) => setJobTitle(event.target.value)}
                  />
                </Field>
              </>
            ) : null}
          </>
        )}
        <Field
          label="Основание проверки"
          hint="Номер внутренней заявки или документ проверки. Не вводите пароль другого сотрудника."
          error={field('verificationReference')}
        >
          <Input
            aria-label="Основание проверки личности"
            value={reference}
            maxLength={1000}
            disabled={pending}
            onChange={(event) => setReference(event.target.value)}
          />
        </Field>
        <label className="flex gap-2 text-[13px]">
          <input
            type="checkbox"
            checked={confirmed}
            disabled={pending}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          Я проверил личность и выбранную связь; совпадение имени или email не является основанием.
        </label>
        <Field label="Ваш текущий пароль" error={field('currentPassword')}>
          <Input
            aria-label="Текущий пароль подтверждения личности"
            type="password"
            autoComplete="current-password"
            value={password}
            maxLength={128}
            disabled={pending}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>
        {error && (
          <p
            role="alert"
            className="rounded-lg bg-surface-danger p-3 text-[13px] text-accent-danger"
          >
            {error.message} Действие не повторяется автоматически.
          </p>
        )}
      </div>
    </Modal>
  )
}
function PharmacistPicker({
  disabled,
  selected,
  onSelect,
}: {
  disabled: boolean
  selected: { id: string; name: string; pharmacyName: string; personId: string | null } | null
  onSelect: (row: typeof selected) => void
}) {
  const [query, setQuery] = useState('')
  const [applied, setApplied] = useState('')
  const [offset, setOffset] = useState(0)
  const load = useCallback(
    (signal: AbortSignal) => accessApi.pharmacistOptions(applied, offset, signal),
    [applied, offset],
  )
  const read = usePlacementRead(load)
  return (
    <div className="rounded-lg border border-paper-line p-3">
      <Field label="Фармацевт из реестра">
        <div className="flex gap-2">
          <Input
            aria-label="Поиск фармацевта для связи"
            value={query}
            maxLength={120}
            disabled={disabled}
            onChange={(event) => setQuery(event.target.value)}
          />
          <Button
            variant="outline"
            disabled={disabled}
            onClick={() => {
              setApplied(query.trim())
              setOffset(0)
              onSelect(null)
            }}
          >
            Найти
          </Button>
        </div>
      </Field>
      {selected && (
        <p className="my-2 text-[13px] font-semibold">
          Выбран: {selected.name} · {selected.pharmacyName}
        </p>
      )}
      {read.pending && <p role="status">Загружаем реестр…</p>}
      {!!read.error && (
        <p role="alert" className="text-[13px] text-accent-danger">
          {accessFailure(read.error).message}
        </p>
      )}
      {read.data && (
        <>
          <div className="my-2 max-h-40 overflow-auto">
            {read.data.items.map((row) => (
              <button
                key={row.id}
                type="button"
                className="block w-full border-b p-2 text-left text-[13px] hover:bg-paper-hover disabled:opacity-50"
                disabled={disabled || !!row.personId}
                onClick={() => onSelect(row)}
              >
                {row.name} · {row.pharmacyName}
                {row.personId ? ' · связь уже подтверждена' : ''}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={disabled || !offset}
              onClick={() => setOffset((value) => Math.max(0, value - 50))}
            >
              Назад
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={disabled || !read.data.hasMore}
              onClick={() => setOffset((value) => value + 50)}
            >
              Далее
            </Button>
            <span className="text-[11px] text-ink-500">
              {read.data.hasMore ? 'Есть следующая страница' : 'Последняя страница'}
            </span>
          </div>
          {!read.data.items.length && (
            <p className="text-[13px] text-ink-500">Фармацевты не найдены.</p>
          )}
        </>
      )}
      <p className="mt-2 text-[12px] text-ink-500">
        Связь не создаёт новый аккаунт фармацевта, не меняет его пароль и не начисляет деньги.
      </p>
    </div>
  )
}
