// Создание кампании: сначала источник рекомендации, затем её тип и товар,
// который предлагаем. Начальная пара сохраняется вместе с черновиком кампании.

import { useEffect, useMemo, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { CreatePromoRequest, PromoRuleProductRef, PromoTriggerKind } from '@/lib/api-types'
import { formatKzt } from '@/mocks/fixtures'
import { proxyMedia } from '@/lib/media'
import { useStorefrontProduct } from '@/lib/queries/storefront'
import { useT } from '@/i18n'
import { ProductGallery } from './ProductGallery'
import { PromoProductPicker, type SelectedProduct } from './PromoProductPicker'
import { PromoInitialTriggerPicker, type InitialTrigger } from './PromoInitialTriggerPicker'
import { PROMO_RULE_LIMITS } from './promoRulesValidation'

interface CreatePromoModalProps {
  open: boolean
  onClose: () => void
  onCreate: (req: CreatePromoRequest) => void | Promise<void>
  pending?: boolean
}

interface FormState {
  triggerKind: PromoTriggerKind | null
  trigger: InitialTrigger | null
  recommendationType: 'substitution' | 'crosssell' | null
  product: SelectedProduct | null
  title: string
  titleTouched: boolean
  cover: string
  dateStart: string
  dateEnd: string
  pharmacistBonus: string
  barcode: string
  ipartId: string
  overrideImage: string
  overrideDescription: string
}

const initial = (): FormState => ({
  triggerKind: null,
  trigger: null,
  recommendationType: null,
  product: null,
  title: '',
  titleTouched: false,
  // Дефолт — основной брендовый коралл (как шапка/навбар), чтобы у новой
  // кампании сразу была осмысленная обложка.
  cover: '#D97757',
  dateStart: '',
  dateEnd: '',
  pharmacistBonus: '0',
  barcode: '',
  ipartId: '',
  overrideImage: '',
  overrideDescription: '',
})

function initialTriggerRef(trigger: InitialTrigger): PromoRuleProductRef {
  if (trigger.kind === 'product') {
    const product = trigger.product
    return {
      medusaProductId: product.medusaProductId,
      triggerKind: 'product',
      name: product.productName.trim().slice(0, PROMO_RULE_LIMITS.name),
      brand: product.brand.trim().slice(0, PROMO_RULE_LIMITS.brand),
      price: product.price,
      barcode: product.barcode && product.barcode.length <= 32 ? product.barcode : null,
      ipartId: product.ipartId && product.ipartId.length <= PROMO_RULE_LIMITS.ipartId ? product.ipartId : null,
      active: false,
    }
  }
  const label = trigger.option.parentLabel
    ? `${trigger.option.parentLabel} / ${trigger.option.label}`
    : trigger.option.label
  return {
    medusaProductId: '',
    triggerKind: trigger.kind,
    triggerValue: trigger.option.key,
    triggerLabel: label.slice(0, PROMO_RULE_LIMITS.triggerLabel),
    name: label.slice(0, PROMO_RULE_LIMITS.name),
    active: false,
  }
}

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
  const sameProduct =
    form.trigger?.kind === 'product' &&
    form.trigger.product.medusaProductId === form.product?.medusaProductId
  const valid =
    form.trigger !== null &&
    form.recommendationType !== null &&
    form.product !== null &&
    !sameProduct &&
    form.title.trim().length > 0 &&
    datesOk

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
    if (!valid || !form.product || !form.trigger || !form.recommendationType) return
    onCreate({
      title: form.title.trim(),
      status: 'draft',
      initialRecommendation: {
        type: form.recommendationType,
        trigger: initialTriggerRef(form.trigger),
      },
      cover: form.cover,
      medusaProductId: form.product.medusaProductId,
      productName: form.product.productName.trim().slice(0, PROMO_RULE_LIMITS.name),
      productImage:
        form.product.productImage && form.product.productImage.length <= 1024
          ? form.product.productImage
          : null,
      brand: form.product.brand.trim().slice(0, PROMO_RULE_LIMITS.brand),
      barcode: form.barcode.trim() || null,
      ipartId: form.ipartId.trim() || null,
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
      subtitle={t('pm.triggerFirstSub')}
      width={650}
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
      <div className="flex flex-col gap-5">
        <section aria-labelledby="create-promo-trigger-heading" className="space-y-3">
          <div>
            <h3 id="create-promo-trigger-heading" className="text-[14px] font-extrabold text-ink-900">
              {t('pr.triggerType')}
            </h3>
            <p className="mt-1 text-[12px] text-ink-500">{t('pm.triggerFirstHint')}</p>
          </div>
          <PromoInitialTriggerPicker
            kind={form.triggerKind}
            value={form.trigger}
            onKindChange={(kind) => setForm((current) => ({ ...current, triggerKind: kind, trigger: null }))}
            onChange={(trigger) => setForm((current) => ({ ...current, trigger }))}
          />
        </section>

        {form.trigger && (
          <section aria-labelledby="create-promo-offer-heading" className="space-y-3 border-t border-ink-100 pt-4">
            <div>
              <h3 id="create-promo-offer-heading" className="text-[14px] font-extrabold text-ink-900">
                {t('pm.offerSection')}
              </h3>
              <p className="mt-1 text-[12px] text-ink-500">{t('pm.offerHint')}</p>
            </div>
            <fieldset>
              <legend className="mb-2 text-[12px] font-semibold text-ink-700">{t('pm.recommendationType')}</legend>
              <div className="grid grid-cols-2 gap-2">
                {(['substitution', 'crosssell'] as const).map((type) => (
                  <label
                    key={type}
                    className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-[13px] font-semibold ${
                      form.recommendationType === type
                        ? 'border-brand-green-600 bg-brand-green-50 text-ink-900'
                        : 'border-ink-200 bg-paper-card text-ink-700 hover:bg-paper-hover'
                    }`}
                  >
                    <input
                      type="radio"
                      name="initial-recommendation-type"
                      value={type}
                      checked={form.recommendationType === type}
                      onChange={() => setForm((current) => ({ ...current, recommendationType: type }))}
                    />
                    {t(type === 'substitution' ? 'pm.typeReplacement' : 'pm.typeCrossSell')}
                  </label>
                ))}
              </div>
            </fieldset>
            {form.recommendationType && (
              <Field label={t('pm.fldOfferProduct')}>
                <PromoProductPicker
                  value={form.product}
                  requireQuery
                  onChange={(p) =>
                    setForm((current) => ({
                      ...current,
                      product: p,
                      title: current.titleTouched ? current.title : p.productName.slice(0, 255),
                      barcode: p.barcode ?? '',
                      ipartId: p.ipartId && p.ipartId.length <= PROMO_RULE_LIMITS.ipartId ? p.ipartId : '',
                    }))
                  }
                />
              </Field>
            )}
            {form.product && (
              <p className="text-[12px] text-ink-500">
                {t('pm.fldPrice')}: <span className="font-semibold text-ink-700">
                  {form.product.price == null ? t('sf.priceNa') : formatKzt(form.product.price)}
                </span>
              </p>
            )}
            {sameProduct && (
              <p role="alert" className="text-[12px] font-semibold text-accent-danger">
                {t('pm.sameProductError')}
              </p>
            )}
          </section>
        )}

        {form.trigger && form.recommendationType && form.product && (
          <section aria-labelledby="create-promo-details-heading" className="space-y-3 border-t border-ink-100 pt-4">
            <div>
              <h3 id="create-promo-details-heading" className="text-[14px] font-extrabold text-ink-900">
                {t('pm.campaignDetails')}
              </h3>
              <p className="mt-1 text-[12px] text-ink-500">{t('pm.draftReviewHint')}</p>
            </div>

            <Field label={t('pm.fldTitle')}>
              <Input
                value={form.title}
                maxLength={255}
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

            <details className="rounded-lg border border-ink-100 bg-paper-input p-3">
              <summary className="cursor-pointer text-[12px] font-bold text-ink-700">
                {t('pm.additionalSettings')}
              </summary>
              <div className="mt-4 flex flex-col gap-3">
                <div className="grid grid-cols-2 gap-3">
                  <Field label={t('pr.barcode')}>
                    <Input
                      className="num"
                      value={form.barcode}
                      onChange={(e) => setForm({ ...form, barcode: e.target.value })}
                      data-testid="create-barcode"
                    />
                  </Field>
                  <Field label={t('pr.ipartId')}>
                    <Input
                      className="num"
                      value={form.ipartId}
                      maxLength={PROMO_RULE_LIMITS.ipartId}
                      onChange={(e) => setForm({ ...form, ipartId: e.target.value })}
                      data-testid="create-ipart"
                    />
                  </Field>
                </div>

                <Field label={t('pm.fldPrice')} hint={t('pm.priceHint')}>
                  <div
                    className="inp flex items-center bg-paper-input font-bold text-ink-700"
                    data-testid="create-price-readonly"
                  >
                    {form.product.price == null ? t('sf.priceNa') : formatKzt(form.product.price)}
                  </div>
                </Field>

                {galleryImages.length > 0 && (
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

                <Field label={t('pm.fldCover')}>
                  <CoverColorPicker
                    value={form.cover}
                    onChange={(c) => setForm((f) => ({ ...f, cover: c }))}
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
                  <div role="alert" className="-mt-1 text-[12px] font-semibold text-accent-danger">
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
            </details>
          </section>
        )}
      </div>
    </Modal>
  )
}
