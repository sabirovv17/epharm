import { useMemo, useState } from 'react'
import { Button, Field, Modal, Select } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { PharmacistDto, PharmacyDto } from '@/lib/api-types'
import { useT } from '@/i18n'

interface ActivatePharmacistModalProps {
  pharmacist: PharmacistDto
  pharmacies: PharmacyDto[]
  pending: boolean
  onClose: () => void
  onActivate: (pharmacyId: string) => void
}

export function ActivatePharmacistModal({
  pharmacist,
  pharmacies,
  pending,
  onClose,
  onActivate,
}: ActivatePharmacistModalProps) {
  const t = useT()
  const [pharmacyId, setPharmacyId] = useState('')
  const options = useMemo(
    () =>
      pharmacies
        .filter((pharmacy) => pharmacy.active)
        .sort((a, b) => `${a.city} ${a.name}`.localeCompare(`${b.city} ${b.name}`, 'ru'))
        .map((pharmacy) => ({
          value: pharmacy.id,
          label: `${pharmacy.city} · ${pharmacy.name}`,
        })),
    [pharmacies],
  )

  return (
    <Modal
      open
      onClose={pending ? () => undefined : onClose}
      title={t('phc.activateTitle')}
      subtitle={t('phc.activateSubtitle')}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={pending}>
            {t('common.cancel')}
          </Button>
          <Button
            variant="primary"
            onClick={() => onActivate(pharmacyId)}
            disabled={!pharmacyId || pending}
            leading={<IconCheck size={14} />}
          >
            {pending ? t('phc.activating') : t('phc.activate')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div>
          <div className="text-[14px] font-extrabold text-ink-900">{pharmacist.name}</div>
          <div className="mt-1 text-[12px] text-ink-500">
            {pharmacist.phone} · {pharmacist.iin}
          </div>
        </div>
        <Field label={t('phc.selectPharmacy')}>
          <Select
            value={pharmacyId || undefined}
            onChange={setPharmacyId}
            options={options}
            placeholder={t('phc.selectPharmacyPlaceholder')}
          />
        </Field>
        {options.length === 0 && (
          <div className="rounded-lg bg-accent-warning/10 px-3 py-2 text-[12px] font-semibold text-ink-700">
            {t('phc.noActivePharmacies')}
          </div>
        )}
      </div>
    </Modal>
  )
}
