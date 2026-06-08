// CreatePharmacyModal — модалка создания аптеки (Блок 2). Минимум полей:
// name + chain (select из существующих) + city + district + addr + group.
// Сеть обязательна (backend валидирует существование chainId → 400).

import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { ChainDto, CreatePharmacyRequest, PharmacyGroup } from '@/lib/api-types'
import { useT } from '@/i18n'

interface CreatePharmacyModalProps {
  open: boolean
  onClose: () => void
  onCreate: (req: CreatePharmacyRequest) => void | Promise<void>
  chains: ChainDto[]
  pending?: boolean
}

interface FormState {
  name: string
  chainId: string
  city: string
  district: string
  addr: string
  group: PharmacyGroup
}

const initial = (): FormState => ({
  name: '',
  chainId: '',
  city: '',
  district: '',
  addr: '',
  group: 'pilot',
})

export function CreatePharmacyModal({
  open,
  onClose,
  onCreate,
  chains,
  pending,
}: CreatePharmacyModalProps) {
  const t = useT()
  const [form, setForm] = useState<FormState>(initial)

  useEffect(() => {
    if (open) setForm(initial())
  }, [open])

  const valid =
    form.name.trim().length > 0 && form.chainId.length > 0 && form.city.trim().length > 0

  const set = (patch: Partial<FormState>) => setForm((f) => ({ ...f, ...patch }))

  const submit = () => {
    if (!valid) return
    onCreate({
      name: form.name.trim(),
      chainId: form.chainId,
      city: form.city.trim(),
      district: form.district.trim(),
      addr: form.addr.trim(),
      group: form.group,
    })
  }

  const chainOptions = chains.map((c) => ({ value: c.id, label: c.name }))
  const groupOptions = [
    { value: 'pilot', label: t('ph.tabPilot') },
    { value: 'control', label: t('ph.tabControl') },
    { value: 'rolled', label: t('ph.tabRolled') },
  ]

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('phf.new')}
      subtitle={t('phf.modalSub')}
      width={560}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="primary"
            disabled={!valid || pending}
            onClick={submit}
            leading={<IconCheck size={14} />}
          >
            {pending ? t('phf.creating') : t('phf.create')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {chains.length === 0 && (
          <div className="rounded-lg bg-accent-warning/10 px-3 py-2 text-[12px] font-semibold text-ink-700">
            {t('phf.noChains')}
          </div>
        )}
        <Field label={t('phf.fldName')}>
          <Input
            value={form.name}
            onChange={(e) => set({ name: e.target.value })}
            placeholder={t('phf.namePh')}
            autoFocus
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('phf.fldChain')}>
            <Select
              value={form.chainId || undefined}
              onChange={(v) => set({ chainId: v })}
              options={chainOptions}
              placeholder={t('phf.chainPh')}
            />
          </Field>
          <Field label={t('phf.fldGroup')}>
            <Select
              value={form.group}
              onChange={(v) => set({ group: v as PharmacyGroup })}
              options={groupOptions}
            />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('phf.fldCity')}>
            <Input
              value={form.city}
              onChange={(e) => set({ city: e.target.value })}
              placeholder={t('phf.cityPh')}
            />
          </Field>
          <Field label={t('phf.fldDistrict')} optional>
            <Input
              value={form.district}
              onChange={(e) => set({ district: e.target.value })}
              placeholder={t('phf.districtPh')}
            />
          </Field>
        </div>
        <Field label={t('phf.fldAddr')} optional>
          <Input
            value={form.addr}
            onChange={(e) => set({ addr: e.target.value })}
            placeholder={t('phf.addrPh')}
          />
        </Field>
      </div>
    </Modal>
  )
}
