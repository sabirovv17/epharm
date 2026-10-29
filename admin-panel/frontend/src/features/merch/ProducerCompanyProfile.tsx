import { useCallback, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { ProducerApiError, producerClient, producerError } from './producer-client'
import { usePlacementRead } from './usePlacementRead'

export interface CompanyFields {
  companyName: string | null
  legalName: string | null
  bin: string | null
  legalAddress: string | null
  actualAddress: string | null
  bankName: string | null
  bic: string | null
  iban: string | null
  kbe: string | null
  directorName: string | null
  directorPosition: string | null
  contactPosition: string | null
  contactEmail: string | null
  vatRegistered: boolean
}
export interface CompanyProfile {
  id: string
  revision: number
  profile: CompanyFields
  profileComplete: boolean
}
const fields: { key: Exclude<keyof CompanyFields, 'vatRegistered'>; label: string; max: number }[] =
  [
    { key: 'companyName', label: 'Название компании', max: 1000 },
    { key: 'legalName', label: 'Юридическое название', max: 1000 },
    { key: 'bin', label: 'БИН', max: 12 },
    { key: 'legalAddress', label: 'Юридический адрес', max: 1000 },
    { key: 'actualAddress', label: 'Фактический адрес', max: 1000 },
    { key: 'directorName', label: 'ФИО руководителя', max: 1000 },
    { key: 'directorPosition', label: 'Должность руководителя', max: 1000 },
    { key: 'contactPosition', label: 'Должность контактного лица', max: 1000 },
    { key: 'contactEmail', label: 'Контактный email', max: 1000 },
    { key: 'bankName', label: 'Банк организации', max: 1000 },
    { key: 'bic', label: 'БИК', max: 11 },
    { key: 'iban', label: 'IBAN организации', max: 20 },
    { key: 'kbe', label: 'КБЕ', max: 2 },
  ]

export default function ProducerCompanyProfile({
  onClose,
  onSaved,
}: {
  onClose: () => void
  onSaved: () => void
}) {
  const load = useCallback(
    (signal: AbortSignal) =>
      producerClient.request<CompanyProfile>('/api/producer/profile', { signal }),
    [],
  )
  const read = usePlacementRead(load)
  const [draft, setDraft] = useState<{ base: CompanyProfile; profile: CompanyFields } | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const [pending, setPending] = useState(false)
  const current = read.data && draft?.base === read.data ? draft.profile : read.data?.profile
  const errors: Partial<Record<keyof CompanyFields, string>> = {}
  for (const [key, pattern, message] of [
    ['bin', /^\d{12}$/, 'БИН: 12 цифр.'],
    ['bic', /^[A-Z0-9]{8,11}$/, 'БИК: 8–11 букв или цифр.'],
    ['iban', /^KZ[0-9A-Z]{18}$/, 'IBAN: KZ и ещё 18 букв или цифр.'],
    ['kbe', /^\d{2}$/, 'КБЕ: 2 цифры.'],
    ['contactEmail', /^[^\s@]+@[^\s@]+\.[^\s@]+$/, 'Проверьте email.'],
  ] as const) {
    const value = current?.[key]?.trim()
    if (value && !pattern.test(key === 'bic' || key === 'iban' ? value.toUpperCase() : value))
      errors[key] = message
  }
  const forbidden = failure instanceof ProducerApiError && [401, 403].includes(failure.status)
  const clearFailureOnEdit = () => {
    if (!(failure instanceof ProducerApiError) || failure.status !== 409) setFailure(null)
  }
  const save = async () => {
    if (!current || !read.data || pending || failure || Object.keys(errors).length) return
    setPending(true)
    try {
      await producerClient.request<CompanyProfile>('/api/producer/profile', {
        method: 'PUT',
        body: JSON.stringify({ expectedRevision: read.data.revision, profile: current }),
      })
      onSaved()
    } catch (error) {
      setFailure(error)
    } finally {
      setPending(false)
    }
  }
  const reload = () => {
    setDraft(null)
    setFailure(null)
    read.reload()
  }
  return (
    <Modal
      open
      title="Профиль компании"
      subtitle="Сведения только вашей организации. Название нужно для отправки медиаплана."
      width={900}
      onClose={() => {
        if (!pending) onClose()
      }}
      footer={
        <>
          <Button variant="ghost" disabled={pending} onClick={onClose}>
            Отмена
          </Button>
          <Button
            disabled={
              pending || !current || Object.keys(errors).length > 0 || !!failure || !!read.error
            }
            onClick={save}
          >
            {pending ? 'Сохраняем…' : 'Сохранить профиль'}
          </Button>
        </>
      }
    >
      {read.pending && <p role="status">Загружаем профиль…</p>}
      {!!read.error && <p role="alert">{producerError(read.error)}</p>}
      {!!failure && (
        <div role="alert" className="mb-4 rounded-lg bg-paper-input p-3 text-[13px]">
          <p>{producerError(failure)}</p>
          <p className="mt-1 text-ink-500">
            Повторная загрузка заменит несохранённые поля актуальной версией.
          </p>
          <Button className="mt-2" variant="outline" onClick={reload}>
            Загрузить актуальный профиль
          </Button>
        </div>
      )}
      {current && !forbidden && (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            {fields.map((field) => (
              <Field key={field.key} label={field.label} optional error={errors[field.key]}>
                <Input
                  aria-label={field.label}
                  value={current[field.key] ?? ''}
                  maxLength={field.max}
                  type={field.key === 'contactEmail' ? 'email' : 'text'}
                  disabled={pending}
                  onChange={(event) => {
                    clearFailureOnEdit()
                    if (read.data)
                      setDraft({
                        base: read.data,
                        profile: { ...current, [field.key]: event.target.value || null },
                      })
                  }}
                />
              </Field>
            ))}
          </div>
          <label className="mt-4 flex gap-2 text-[13px]">
            <input
              type="checkbox"
              checked={current.vatRegistered}
              disabled={pending}
              onChange={(event) => {
                clearFailureOnEdit()
                if (read.data)
                  setDraft({
                    base: read.data,
                    profile: { ...current, vatRegistered: event.target.checked },
                  })
              }}
            />
            Плательщик НДС
          </label>
          <p className="mt-4 text-[12px] text-ink-500">
            Название компании необходимо для отправки медиаплана. Пустые реквизиты можно сохранить и
            заполнить позже. Банковские реквизиты здесь — сведения об организации. Оплата и
            банковские переводы в этом кабинете не подключены. Телефон входа этим экраном не
            меняется.
          </p>
        </>
      )}
    </Modal>
  )
}
