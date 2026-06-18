// CreatePromoModal — создание товарной акции: выбор ОДНОГО товара из витрины
// Medusa + диапазон дат + единый бонус фармацевту. Цена товара read-only из
// Medusa (обновляется ежедневно). Опционально — override фото/описания (поверх PIM).
// Бренд берётся из выбранного товара. Многоуровневые пороги убраны (T1).

import { useEffect, useMemo, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { CreatePromoRequest } from '@/lib/api-types'
import { formatKzt } from '@/mocks/fixtures'
import { proxyMedia } from '@/lib/media'
import { useStorefrontProduct } from '@/lib/queries/storefront'
import { useT } from '@/i18n'
import { ProductGallery } from './ProductGallery'
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
  cover: string
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
  // Дефолт — основной брендовый коралл (как шапка/навбар), чтобы у новой
  // кампании сразу была осмысленная обложка.
  cover: '#D97757',
  dateStart: '',
  dateEnd: '',
  pharmacistBonus: '0',
  overrideImage: '',
  overrideDescription: '',
})

/// Пресеты цвета обложки (палитра Claude orange + акценты). Источник — design-tokens.
/// Бренд-стопы разведены по коралловой шкале (узкая дельта, плоско), amber/red/ink — без изменений.
const COVER_PRESETS = [
  '#D97757', // коралл 600 (PRIMARY)
  '#BE5A38', // коралл 700
  '#E0916B', // коралл 400
  '#9A4427', // коралл 800
  '#E4A485', // коралл 300
  '#F4B73A', // amber
  '#E5484D', // red
  '#6F665B', // тёплый ink 500
]

/// Выбор цвета обложки: пресеты-кружки + нативный пикер «свой цвет». Активный
/// пресет — кольцо + галочка. Значение — hex (#RRGGBB), как ждёт backend (cover).
function CoverColorPicker({ value, onChange }: { value: string; onChange: (c: string) => void }) {
  const norm = value.trim().toLowerCase()
  return (
    <div className="flex flex-wrap items-center gap-2">
      {COVER_PRESETS.map((c) => {
        const active = norm === c.toLowerCase()
        return (
          <button
            key={c}
            type="button"
            aria-label={c}
            aria-pressed={active}
            onClick={() => onChange(c)}
            className={`flex h-8 w-8 items-center justify-center rounded-full transition ${
              active ? 'ring-2 ring-ink-900 ring-offset-2' : 'ring-1 ring-black/10'
            }`}
            style={{ backgroundColor: c }}
          >
            {active && <IconCheck size={14} className="text-white" />}
          </button>
        )
      })}
      {/* Свой цвет — нативный color-picker (swatch показывает текущее значение). */}
      <input
        type="color"
        value={/^#[0-9a-fA-F]{6}$/.test(value) ? value : '#D97757'}
        onChange={(e) => onChange(e.target.value)}
        className="h-8 w-8 cursor-pointer rounded-full border border-black/10 bg-transparent p-0"
        aria-label="Свой цвет обложки"
        title="Свой цвет"
      />
    </div>
  )
}

export function CreatePromoModal({ open, onClose, onCreate, pending }: CreatePromoModalProps) {
  const t = useT()
  const [form, setForm] = useState<FormState>(initial)

  useEffect(() => {
    if (open) setForm(initial())
  }, [open])

  const datesOk = !form.dateStart || !form.dateEnd || form.dateStart <= form.dateEnd
  const valid = form.product !== null && form.title.trim().length > 0 && datesOk

  // Деталь выбранного товара витрины — источник фото Medusa для галереи (выбор
  // обложки прямо при создании). Хук вызываем безусловно (id=null → disabled).
  const { data: detail } = useStorefrontProduct(form.product?.medusaProductId ?? null)
  // Исходные URL (как в БД). Снимок товара + фото Medusa + «своё фото» в конце.
  const galleryImages = useMemo(() => {
    const out: string[] = []
    const push = (u?: string | null) => {
      const v = u?.trim()
      if (v && !out.includes(v)) out.push(v)
    }
    push(form.product?.productImage)
    push(detail?.imageUrl)
    ;(detail?.images ?? []).forEach(push)
    push(form.overrideImage)
    return out
  }, [form.product?.productImage, form.overrideImage, detail])
  const effectiveCover =
    form.overrideImage.trim() ||
    form.product?.productImage ||
    detail?.imageUrl ||
    galleryImages[0] ||
    null

  const submit = () => {
    if (!valid || !form.product) return
    onCreate({
      title: form.title.trim(),
      status: 'draft',
      cover: form.cover,
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

        {/* Галерея фото Medusa — выбрать обложку сразу при создании кампании. */}
        {form.product && galleryImages.length > 0 && (
          <Field label={t('pm.galleryTitle')} hint={t('pm.galleryPickHint')}>
            <ProductGallery
              key={galleryImages.join('|')}
              images={galleryImages}
              effective={effectiveCover}
              resolveSrc={proxyMedia}
              onPickCover={(u) => setForm((f) => ({ ...f, overrideImage: u }))}
            />
          </Field>
        )}

        <Field label={t('pm.fldTitle')}>
          <Input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value, titleTouched: true })}
            placeholder={t('pm.titlePh')}
          />
        </Field>

        <Field label={t('pm.fldCover')}>
          <CoverColorPicker
            value={form.cover}
            onChange={(c) => setForm((f) => ({ ...f, cover: c }))}
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
