// CreatePromoModal — создание товарной акции: выбор ОДНОГО товара из витрины
// Medusa + диапазон дат + единый бонус фармацевту. Цена товара read-only из
// Medusa (обновляется ежедневно). Опционально — override фото/описания (поверх PIM).
// Бренд берётся из выбранного товара. Многоуровневые пороги убраны (T1).

import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { CreatePromoRequest } from '@/lib/api-types'
import { formatKzt } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { PromoProductPicker, type SelectedProduct } from './PromoProductPicker'

interface CreatePromoModalProps {
  open: boolean
  onClose: () => void
  onCreate: (req: CreatePromoRequest) => void | Promise<void>
  pending?: boolean
}

interface FormState {
  product: SelectedProduct | null
  title: string
  titleTouched: boolean
  dateStart: string
  dateEnd: string
  pharmacistBonus: string
  overrideImage: string
  overrideDescription: string
}

const initial = (): FormState => ({
  product: null,
  title: '',
  titleTouched: false,
  dateStart: '',
  dateEnd: '',
  pharmacistBonus: '0',
  overrideImage: '',
  overrideDescription: '',
})

export function CreatePromoModal({ open, onClose, onCreate, pending }: CreatePromoModalProps) {
  const t = useT()
  const [form, setForm] = useState<FormState>(initial)

  useEffect(() => {
    if (open) setForm(initial())
  }, [open])

  const datesOk = !form.dateStart || !form.dateEnd || form.dateStart <= form.dateEnd
  const valid = form.product !== null && form.title.trim().length > 0 && datesOk

  const submit = () => {
    if (!valid || !form.product) return
    onCreate({
      title: form.title.trim(),
      status: 'draft',
      medusaProductId: form.product.medusaProductId,
      productName: form.product.productName,
      productImage: form.product.productImage,
      brand: form.product.brand,
      pharmacistBonus: Math.max(0, Math.trunc(Number(form.pharmacistBonus) || 0)),
      overrideImage: form.overrideImage.trim() || null,
      overrideDescription: form.overrideDescription.trim() || null,
      dateStart: form.dateStart || null,
      dateEnd: form.dateEnd || null,
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('pm.newCampaign')}
      subtitle={t('pm.modalProductSub')}
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
        <Field label={t('pm.fldProduct')}>
          <PromoProductPicker
            value={form.product}
            onChange={(p) =>
              setForm((f) => ({
                ...f,
                product: p,
                // авто-название по товару, пока пользователь не правил title вручную
                title: f.titleTouched ? f.title : p.productName,
              }))
            }
          />
        </Field>

        {/* Цена из Medusa — read-only (обновляется ежедневно). */}
        {form.product && (
          <Field label={t('pm.fldPrice')} hint={t('pm.priceHint')}>
            <div
              className="inp flex items-center bg-paper-input font-bold text-ink-700"
              data-testid="create-price-readonly"
            >
              {form.product.price == null ? t('sf.priceNa') : formatKzt(form.product.price)}
            </div>
          </Field>
        )}

        <Field label={t('pm.fldTitle')}>
          <Input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value, titleTouched: true })}
            placeholder={t('pm.titlePh')}
          />
        </Field>

        <Field label={t('pm.fldBonus')} hint={t('pm.bonusHint')}>
          <Input
            type="number"
            min={0}
            value={form.pharmacistBonus}
            onChange={(e) => setForm({ ...form, pharmacistBonus: e.target.value })}
          />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label={t('pm.fldDateStart')}>
            <Input
              type="date"
              value={form.dateStart}
              onChange={(e) => setForm({ ...form, dateStart: e.target.value })}
            />
          </Field>
          <Field label={t('pm.fldDateEnd')}>
            <Input
              type="date"
              value={form.dateEnd}
              onChange={(e) => setForm({ ...form, dateEnd: e.target.value })}
            />
          </Field>
        </div>
        {!datesOk && (
          <div className="-mt-1 text-[12px] font-semibold text-accent-danger">
            {t('pm.dateOrderErr')}
          </div>
        )}

        <Field label={t('pm.fldOverrideImage')} hint={t('pm.overrideHint')} optional>
          <Input
            value={form.overrideImage}
            onChange={(e) => setForm({ ...form, overrideImage: e.target.value })}
            placeholder="https://…"
          />
        </Field>
        <Field label={t('pm.fldOverrideDesc')} hint={t('pm.overrideHint')} optional>
          <textarea
            className="inp"
            rows={3}
            value={form.overrideDescription}
            onChange={(e) => setForm({ ...form, overrideDescription: e.target.value })}
          />
        </Field>
      </div>
    </Modal>
  )
}
