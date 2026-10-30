import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Field, Input, Modal } from '@/ui'
import { describeError } from '@/lib/describeError'
import { inventoryEditorApi, type Organization } from './api'

export default function NetworkStructure({
  onChanged,
  onClose,
}: {
  onChanged: () => void
  onClose: () => void
}) {
  const query = useQuery({
    queryKey: ['admin-access', 'merch-network-structure'],
    queryFn: ({ signal }) => inventoryEditorApi.organizations(signal),
    retry: false,
  })
  const [edit, setEdit] = useState<Organization | 'new' | null>(null)
  const [branch, setBranch] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [blocked, setBlocked] = useState(false)
  const ready =
    query.isSuccess && query.isFetchedAfterMount && !query.isFetching && !query.isError && !blocked
  async function save(work: () => Promise<unknown>) {
    if (!ready || busy) return
    setBusy(true)
    setError('')
    try {
      await work()
      setEdit(null)
      setBranch(false)
      await query.refetch()
      onChanged()
    } catch (failure) {
      setError(describeError(failure))
      setBlocked(true)
      setEdit(null)
      setBranch(false)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      onClose={() => {
        if (!busy) onClose()
      }}
      title="Организации и аптеки мерча"
      subtitle="Изменение общей структуры требует явного доступа ко всей сети мерча. Пользователи и их права здесь не назначаются."
      width={880}
    >
      {(query.isPending || query.isFetching) && (
        <p role="status">Проверяем доступ и загружаем структуру…</p>
      )}
      {(query.isError || error) && (
        <div role="alert" className="mb-4 rounded-xl bg-surface-danger p-3">
          <p>{error || describeError(query.error)}</p>
          <Button
            variant="outline"
            onClick={async () => {
              const result = await query.refetch()
              if (!result.isError) {
                setBlocked(false)
                setError('')
              }
            }}
          >
            Обновить данные
          </Button>
        </div>
      )}
      {ready && (
        <>
          <div className="mb-4 flex flex-wrap gap-2">
            <Button
              disabled={busy}
              onClick={() => {
                setEdit('new')
                setBranch(false)
              }}
            >
              Добавить организацию
            </Button>
            <Button
              variant="outline"
              disabled={busy || !query.data.some((item) => item.active)}
              onClick={() => {
                setBranch(true)
                setEdit(null)
              }}
            >
              Добавить аптеку мерча
            </Button>
          </div>
          <div className="space-y-2">
            {query.data.map((item) => (
              <div
                className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-ink-100 p-3"
                key={item.id}
              >
                <div className="min-w-0 break-words">
                  <strong>{item.name}</strong>
                  <p className="text-sm text-ink-500">
                    {item.legalName}
                    {item.bin && ` · БИН ${item.bin}`}
                    {!item.active && ' · Архив'}
                  </p>
                </div>
                <Button
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    setEdit(item)
                    setBranch(false)
                  }}
                >
                  Изменить организацию
                </Button>
              </div>
            ))}
          </div>
          {query.data.length === 0 && (
            <p role="status" className="rounded-lg bg-paper-input p-4 text-sm text-ink-500">
              Организации пока не добавлены. Начните с организации, затем добавьте аптеку.
            </p>
          )}
          {edit && (
            <OrganizationForm
              key={typeof edit === 'string' ? edit : `${edit.id}:${edit.revision}`}
              value={edit === 'new' ? null : edit}
              busy={busy}
              save={save}
            />
          )}
          {branch && <BranchForm organizations={query.data} busy={busy} save={save} />}
        </>
      )}
    </Modal>
  )
}
function OrganizationForm({
  value,
  busy,
  save,
}: {
  value: Organization | null
  busy: boolean
  save: (work: () => Promise<unknown>) => Promise<void>
}) {
  const [name, setName] = useState(value?.name || '')
  const [legal, setLegal] = useState(value?.legalName || '')
  const [bin, setBin] = useState(value?.bin || '')
  const [active, setActive] = useState(value?.active ?? true)
  return (
    <form
      className="mt-5 space-y-3 rounded-xl bg-paper-input p-4"
      onSubmit={(e) => {
        e.preventDefault()
        if (
          !active &&
          !window.confirm(
            'Архивировать организацию? Аптеки и история сохранятся, действующие размещения защищены.',
          )
        )
          return
        void save(() =>
          inventoryEditorApi.organization(value?.id || null, {
            name,
            legalName: legal,
            bin: bin || null,
            active,
            ...(value ? { expectedRevision: value.revision } : {}),
          }),
        )
      }}
    >
      <h3 className="font-bold">{value ? 'Изменение организации' : 'Новая организация'}</h3>
      <Field label="Название организации">
        <Input
          aria-label="Название организации"
          value={name}
          required
          maxLength={255}
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      <Field label="Юридическое название">
        <Input
          aria-label="Юридическое название"
          value={legal}
          required
          maxLength={255}
          onChange={(e) => setLegal(e.target.value)}
        />
      </Field>
      <Field label="БИН организации">
        <Input
          aria-label="БИН организации"
          value={bin}
          pattern="[0-9]{12}"
          maxLength={12}
          onChange={(e) => setBin(e.target.value)}
        />
      </Field>
      <label className="flex gap-2 text-sm">
        <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
        Организация активна
      </label>
      <Button type="submit" disabled={busy}>
        Сохранить организацию
      </Button>
    </form>
  )
}
function BranchForm({
  organizations,
  busy,
  save,
}: {
  organizations: Organization[]
  busy: boolean
  save: (work: () => Promise<unknown>) => Promise<void>
}) {
  const [organizationId, setOrganizationId] = useState('')
  const [form, setForm] = useState({ code: '', name: '', address: '', city: '' })
  return (
    <form
      className="mt-5 space-y-3 rounded-xl bg-paper-input p-4"
      onSubmit={(e) => {
        e.preventDefault()
        void save(() =>
          inventoryEditorApi.createBranch({
            ...form,
            organizationId,
            division: null,
            latitude: null,
            longitude: null,
            active: true,
          }),
        )
      }}
    >
      <h3 className="font-bold">Новая аптека мерча</h3>
      <Field label="Организация новой аптеки">
        <select
          className="inp"
          aria-label="Организация новой аптеки"
          value={organizationId}
          required
          onChange={(e) => setOrganizationId(e.target.value)}
        >
          <option value="">Выберите организацию</option>
          {organizations
            .filter((item) => item.active)
            .map((item) => (
              <option value={item.id} key={item.id}>
                {item.name}
              </option>
            ))}
        </select>
      </Field>
      <div className="grid gap-3 md:grid-cols-2">
        {(['code', 'name', 'address', 'city'] as const).map((field) => (
          <Field
            label={
              {
                code: 'Код новой аптеки',
                name: 'Название новой аптеки',
                address: 'Адрес новой аптеки',
                city: 'Город новой аптеки',
              }[field]
            }
            key={field}
          >
            <Input
              aria-label={
                {
                  code: 'Код новой аптеки',
                  name: 'Название новой аптеки',
                  address: 'Адрес новой аптеки',
                  city: 'Город новой аптеки',
                }[field]
              }
              value={form[field]}
              required
              onChange={(e) => setForm({ ...form, [field]: e.target.value })}
            />
          </Field>
        ))}
      </div>
      <p className="text-xs text-ink-500">
        Привязка к аптеке ePharm не угадывается по адресу. Она остаётся отдельной проверяемой
        операцией.
      </p>
      <Button type="submit" disabled={busy || !organizationId}>
        Создать аптеку мерча
      </Button>
    </form>
  )
}
