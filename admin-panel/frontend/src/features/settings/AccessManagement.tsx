import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal, SectionCard, Select } from '@/ui'
import { roleLabel } from '@/mocks/fixtures'
import {
  accessApi,
  accessFailure,
  permissionId,
  validateGrants,
  type AccessAccount,
  type AccessCapabilities,
  type AccessCatalog,
  type AccessGrant,
  type AccessProfile,
  type CreateAccessAccount,
  type VerifiedPerson,
} from './access-api'

type Failure = ReturnType<typeof accessFailure>

function ErrorNotice({ error }: { error: Failure | null }) {
  return error ? (
    <div role="alert" className="rounded-lg bg-surface-danger p-3 text-[13px] text-accent-danger">
      {error.message}
    </div>
  ) : null
}

export default function AccessManagement() {
  const [refresh, setRefresh] = useState(0)
  const [data, setData] = useState<{
    capabilities: AccessCapabilities
    accounts: AccessAccount[]
    people: VerifiedPerson[]
    catalog: AccessCatalog
  } | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [loading, setLoading] = useState(true)
  const [createOpen, setCreateOpen] = useState(false)
  const [selected, setSelected] = useState<AccessAccount | null>(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    accessApi
      .me(controller.signal)
      .then(async (capabilities) => {
        if (!capabilities.canViewAccounts)
          return {
            capabilities,
            accounts: [],
            people: [],
            catalog: { permissions: [], assignableRoles: [], pharmacies: [] },
          }
        const [accounts, people, catalog] = await Promise.all([
          accessApi.accounts(controller.signal),
          accessApi.people(controller.signal),
          accessApi.catalog(controller.signal),
        ])
        return { capabilities, accounts, people, catalog }
      })
      .then((result) => {
        if (!controller.signal.aborted) setData(result)
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setError(accessFailure(reason))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [refresh])

  const reload = () => {
    setLoading(true)
    setData(null)
    setSelected(null)
    setError(null)
    setRefresh((value) => value + 1)
  }

  return (
    <SectionCard
      title="Аккаунты и права доступа"
      subtitle="Права применяются сервером и ограничены ролью и областью аптек."
    >
      <div className="flex flex-col gap-4">
        {loading && (
          <div role="status" className="text-ink-500">
            Проверяем доступ…
          </div>
        )}
        <ErrorNotice error={error} />
        {error && (
          <Button variant="outline" onClick={reload}>
            Повторить
          </Button>
        )}
        {data && !data.capabilities.canViewAccounts && (
          <p className="text-ink-500">У вас нет доступа к управлению аккаунтами.</p>
        )}
        {data?.capabilities.canViewAccounts && (
          <>
            <div className="flex items-center justify-between gap-3">
              <p className="text-[13px] text-ink-500">
                Новый аккаунт создаётся без прав. Права назначаются отдельным действием.
              </p>
              <div className="flex gap-2">
                <Button variant="outline" onClick={reload}>
                  Обновить список
                </Button>
                {data.capabilities.canManageAccounts && (
                  <Button onClick={() => setCreateOpen(true)}>Создать аккаунт</Button>
                )}
              </div>
            </div>
            {notice && (
              <p role="status" className="text-[13px] font-semibold text-ink-700">
                {notice}
              </p>
            )}
            {!data.accounts.length ? (
              <p className="text-ink-500">Аккаунты не найдены.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-[13px]">
                  <thead className="border-b text-[12px] text-ink-500">
                    <tr>
                      <th className="py-2">Сотрудник</th>
                      <th>Роль / режим</th>
                      <th>Статус</th>
                      <th className="text-right">Доступ</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.accounts.map((account) => (
                      <tr key={account.accountId} className="border-b last:border-0">
                        <td className="py-3">
                          <div className="font-semibold">{account.name}</div>
                          <div className="text-ink-500">{account.email}</div>
                          {account.jobTitle && (
                            <div className="text-ink-400">{account.jobTitle}</div>
                          )}
                        </td>
                        <td>
                          {roleLabel(account.roleCeiling)}
                          <div className="text-[12px] text-ink-500">
                            {account.managed
                              ? `Управляемый · версия ${account.version ?? '—'}`
                              : 'Прежние права'}
                          </div>
                        </td>
                        <td>
                          {account.status === 'ACTIVE' || account.status === 'active'
                            ? 'Активен'
                            : 'Неактивен'}
                          {account.primaryHqOwner && (
                            <div className="text-[12px] text-ink-500">Главный HQ</div>
                          )}
                        </td>
                        <td className="text-right">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={!account.managed || !account.personId}
                            onClick={() => setSelected(account)}
                          >
                            {data.capabilities.canManageAccounts && account.editable
                              ? 'Настроить права'
                              : 'Посмотреть права'}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="text-[12px] text-ink-400">
              Прежние аккаунты не переводятся на новые права автоматически. Личные кабинеты
              фармацевтов и кассы работают отдельно.
            </p>
            {createOpen && (
              <CreateAccount
                catalog={data.catalog}
                people={data.people}
                onClose={() => setCreateOpen(false)}
                onCreated={() => {
                  setCreateOpen(false)
                  setNotice('Аккаунт создан без прав. Выберите его в списке и настройте доступ.')
                  reload()
                }}
              />
            )}
            {selected && (
              <GrantEditor
                key={selected.accountId}
                account={selected}
                catalog={data.catalog}
                canManage={data.capabilities.canManageAccounts && selected.editable}
                onClose={() => setSelected(null)}
                onSaved={(version) => {
                  setData((current) =>
                    current
                      ? {
                          ...current,
                          accounts: current.accounts.map((account) =>
                            account.accountId === selected.accountId
                              ? { ...account, version }
                              : account,
                          ),
                        }
                      : current,
                  )
                }}
              />
            )}
          </>
        )}
      </div>
    </SectionCard>
  )
}

function CreateAccount({
  catalog,
  people,
  onClose,
  onCreated,
}: {
  catalog: AccessCatalog
  people: VerifiedPerson[]
  onClose: () => void
  onCreated: () => void
}) {
  const [form, setForm] = useState<CreateAccessAccount>({
    email: '',
    name: '',
    password: '',
    role: '' as CreateAccessAccount['role'],
    verifiedPersonId: '',
    jobTitle: '',
    currentPassword: '',
  })
  const [error, setError] = useState<Failure | null>(null)
  const [pending, setPending] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const set = (patch: Partial<CreateAccessAccount>) =>
    setForm((current) => ({ ...current, ...patch }))
  const validation = {
    email:
      /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()) && form.email.length <= 255
        ? ''
        : 'Укажите email.',
    name: form.name.trim() && form.name.length <= 255 ? '' : 'Укажите имя сотрудника.',
    password:
      form.password.length >= 12 && new TextEncoder().encode(form.password).length <= 72
        ? ''
        : 'От 12 символов, не больше 72 байт UTF-8.',
    role: catalog.assignableRoles.includes(form.role) ? '' : 'Выберите доступную роль.',
    verifiedPersonId: people.some((person) => person.personId === form.verifiedPersonId)
      ? ''
      : 'Выберите проверенного человека из списка.',
    currentPassword:
      form.currentPassword && form.currentPassword.length <= 128
        ? ''
        : 'Введите свой текущий пароль.',
  }
  const submit = async () => {
    setAttempted(true)
    if (Object.values(validation).some(Boolean)) return
    setPending(true)
    setError(null)
    try {
      await accessApi.create({
        ...form,
        email: form.email.trim(),
        name: form.name.trim(),
        jobTitle: form.jobTitle.trim(),
      })
      onCreated()
    } catch (reason) {
      setError(accessFailure(reason))
    } finally {
      set({ password: '', currentPassword: '' })
      setPending(false)
    }
  }
  const fieldError = (name: keyof typeof validation) =>
    error?.fields[name] || (attempted ? validation[name] : '')
  return (
    <Modal
      open
      onClose={() => {
        if (!pending) onClose()
      }}
      title="Создать аккаунт администратора"
      subtitle="Используется только заранее проверенная связь с человеком."
      width={620}
      footer={
        <>
          <Button variant="ghost" disabled={pending} onClick={onClose}>
            Отмена
          </Button>
          <Button
            disabled={pending || !people.length || !catalog.assignableRoles.length}
            onClick={submit}
          >
            {pending ? 'Создаём…' : 'Создать без прав'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <ErrorNotice error={error} />
        {!people.length && (
          <p role="status" className="rounded-lg bg-paper-input p-3 text-[13px]">
            Нет проверенных сотрудников для привязки. Сначала нужно подтвердить человека; совпадение
            имени или email не заменяет проверку.
          </p>
        )}
        {!catalog.assignableRoles.length && (
          <p role="status" className="text-accent-danger">
            Нет доступных ролей. Создание аккаунта недоступно.
          </p>
        )}
        <Field label="Проверенный сотрудник" error={fieldError('verifiedPersonId')}>
          <Select
            ariaLabel="Проверенный сотрудник"
            value={form.verifiedPersonId}
            onChange={(verifiedPersonId) => set({ verifiedPersonId })}
            options={people.map((person) => ({
              value: person.personId,
              label: `${person.displayName} · ${person.referenceLabel} · ${person.personId}`,
            }))}
            disabled={pending || !people.length}
          />
        </Field>
        <Field label="Имя сотрудника" error={fieldError('name')}>
          <Input
            aria-label="Имя сотрудника"
            value={form.name}
            onChange={(event) => set({ name: event.target.value })}
            maxLength={255}
            disabled={pending}
            autoComplete="off"
          />
        </Field>
        <Field label="Email нового аккаунта" error={fieldError('email')}>
          <Input
            aria-label="Email нового аккаунта"
            type="email"
            value={form.email}
            onChange={(event) => set({ email: event.target.value })}
            maxLength={255}
            disabled={pending}
            autoComplete="off"
          />
        </Field>
        <Field label="Должность" optional error={error?.fields.jobTitle}>
          <Input
            value={form.jobTitle}
            onChange={(event) => set({ jobTitle: event.target.value })}
            maxLength={255}
            disabled={pending}
          />
        </Field>
        <Field
          label="Роль — верхняя граница прав"
          error={fieldError('role')}
          hint="Роль сама по себе не выдаёт права разделов."
        >
          <Select
            ariaLabel="Роль — верхняя граница прав"
            value={form.role}
            onChange={(role) => set({ role: role as CreateAccessAccount['role'] })}
            options={catalog.assignableRoles.map((role) => ({
              value: role,
              label: roleLabel(role),
            }))}
            disabled={pending}
          />
        </Field>
        <Field label="Начальный пароль нового аккаунта" error={fieldError('password')}>
          <Input
            aria-label="Начальный пароль нового аккаунта"
            type="password"
            autoComplete="new-password"
            value={form.password}
            onChange={(event) => set({ password: event.target.value })}
            disabled={pending}
            maxLength={72}
          />
        </Field>
        <Field
          label="Ваш текущий пароль"
          error={fieldError('currentPassword')}
          hint="Подтверждает это создание. Пароли не сохраняются в браузере приложением."
        >
          <Input
            aria-label="Ваш текущий пароль"
            type="password"
            autoComplete="off"
            value={form.currentPassword}
            onChange={(event) => set({ currentPassword: event.target.value })}
            disabled={pending}
            maxLength={128}
          />
        </Field>
      </div>
    </Modal>
  )
}

function GrantEditor({
  account,
  catalog,
  canManage,
  onClose,
  onSaved,
}: {
  account: AccessAccount
  catalog: AccessCatalog
  canManage: boolean
  onClose: () => void
  onSaved: (version: number) => void
}) {
  const [refresh, setRefresh] = useState(0)
  const [profile, setProfile] = useState<AccessProfile | null>(null)
  const [grants, setGrants] = useState<AccessGrant[]>([])
  const [activePermission, setActivePermission] = useState('')
  const [error, setError] = useState<Failure | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [success, setSuccess] = useState('')
  const [pharmacySearch, setPharmacySearch] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    accessApi
      .profile(account.accountId, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        setProfile(result)
        setGrants(result.grants.map((grant) => ({ ...grant, pharmacyIds: [...grant.pharmacyIds] })))
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setError(accessFailure(reason))
      })
    return () => controller.abort()
  }, [account.accountId, refresh])

  const groupMap = new Map<string, typeof catalog.permissions>()
  catalog.permissions.forEach((permission) => {
    const key = `${permission.module}.${permission.subsection}`
    groupMap.set(key, [...(groupMap.get(key) ?? []), permission])
  })
  const active = catalog.permissions.find(
    (permission) => permissionId(permission) === activePermission,
  )
  const activeGrant = grants.find((grant) => permissionId(grant) === activePermission)
  const unknownGrants = grants.filter(
    (grant) =>
      !catalog.permissions.some((permission) => permissionId(permission) === permissionId(grant)),
  )
  const locked = !canManage || pending || error?.conflict === true
  const changeGrant = (id: string, patch: Partial<AccessGrant>) => {
    setGrants((current) =>
      current.map((grant) => (permissionId(grant) === id ? { ...grant, ...patch } : grant)),
    )
    setValidation(null)
    setSuccess('')
  }
  const requestSave = () => {
    const invalid = validateGrants(grants, catalog, account.roleCeiling)
    setValidation(invalid)
    if (!invalid) {
      setError(null)
      setCurrentPassword('')
      setConfirmOpen(true)
    }
  }
  const save = async () => {
    if (!profile || !currentPassword || currentPassword.length > 128 || locked) return
    const invalid = validateGrants(grants, catalog, account.roleCeiling)
    if (invalid) {
      setValidation(invalid)
      setConfirmOpen(false)
      return
    }
    setPending(true)
    setError(null)
    try {
      const result = await accessApi.replace(
        account.accountId,
        profile.version,
        grants,
        currentPassword,
      )
      setProfile(result)
      setGrants(result.grants)
      setSuccess(`Права сохранены. Версия ${result.version}.`)
      setConfirmOpen(false)
      onSaved(result.version)
    } catch (reason) {
      setError(accessFailure(reason, true))
    } finally {
      setCurrentPassword('')
      setPending(false)
    }
  }
  const reload = () => {
    setProfile(null)
    setGrants([])
    setError(null)
    setValidation(null)
    setConfirmOpen(false)
    setCurrentPassword('')
    setRefresh((value) => value + 1)
  }
  return (
    <Modal
      open
      onClose={() => {
        if (!pending) onClose()
      }}
      title={`Права: ${account.name}`}
      subtitle={`${roleLabel(account.roleCeiling)} · ${account.email}`}
      width={1000}
      footer={
        <>
          <Button variant="ghost" disabled={pending} onClick={onClose}>
            Закрыть
          </Button>
          {canManage && (
            <Button disabled={!profile || locked || confirmOpen} onClick={requestSave}>
              Проверить и сохранить
            </Button>
          )}
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <ErrorNotice error={error} />
        {Object.entries(error?.fields ?? {})
          .filter(([field]) => field !== 'currentPassword')
          .map(([field, message]) => (
            <p key={field} className="text-[12px] text-accent-danger">
              {message}
            </p>
          ))}
        {(error?.conflict || (!profile && error)) && (
          <Button variant="outline" onClick={reload}>
            Загрузить актуальные права
          </Button>
        )}
        {!profile && !error && <p role="status">Загружаем права…</p>}
        {success && (
          <p role="status" className="font-semibold text-ink-700">
            {success}
          </p>
        )}
        {validation && (
          <p role="alert" className="text-accent-danger">
            {validation}
          </p>
        )}
        {profile && (
          <>
            <p className="text-[12px] text-ink-500">
              Версия {profile.version}. Каждое действие имеет свою область аптек. Выключенные
              разрешения не выдаются автоматически.
            </p>
            {!canManage && (
              <p className="text-ink-500">Только просмотр. Изменение этого аккаунта недоступно.</p>
            )}
            {unknownGrants.map((grant) => (
              <div
                key={permissionId(grant)}
                className="flex items-center justify-between gap-3 rounded-lg bg-paper-input p-3"
              >
                <p className="text-[12px]">
                  Неизвестное разрешение: {permissionId(grant)}. Проверьте его перед сохранением.
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={locked}
                  onClick={() =>
                    setGrants((current) =>
                      current.filter((item) => permissionId(item) !== permissionId(grant)),
                    )
                  }
                >
                  Отозвать разрешение
                </Button>
              </div>
            ))}
            <div className="grid grid-cols-[1.15fr_1fr] gap-4">
              <div className="flex flex-col gap-2">
                {[...groupMap.entries()].map(([key, permissions]) => (
                  <details
                    key={key}
                    className="rounded-lg border border-paper-line"
                    open={permissions.some((permission) =>
                      grants.some((grant) => permissionId(grant) === permissionId(permission)),
                    )}
                  >
                    <summary className="cursor-pointer bg-paper-input px-3 py-2 text-[13px] font-semibold">
                      {permissions[0].moduleTitle} / {permissions[0].subsectionTitle}
                    </summary>
                    <div className="flex flex-col gap-2 p-3">
                      {permissions.map((permission) => {
                        const id = permissionId(permission)
                        const grant = grants.find((item) => permissionId(item) === id)
                        const unavailable =
                          permission.mode === 'NOT_READY'
                            ? 'Ещё не подключено'
                            : !permission.availableForRoles.includes(account.roleCeiling)
                              ? 'Ограничено ролью'
                              : !permission.delegatable
                                ? 'Нельзя выдать с вашим доступом'
                                : ''
                        return (
                          <div
                            key={id}
                            className="flex items-start justify-between gap-2 text-[12px]"
                          >
                            <label className="flex items-start gap-2">
                              <input
                                type="checkbox"
                                className="mt-0.5 accent-brand-green-600"
                                checked={!!grant}
                                disabled={locked || (!grant && !!unavailable)}
                                onChange={(event) => {
                                  if (event.target.checked) {
                                    setGrants((current) => [
                                      ...current,
                                      {
                                        module: permission.module,
                                        subsection: permission.subsection,
                                        action: permission.action,
                                        allPharmacies: false,
                                        pharmacyIds: [],
                                      },
                                    ])
                                    setActivePermission(id)
                                  } else {
                                    setGrants((current) =>
                                      current.filter((item) => permissionId(item) !== id),
                                    )
                                    if (activePermission === id) setActivePermission('')
                                  }
                                  setValidation(null)
                                  setSuccess('')
                                }}
                              />
                              <span>
                                {permission.actionTitle}
                                {unavailable && (
                                  <span className="block text-ink-400">{unavailable}</span>
                                )}
                              </span>
                            </label>
                            {grant && (
                              <button
                                className="text-right font-semibold text-brand-green-700"
                                onClick={() => {
                                  setActivePermission(id)
                                  setPharmacySearch('')
                                }}
                              >
                                {grant.allPharmacies
                                  ? 'Вся сеть'
                                  : `Аптеки: ${grant.pharmacyIds.length}`}
                              </button>
                            )}
                          </div>
                        )
                      })}
                    </div>
                  </details>
                ))}
              </div>
              <div className="rounded-lg border border-paper-line p-4">
                {active && activeGrant ? (
                  <div className="flex flex-col gap-3">
                    <div className="text-[13px] font-bold">
                      Область: {active.subsectionTitle} / {active.actionTitle}
                    </div>
                    <p className="text-[12px] text-ink-500">
                      Пустой список не предоставляет доступ. Выбор относится только к этому
                      действию.
                    </p>
                    {!activeGrant.allPharmacies && activeGrant.pharmacyIds.length > 0 && (
                      <div className="text-[12px] text-ink-500">
                        <p className="font-semibold">Текущая выбранная область:</p>
                        {activeGrant.pharmacyIds.map((id) => {
                          const pharmacy = catalog.pharmacies.find((item) => item.id === id)
                          return (
                            <p key={id}>
                              {pharmacy
                                ? `${pharmacy.city} · ${pharmacy.name}`
                                : 'Название недоступно'}{' '}
                              · ID: {id}
                            </p>
                          )
                        })}
                      </div>
                    )}
                    <label className="flex items-center gap-2 text-[13px]">
                      <input
                        type="radio"
                        name="access-scope"
                        checked={!activeGrant.allPharmacies}
                        disabled={locked}
                        onChange={() =>
                          changeGrant(activePermission, { allPharmacies: false, pharmacyIds: [] })
                        }
                      />
                      Выбранные аптеки
                    </label>
                    <label className="flex items-center gap-2 text-[13px]">
                      <input
                        type="radio"
                        name="access-scope"
                        checked={activeGrant.allPharmacies}
                        disabled={locked || !active.delegationScope?.allPharmacies}
                        onChange={() =>
                          changeGrant(activePermission, { allPharmacies: true, pharmacyIds: [] })
                        }
                      />
                      Вся сеть — явное разрешение
                    </label>
                    {active.mode === 'ALL_ONLY' && (
                      <p className="text-[12px] text-ink-500">
                        Для этого действия сервер поддерживает только всю сеть.
                      </p>
                    )}
                    {!activeGrant.allPharmacies && (
                      <>
                        <Input
                          aria-label="Поиск аптек для доступа"
                          placeholder="Город, название, адрес"
                          value={pharmacySearch}
                          onChange={(event) => setPharmacySearch(event.target.value)}
                        />
                        <div className="max-h-64 overflow-auto">
                          {catalog.pharmacies
                            .filter(
                              (pharmacy) =>
                                (active.delegationScope?.allPharmacies ||
                                  active.delegationScope?.pharmacyIds.includes(pharmacy.id)) &&
                                `${pharmacy.city} ${pharmacy.name} ${pharmacy.address}`
                                  .toLocaleLowerCase()
                                  .includes(pharmacySearch.toLocaleLowerCase()),
                            )
                            .map((pharmacy) => (
                              <label
                                key={pharmacy.id}
                                className="flex gap-2 border-b py-2 text-[12px]"
                              >
                                <input
                                  type="checkbox"
                                  aria-label={`${pharmacy.city}, ${pharmacy.name}, ${pharmacy.address}`}
                                  checked={activeGrant.pharmacyIds.includes(pharmacy.id)}
                                  disabled={locked || active.mode === 'ALL_ONLY'}
                                  onChange={(event) =>
                                    changeGrant(activePermission, {
                                      pharmacyIds: event.target.checked
                                        ? [...activeGrant.pharmacyIds, pharmacy.id]
                                        : activeGrant.pharmacyIds.filter(
                                            (id) => id !== pharmacy.id,
                                          ),
                                    })
                                  }
                                />
                                <span>
                                  {pharmacy.name}
                                  <span className="block text-ink-500">
                                    {pharmacy.city} · {pharmacy.address}
                                  </span>
                                </span>
                              </label>
                            ))}
                        </div>
                        {!catalog.pharmacies.length && (
                          <p className="text-[12px] text-ink-500">
                            Нет подтверждённых аптек для выбора.
                          </p>
                        )}
                      </>
                    )}
                  </div>
                ) : (
                  <p className="text-[13px] text-ink-500">
                    Выберите действие, затем укажите его область аптек.
                  </p>
                )}
              </div>
            </div>
            {confirmOpen && (
              <div className="flex flex-col gap-3 rounded-lg border border-paper-line bg-paper-input p-4">
                <h3 className="font-bold">Подтвердите изменение прав</h3>
                <p className="text-[13px]">
                  {grants.length
                    ? `Будет сохранено разрешений: ${grants.length}. На всю сеть: ${grants.filter((grant) => grant.allPharmacies).length}.`
                    : 'Все управляемые права аккаунта будут отозваны.'}{' '}
                  Сохранение использует проверенную версию {profile.version}.
                </p>
                <Field
                  label="Ваш текущий пароль для сохранения"
                  error={error?.fields.currentPassword}
                >
                  <Input
                    aria-label="Ваш текущий пароль для сохранения"
                    type="password"
                    autoComplete="off"
                    value={currentPassword}
                    onChange={(event) => setCurrentPassword(event.target.value)}
                    maxLength={128}
                    disabled={pending || error?.conflict}
                  />
                </Field>
                <div className="flex gap-2">
                  <Button disabled={!currentPassword || locked} onClick={save}>
                    {pending
                      ? 'Сохраняем…'
                      : grants.length
                        ? 'Подтвердить права'
                        : 'Отозвать все права'}
                  </Button>
                  <Button
                    variant="ghost"
                    disabled={pending}
                    onClick={() => {
                      setConfirmOpen(false)
                      setCurrentPassword('')
                    }}
                  >
                    Отмена изменения
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </Modal>
  )
}
