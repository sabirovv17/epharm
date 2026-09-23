import { useMemo, useState } from 'react'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type {
  CreatePharmacistRequest,
  PharmacistStatus,
  PharmacistTier,
  PharmacyDto,
} from '@/lib/api-types'
import { isValidIin } from '@/lib/iin'

interface Props {
  open: boolean
  pharmacies: PharmacyDto[]
  pending?: boolean
  initialPhone?: string
  error?: string | null
  onClose: () => void
  onCreate: (request: CreatePharmacistRequest) => void
}

interface FormState {
  name: string
  iin: string
  phone: string
  pharmacyId: string
  tier: PharmacistTier
  status: PharmacistStatus
}

const initialForm = (phone: string): FormState => ({
  name: 'Тестовый фармацевт',
  iin: '',
  phone,
  pharmacyId: '',
  tier: 'Silver',
  status: 'pending',
})

function normalizePhone(value: string): string {
  const digits = value.replace(/\D/g, '')
  if (!digits) return ''
  if (digits.length === 11 && (digits.startsWith('7') || digits.startsWith('8'))) {
    return `+7${digits.slice(1)}`
  }
  if (digits.length === 10) return `+7${digits}`
  return value.trim()
}

export function CreatePharmacistModal({
  open,
  pharmacies,
  pending,
  initialPhone = '',
  error,
  onClose,
  onCreate,
}: Props) {
  const [form, setForm] = useState<FormState>(() => initialForm(initialPhone))
  const [attempted, setAttempted] = useState(false)

  const normalizedPhone = useMemo(() => normalizePhone(form.phone), [form.phone])
  const phoneValid = /^\+7\d{10}$/.test(normalizedPhone)
  const iinValid = isValidIin(form.iin)
  const valid =
    form.name.trim().length > 0 &&
    form.name.trim().length <= 255 &&
    iinValid &&
    phoneValid &&
    !!form.pharmacyId

  const set = (patch: Partial<FormState>) => setForm((current) => ({ ...current, ...patch }))
  const submit = () => {
    setAttempted(true)
    if (!valid) return
    onCreate({
      name: form.name.trim(),
      iin: form.iin.trim(),
      phone: normalizedPhone,
      pharmacyId: form.pharmacyId,
      tier: form.tier,
      status: form.status,
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Добавить фармацевта"
      subtitle="Создайте тестовый или рабочий профиль и назначьте аптеку."
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button
            variant="primary"
            disabled={pending}
            onClick={submit}
            leading={<IconCheck size={14} />}
          >
            {pending ? 'Создаём…' : 'Создать фармацевта'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {error && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {error}
          </div>
        )}
        {!pharmacies.length && (
          <div className="rounded-lg bg-accent-warning/10 px-3 py-2 text-[12px] font-semibold text-ink-700">
            Сначала добавьте хотя бы одну аптеку.
          </div>
        )}
        <Field label="ФИО">
          <Input
            value={form.name}
            maxLength={255}
            onChange={(event) => set({ name: event.target.value })}
            placeholder="Имя фармацевта"
            autoFocus
          />
        </Field>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="ИИН">
            <Input
              value={form.iin}
              inputMode="numeric"
              maxLength={12}
              onChange={(event) => set({ iin: event.target.value.replace(/\D/g, '') })}
              placeholder="12 цифр"
            />
            {attempted && !iinValid && (
              <div className="mt-1 text-[11px] font-semibold text-accent-danger">
                Проверьте 12 цифр и контрольную сумму ИИН.
              </div>
            )}
          </Field>
          <Field label="Телефон">
            <Input
              value={form.phone}
              inputMode="tel"
              maxLength={32}
              onChange={(event) => set({ phone: event.target.value })}
              onBlur={() => set({ phone: normalizedPhone })}
              placeholder="+7 747 079 93 53"
            />
            {attempted && !phoneValid && (
              <div className="mt-1 text-[11px] font-semibold text-accent-danger">
                Укажите номер Казахстана в формате +7XXXXXXXXXX.
              </div>
            )}
          </Field>
        </div>
        <Field label="Аптека">
          <Select
            value={form.pharmacyId || undefined}
            onChange={(value) => set({ pharmacyId: value })}
            options={pharmacies
              .filter((pharmacy) => pharmacy.active)
              .map((pharmacy) => ({
                value: pharmacy.id,
                label: `${pharmacy.name} · ${pharmacy.city}`,
              }))}
            placeholder="Выберите аптеку"
          />
        </Field>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="Уровень">
            <Select
              value={form.tier}
              onChange={(value) => set({ tier: value as PharmacistTier })}
              options={['Silver', 'Gold', 'Platinum'].map((value) => ({ value, label: value }))}
            />
          </Field>
          <Field label="Статус">
            <Select
              value={form.status}
              onChange={(value) => set({ status: value as PharmacistStatus })}
              options={[
                { value: 'pending', label: 'Ожидает активации' },
                { value: 'active', label: 'Активен' },
              ]}
            />
          </Field>
        </div>
      </div>
    </Modal>
  )
}
