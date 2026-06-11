// PromoDetailPage — полная страница кампании /promo/:id.
// Заменяет PromoDetailModal: открывается по клику на карточку, поддерживает
// редактирование полей (PATCH /promo/:id) + статус-действия (пауза/возобновить/
// архив/восстановить). Archived нельзя редактировать (backend → 409), поэтому
// форма read-only + кнопка «Восстановить».

import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Empty, Field, Input, PageHeader, ProgressBar, StatusChip, useToast } from '@/ui'
import { IconArchive, IconArrowUp, IconPause, IconPlay, IconPromo, IconRefresh } from '@/ui/icons'
import { formatKzt } from '@/mocks/fixtures'
import type { PromoStatus, PromoTierDto, UpdatePromoRequest } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { useArchivePromo, usePromo, useRestorePromo, useUpdatePromo } from '@/lib/queries/promo'
import { PromoProductPicker, type SelectedProduct } from './PromoProductPicker'
import { PromoTiersEditor } from './PromoTiersEditor'

interface FormState {
  title: string
  brand: string
  period: string
  budget: string
  kpi: string
  cover: string
  // Товарная акция:
  product: SelectedProduct | null
  dateStart: string
  dateEnd: string
  tiers: PromoTierDto[]
}

const EMPTY_FORM: FormState = {
  title: '',
  brand: '',
  period: '',
  budget: '',
  kpi: '',
  cover: '',
  product: null,
  dateStart: '',
  dateEnd: '',
  tiers: [],
}

function tiersOk(tiers: PromoTierDto[]): boolean {
  for (let i = 1; i < tiers.length; i++) if (tiers[i].minQty <= tiers[i - 1].minQty) return false
  return true
}

function sameTiers(a: PromoTierDto[], b: PromoTierDto[]): boolean {
  if (a.length !== b.length) return false
  return a.every(
    (t, i) => t.minQty === b[i].minQty && t.price === b[i].price && t.bonus === b[i].bonus,
  )
}

export default function PromoDetailPage() {
  const t = useT()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()

  const { data: promo, isLoading, isError, error, refetch } = usePromo(id || null)
  const updatePromo = useUpdatePromo()
  const archivePromo = useArchivePromo()
  const restorePromo = useRestorePromo()

  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [saveErr, setSaveErr] = useState<string | null>(null)

  // Синхронизируем форму при загрузке/смене кампании.
  useEffect(() => {
    if (promo) {
      setForm({
        title: promo.title,
        brand: promo.brand,
        period: promo.period,
        budget: String(promo.budget),
        kpi: promo.kpi,
        cover: promo.cover,
        product: promo.medusaProductId
          ? {
              medusaProductId: promo.medusaProductId,
              productName: promo.productName,
              productImage: promo.productImage,
              brand: promo.brand,
            }
          : null,
        dateStart: promo.dateStart ?? '',
        dateEnd: promo.dateEnd ?? '',
        tiers: promo.tiers,
      })
      setSaveErr(null)
    }
  }, [promo])

  const goBack = () => navigate('/promo')

  if (isLoading && !promo) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('pd.loading')}
          body={t('common.connecting')}
          icon={<IconPromo size={26} />}
        />
      </Shell>
    )
  }

  if (isError && !promo) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('pd.errTitle')}
          body={describeError(error)}
          icon={<IconPromo size={26} />}
          action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
        />
      </Shell>
    )
  }

  if (!promo) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('pd.notFound')}
          body={t('pd.notFoundBody')}
          icon={<IconPromo size={26} />}
          action={<Button onClick={goBack}>{t('pd.toList')}</Button>}
        />
      </Shell>
    )
  }

  const isArchived = promo.status === 'archived'
  const isActive = promo.status === 'active'
  const pending = updatePromo.isPending || archivePromo.isPending || restorePromo.isPending
  const cover = form.cover || promo.cover || '#5A6173'
  const progressMax = Math.max(promo.budget, 1)
  const progressPct = Math.round((promo.spent / progressMax) * 100)

  const dirty =
    form.title !== promo.title ||
    form.brand !== promo.brand ||
    form.period !== promo.period ||
    form.budget !== String(promo.budget) ||
    form.kpi !== promo.kpi ||
    form.cover !== promo.cover ||
    (form.product?.medusaProductId ?? null) !== promo.medusaProductId ||
    form.dateStart !== (promo.dateStart ?? '') ||
    form.dateEnd !== (promo.dateEnd ?? '') ||
    !sameTiers(form.tiers, promo.tiers)

  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }))

  const handleSave = () => {
    if (!form.title.trim()) {
      setSaveErr(t('pd.errTitleReq'))
      return
    }
    if (!tiersOk(form.tiers)) {
      setSaveErr('Пороги акции должны идти по возрастанию количества.')
      return
    }
    if (form.dateStart && form.dateEnd && form.dateStart > form.dateEnd) {
      setSaveErr('Дата окончания акции раньше даты начала.')
      return
    }
    const patch: UpdatePromoRequest = {
      title: form.title.trim(),
      brand: form.brand.trim(),
      period: form.period.trim(),
      budget: Number(form.budget) || 0,
      kpi: form.kpi.trim(),
      cover: form.cover.trim(),
      medusaProductId: form.product?.medusaProductId ?? null,
      productName: form.product?.productName ?? '',
      productImage: form.product?.productImage ?? null,
      dateStart: form.dateStart || null,
      dateEnd: form.dateEnd || null,
      tiers: form.tiers,
    }
    updatePromo.mutate(
      { id: promo.id, patch },
      {
        onSuccess: () => toast.push(t('pd.saved')),
        onError: (e) => setSaveErr(describeError(e)),
      },
    )
  }

  const handleToggle = () => {
    const next: PromoStatus = isActive ? 'paused' : 'active'
    updatePromo.mutate(
      { id: promo.id, patch: { status: next } },
      {
        onSuccess: () => toast.push(next === 'active' ? t('pd.resumedToast') : t('pd.pausedToast')),
        onError: (e) => toast.push(describeError(e)),
      },
    )
  }

  const handleArchive = () => {
    if (!confirm(t('pd.confirmArchive', { title: promo.title }))) return
    archivePromo.mutate(promo.id, {
      onSuccess: () => {
        toast.push(t('pd.archivedToast'))
        goBack()
      },
      onError: (e) => toast.push(describeError(e)),
    })
  }

  const handleRestore = () => {
    restorePromo.mutate(promo.id, {
      onSuccess: () => toast.push(t('pd.restoredToast')),
      onError: (e) => toast.push(describeError(e)),
    })
  }

  return (
    <Shell
      onBack={goBack}
      actions={
        isArchived ? (
          <Button
            variant="primary"
            leading={<IconRefresh size={14} />}
            disabled={pending}
            onClick={handleRestore}
          >
            {restorePromo.isPending ? t('pd.restoring') : t('pd.restore')}
          </Button>
        ) : (
          <>
            <Button
              variant="outline"
              leading={isActive ? <IconPause size={14} /> : <IconPlay size={14} />}
              disabled={pending}
              onClick={handleToggle}
            >
              {isActive ? t('pd.pause') : t('pd.resume')}
            </Button>
            <Button
              variant="danger"
              leading={<IconArchive size={14} />}
              disabled={pending}
              onClick={handleArchive}
            >
              {t('pd.archive')}
            </Button>
          </>
        )
      }
    >
      <div className="grid grid-cols-3 gap-5" data-testid="promo-detail">
        {/* Левая колонка: редактор */}
        <div className="col-span-2 flex flex-col gap-4">
          {saveErr && (
            <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
              {saveErr}
            </div>
          )}

          {isArchived && (
            <div className="card-soft border-accent-warning/30 bg-accent-warning/5 p-3 text-[12px] text-ink-700">
              {t('pd.archivedBanner')}
            </div>
          )}

          {/* Товарная акция: товар Medusa + даты + ценовые пороги */}
          <div className="card flex flex-col gap-4 p-5">
            <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
              Акция — товар, даты, пороги
            </div>
            <Field label="Товар (из витрины Medusa)">
              {isArchived ? (
                <div className="rounded-xl border border-ink-100 bg-paper-card px-3 py-2 text-[14px] font-bold text-ink-700">
                  {form.product?.productName || '— товар не привязан —'}
                </div>
              ) : (
                <PromoProductPicker
                  value={form.product}
                  onChange={(p) =>
                    setForm((f) => ({ ...f, product: p, brand: p.brand || f.brand }))
                  }
                />
              )}
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Начало акции">
                <Input
                  type="date"
                  value={form.dateStart}
                  onChange={set('dateStart')}
                  disabled={isArchived}
                />
              </Field>
              <Field label="Окончание акции">
                <Input
                  type="date"
                  value={form.dateEnd}
                  onChange={set('dateEnd')}
                  disabled={isArchived}
                />
              </Field>
            </div>
            <Field label="Ценовые пороги (цена и бонус по количеству)">
              <PromoTiersEditor
                value={form.tiers}
                onChange={(tiers) => setForm((f) => ({ ...f, tiers }))}
              />
            </Field>
          </div>

          <div className="card flex flex-col gap-4 p-5">
            <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
              {t('pd.mainData')}
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Field label={t('pd.fldTitle')}>
                <Input value={form.title} onChange={set('title')} disabled={isArchived} />
              </Field>
              <Field label={t('pd.fldBrand')}>
                <Input value={form.brand} onChange={set('brand')} disabled={isArchived} />
              </Field>
              <Field label={t('pd.fldPeriod')}>
                <Input
                  value={form.period}
                  onChange={set('period')}
                  placeholder={t('pd.periodPh')}
                  disabled={isArchived}
                />
              </Field>
              <Field label={t('pd.fldBudget')}>
                <Input
                  type="number"
                  min={0}
                  value={form.budget}
                  onChange={set('budget')}
                  disabled={isArchived}
                />
              </Field>
              <Field label={t('pd.fldKpi')} optional>
                <Input
                  value={form.kpi}
                  onChange={set('kpi')}
                  placeholder={t('pd.kpiPh')}
                  disabled={isArchived}
                />
              </Field>
              <Field label={t('pd.fldCover')} optional>
                <Input
                  value={form.cover}
                  onChange={set('cover')}
                  placeholder={t('pd.coverPh')}
                  disabled={isArchived}
                />
              </Field>
            </div>
            {!isArchived && (
              <div className="hairline flex items-center justify-end gap-2 border-t pt-3">
                <Button variant="ghost" onClick={goBack}>
                  {t('common.cancel')}
                </Button>
                <Button
                  variant="primary"
                  disabled={!dirty || updatePromo.isPending}
                  onClick={handleSave}
                >
                  {updatePromo.isPending ? t('pm.creating') : t('common.save')}
                </Button>
              </div>
            )}
          </div>
        </div>

        {/* Правая колонка: превью + бюджет + мета */}
        <div className="flex flex-col gap-4">
          <div
            className="relative h-36 overflow-hidden rounded-xl"
            data-testid="promo-detail-cover"
            style={{ background: `linear-gradient(135deg, ${cover}, ${cover}cc)` }}
          >
            <div className="absolute right-3 top-3">
              <StatusChip status={promo.status} />
            </div>
            <div className="absolute bottom-4 left-5 right-5 text-white">
              <div className="mb-1 text-[11px] font-bold uppercase tracking-[0.08em] opacity-85">
                {form.brand || '—'}
              </div>
              <div className="text-[19px] font-extrabold leading-tight">{form.title || '—'}</div>
            </div>
          </div>

          <div className="card-soft p-4">
            <div className="mb-2 flex items-center justify-between text-[12px] font-bold uppercase tracking-[0.06em] text-ink-500">
              <span>{t('pd.budgetSpent')}</span>
              <span className="num text-ink-900">{progressPct}%</span>
            </div>
            <div className="mb-2 flex items-center justify-between text-[13px]">
              <span className="num font-extrabold text-ink-900">{formatKzt(promo.spent)}</span>
              <span className="num font-semibold text-ink-500">/ {formatKzt(promo.budget)}</span>
            </div>
            <ProgressBar value={promo.spent} max={progressMax} />
          </div>

          <div className="card-soft grid grid-cols-2 gap-3 p-4 text-[13px]">
            <Meta label={t('pd.metaPharm')} value={`${promo.pharmacies}`} />
            <Meta label={t('pd.metaCreated')} value={promo.createdAt.slice(0, 10)} />
            <Meta label={t('pd.metaUpdated')} value={promo.updatedAt.slice(0, 10)} />
            <Meta label={t('pd.metaId')} value={promo.id} mono />
          </div>
        </div>
      </div>
    </Shell>
  )
}

function Shell({
  children,
  onBack,
  actions,
}: {
  children: React.ReactNode
  onBack: () => void
  actions?: React.ReactNode
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-4">
      <button
        type="button"
        onClick={onBack}
        className="flex w-fit items-center gap-1 text-[13px] font-bold text-ink-500 hover:text-brand-green-700"
      >
        <IconArrowUp size={14} className="-rotate-90" /> {t('promoDetail.back')}
      </button>
      <PageHeader
        title={t('page.promoDetail.title')}
        subtitle={t('page.promoDetail.subtitle')}
        actions={actions}
      />
      {children}
    </div>
  )
}

function Meta({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <div className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">{label}</div>
      <div className={`mt-0.5 font-extrabold text-ink-900 ${mono ? 'num' : ''}`}>{value}</div>
    </div>
  )
}
