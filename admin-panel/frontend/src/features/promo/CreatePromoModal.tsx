// CreatePromoModal — простое модальное окно создания. Один шаг (форма + кнопка).
// На MVP минимум полей: title, brand, period, budget. KPI и cover добавятся позже.

import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal } from '@/ui'
import { IconCheck } from '@/ui/icons'
import type { CreatePromoRequest } from '@/lib/api-types'
import { useT } from '@/i18n'

interface CreatePromoModalProps {
  open: boolean
  onClose: () => void
  onCreate: (req: CreatePromoRequest) => void | Promise<void>
  pending?: boolean
}

interface FormState {
  title: string
  brand: string
  period: string
  budget: string
  kpi: string
  cover: string
}

const initial = (): FormState => ({
  title: '',
  brand: '',
  period: '',
  budget: '',
  kpi: '',
  cover: '#16C97A',
})

export function CreatePromoModal({ open, onClose, onCreate, pending }: CreatePromoModalProps) {
  const t = useT()
  const [form, setForm] = useState<FormState>(initial)

  useEffect(() => {
    if (open) setForm(initial())
  }, [open])

  const valid = form.title.trim().length > 0 && form.brand.trim().length > 0

  const submit = () => {
    if (!valid) return
    onCreate({
      title: form.title.trim(),
      brand: form.brand.trim(),
      status: 'draft',
      period: form.period.trim(),
      budget: form.budget ? Number(form.budget) || 0 : 0,
      kpi: form.kpi.trim(),
      cover: form.cover.trim(),
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('pm.newCampaign')}
      subtitle={t('pm.modalSub')}
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
            {pending ? t('pm.creating') : t('pm.createDraft')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {/* Live preview: показывает как будет выглядеть cover карточки.
            Обновляется при наборе title / brand / cover hex. Это позволяет
            юзеру сразу понять, что текст лендит внутрь цветного блока. */}
        <PromoCoverPreview title={form.title} brand={form.brand} cover={form.cover} />

        <Field label={t('pm.fldTitle')}>
          <Input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder={t('pm.titlePh')}
            autoFocus
          />
        </Field>
        <Field label={t('pm.fldBrand')}>
          <Input
            value={form.brand}
            onChange={(e) => setForm({ ...form, brand: e.target.value })}
            placeholder={t('pm.brandPh')}
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('pm.fldPeriod')}>
            <Input
              value={form.period}
              onChange={(e) => setForm({ ...form, period: e.target.value })}
              placeholder={t('pm.periodPh')}
            />
          </Field>
          <Field label={t('pm.fldBudget')}>
            <Input
              type="number"
              value={form.budget}
              onChange={(e) => setForm({ ...form, budget: e.target.value })}
              placeholder={t('pm.budgetPh')}
            />
          </Field>
        </div>
        <Field label={t('pm.fldKpi')}>
          <Input
            value={form.kpi}
            onChange={(e) => setForm({ ...form, kpi: e.target.value })}
            placeholder={t('pm.kpiPh')}
          />
        </Field>
        <Field label={t('pm.fldCover')} hint={t('pm.coverHint')}>
          <Input
            value={form.cover}
            onChange={(e) => setForm({ ...form, cover: e.target.value })}
            placeholder="#16C97A"
          />
        </Field>
      </div>
    </Modal>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// PromoCoverPreview — live превью cover-блока. Sanitize'ит hex чтобы
// CSS не сломался на полуготовом инпуте («#16» / «не hex»).
// ─────────────────────────────────────────────────────────────────────────

function sanitizeHex(raw: string): string {
  const v = raw.trim()
  // Принимаем #RGB / #RRGGBB. Иначе fallback на neutral grey.
  if (/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v)) return v
  return '#5A6173'
}

interface PromoCoverPreviewProps {
  title: string
  brand: string
  cover: string
}

function PromoCoverPreview({ title, brand, cover }: PromoCoverPreviewProps) {
  const t = useT()
  const color = sanitizeHex(cover)
  const displayTitle = title.trim() || t('pm.previewTitle')
  const displayBrand = brand.trim() || t('pm.previewBrand')
  return (
    <div
      data-testid="promo-create-preview"
      className="relative h-28 overflow-hidden rounded-xl"
      style={{ background: `linear-gradient(135deg, ${color}, ${color}cc)` }}
    >
      <div className="absolute bottom-4 left-5 right-5 text-white">
        <div className="mb-1 text-[11px] font-bold uppercase tracking-[0.08em] opacity-85">
          {displayBrand}
        </div>
        <div className="text-[18px] font-extrabold leading-tight">{displayTitle}</div>
      </div>
      <div className="absolute right-3 top-3 rounded-full bg-white/85 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.06em] text-ink-700">
        {t('pm.preview')}
      </div>
    </div>
  )
}
