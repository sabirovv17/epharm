// CreatePromoModal — создание товарной акции: выбор товара из витрины Medusa +
// диапазон дат + ценовые пороги с бонусом. Бренд берётся из выбранного товара.

import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { CreatePromoRequest, PromoTierDto } from '@/lib/api-types'
import { useT } from '@/i18n'
import { PromoProductPicker, type SelectedProduct } from './PromoProductPicker'
import { PromoTiersEditor } from './PromoTiersEditor'

interface CreatePromoModalProps {
  open: boolean
  onClose: () => void
  onCreate: (req: CreatePromoRequest) => void | Promise<void>
  pending?: boolean
}

interface FormState {
  product: SelectedProduct | null
  title: string
  dateStart: string
  dateEnd: string
  tiers: PromoTierDto[]
}

const initial = (): FormState => ({
  product: null,
  title: '',
  dateStart: '',
  dateEnd: '',
  tiers: [{ minQty: 1, price: 0, bonus: 0 }],
})

function tiersOk(tiers: PromoTierDto[]): boolean {
  for (let i = 1; i < tiers.length; i++) if (tiers[i].minQty <= tiers[i - 1].minQty) return false
  return true
}

export function CreatePromoModal({ open, onClose, onCreate, pending }: CreatePromoModalProps) {
  const t = useT()
  const [form, setForm] = useState<FormState>(initial)

  useEffect(() => {
    if (open) setForm(initial())
  }, [open])

  const datesOk = !form.dateStart || !form.dateEnd || form.dateStart <= form.dateEnd
  const valid =
    form.product !== null && form.title.trim().length > 0 && tiersOk(form.tiers) && datesOk

  const submit = () => {
    if (!valid || !form.product) return
    onCreate({
      title: form.title.trim(),
      status: 'draft',
      medusaProductId: form.product.medusaProductId,
      productName: form.product.productName,
      productImage: form.product.productImage,
      brand: form.product.brand,
      dateStart: form.dateStart || null,
      dateEnd: form.dateEnd || null,
      tiers: form.tiers,
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Новая акция"
      subtitle="Выберите товар из витрины и задайте цены/бонусы по количеству"
      width={580}
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
            {pending ? t('pm.creating') : t('pm.createDraft')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label="Товар (из витрины Medusa)">
          <PromoProductPicker
            value={form.product}
            onChange={(p) =>
              setForm((f) => ({ ...f, product: p, title: f.title || p.productName }))
            }
          />
        </Field>

        <Field label="Название акции">
          <Input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder="напр. Двойной бонус на Панкраген"
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Начало">
            <Input
              type="date"
              value={form.dateStart}
              onChange={(e) => setForm({ ...form, dateStart: e.target.value })}
            />
          </Field>
          <Field label="Окончание">
            <Input
              type="date"
              value={form.dateEnd}
              onChange={(e) => setForm({ ...form, dateEnd: e.target.value })}
            />
          </Field>
        </div>
        {!datesOk && (
          <div className="-mt-1 text-[12px] font-semibold text-accent-danger">
            Дата окончания раньше начала.
          </div>
        )}

        <Field label="Ценовые пороги (цена и бонус по количеству)">
          <PromoTiersEditor value={form.tiers} onChange={(tiers) => setForm({ ...form, tiers })} />
        </Field>
      </div>
    </Modal>
  )
}
