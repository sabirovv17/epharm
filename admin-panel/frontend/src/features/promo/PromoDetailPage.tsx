// PromoDetailPage — полная страница кампании /promo/:id.
// Открывается по клику на карточку, поддерживает редактирование полей
// (подготовленный bundle → отдельное применение) + немедленные статус-действия.
// Archived нельзя редактировать (backend → 409), поэтому форма read-only.
//
// T1: один товар Medusa, цена read-only (из Medusa, почасовой рефреш), единый
//     бонус фармацевту, опц. override фото/описания. Порогов больше нет.
// T2: секция «Замены и кросс-селл» — авторинг правил из кампании (см. PromoRulesEditor).

import { useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Button,
  Empty,
  Field,
  Input,
  Modal,
  PageHeader,
  ProgressBar,
  StatusChip,
  useToast,
} from '@/ui'
import { IconArchive, IconArrowUp, IconPause, IconPlay, IconPromo, IconRefresh } from '@/ui/icons'
import { formatKzt } from '@/mocks/fixtures'
import type {
  PromoDto,
  PromoRulesConfigDto,
  PromoStatus,
  UpdatePromoRequest,
} from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { proxyMedia } from '@/lib/media'
import { useT } from '@/i18n'
import { useArchivePromo, usePromo, useRestorePromo, useUpdatePromo } from '@/lib/queries/promo'
import { useStorefrontProduct } from '@/lib/queries/storefront'
import type { SelectedProduct } from './PromoProductPicker'
import { PromoRulesEditor } from './PromoRulesEditor'
import { ProductGallery } from './ProductGallery'
import { collectProductBarcodes } from '@/lib/productBarcodes'
import {
  useApplyPromoChanges,
  usePromoChanges,
  useSavePromoChanges,
} from '@/lib/queries/promoChanges'
import { normalizePromoRulesConfig } from './promoRulesValidation'
import { promoTriggerKind } from './promoTrigger'

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
  pharmacistBonus: string
  barcode: string
  ipartId: string
  overrideImage: string
  overrideDescription: string
  overrideCharacteristics: string
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
  pharmacistBonus: '0',
  barcode: '',
  ipartId: '',
  overrideImage: '',
  overrideDescription: '',
  overrideCharacteristics: '',
}

const EMPTY_RULES: PromoRulesConfigDto = {
  replacements: [],
  crossSells: [],
  script: '',
  advantages: [],
  partnerLabel: null,
  comparison: [],
  goalLabel: null,
  goalTarget: null,
  goalBonus: null,
}

function campaignForm(promo: PromoDto, campaign: UpdatePromoRequest = {}): FormState {
  const editable = { ...promo, ...campaign }
  const barcodes = collectProductBarcodes(editable)
  return {
    title: editable.title ?? '',
    brand: editable.brand ?? '',
    period: editable.period ?? '',
    budget: String(editable.budget ?? 0),
    kpi: editable.kpi ?? '',
    cover: editable.cover ?? '',
    product: promo.medusaProductId
      ? {
          medusaProductId: promo.medusaProductId,
          productName: promo.productName,
          productImage: promo.productImage,
          brand: promo.brand,
          price: promo.price,
          barcode: barcodes[0] ?? null,
          barcodes,
          ipartId: promo.ipartId,
        }
      : null,
    dateStart: editable.dateStart ?? '',
    dateEnd: editable.dateEnd ?? '',
    pharmacistBonus: String(editable.pharmacistBonus ?? 0),
    barcode: barcodes[0] ?? '',
    ipartId: editable.ipartId ?? '',
    overrideImage: editable.overrideImage ?? '',
    overrideDescription: editable.overrideDescription ?? '',
    overrideCharacteristics: editable.overrideCharacteristics ?? '',
  }
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
  const changesQuery = usePromoChanges(id || null)
  const changes =
    changesQuery.isFetchedAfterMount && !changesQuery.isError && changesQuery.data?.promoId === id
      ? changesQuery.data
      : undefined
  const saveChanges = useSavePromoChanges()
  const applyChanges = useApplyPromoChanges()

  const [formDraft, setForm] = useState<FormState>(EMPTY_FORM)
  const form = changesQuery.isError ? (promo ? campaignForm(promo) : EMPTY_FORM) : formDraft
  const [saveErr, setSaveErr] = useState<string | null>(null)
  const [rulesConfig, setRulesConfig] = useState<PromoRulesConfigDto>(EMPTY_RULES)
  const [staleRevision, setStaleRevision] = useState(false)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [resetCounter, setResetCounter] = useState(0)
  const loadedRevision = useRef('')

  // Синхронизируем форму при загрузке/смене кампании.
  useEffect(() => {
    if (changesQuery.isError || (isError && !promo)) {
      loadedRevision.current = ''
      setForm(EMPTY_FORM)
      setReviewOpen(false)
      setRulesConfig(EMPTY_RULES)
    }
    if (promo) {
      const authoritative = changes && changesQuery.isFetchedAfterMount && !changesQuery.isError
      if (!authoritative && loadedRevision.current.startsWith(`${id}:`)) return
      const key = authoritative ? `${id}:${changes.revision}` : `${id}:published`
      if (loadedRevision.current === key) return
      loadedRevision.current = key
      setForm(campaignForm(promo, authoritative ? changes.bundle.campaign : {}))
      setRulesConfig(authoritative ? changes.bundle.rules : EMPTY_RULES)
      setSaveErr(null)
      if (authoritative) setStaleRevision(false)
      setReviewOpen(false)
    }
  }, [
    promo,
    changes,
    id,
    changesQuery.isFetchedAfterMount,
    changesQuery.isError,
    isError,
    resetCounter,
  ])

  // Описание и характеристики товара из Medusa (с уже наложенными override кампании).
  // Показываем их прямо на странице и предзаполняем поля, чтобы можно было перезаписать.
  const medusaId = promo?.medusaProductId ?? null
  const { data: medusaProduct } = useStorefrontProduct(medusaId)
  const medusaCharacteristics = (medusaProduct?.keyFacts ?? []).join('\n')

  // Предзаполняем поля значениями из Medusa, если своих override ещё нет
  // (в форме пусто). Зависимость [medusaProduct] → срабатывает один раз при загрузке.
  useEffect(() => {
    if (!medusaProduct) return
    setForm((f) => ({
      ...f,
      overrideDescription: f.overrideDescription || (medusaProduct.description ?? ''),
      overrideCharacteristics:
        f.overrideCharacteristics || (medusaProduct.keyFacts ?? []).join('\n'),
    }))
  }, [medusaProduct])

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
  const pending =
    updatePromo.isPending ||
    archivePromo.isPending ||
    restorePromo.isPending ||
    saveChanges.isPending ||
    applyChanges.isPending
  const freshChanges =
    !!changes &&
    changesQuery.isFetchedAfterMount &&
    !changesQuery.isFetching &&
    !changesQuery.isError
  const canEdit =
    freshChanges &&
    changes.canSave &&
    !changes.conflict &&
    !staleRevision &&
    !pending &&
    !isArchived
  const editingDisabled = !canEdit
  const editable = { ...promo, ...changes?.bundle.campaign }
  const cover = form.cover || promo.cover || '#6F665B'
  const progressMax = Math.max(promo.budget, 1)
  const progressPct = Math.round((promo.spent / progressMax) * 100)
  const savedBarcodes = collectProductBarcodes(editable)

  const dirty =
    form.title !== editable.title ||
    form.brand !== editable.brand ||
    form.period !== editable.period ||
    form.budget !== String(editable.budget) ||
    form.kpi !== editable.kpi ||
    form.cover !== editable.cover ||
    form.dateStart !== (editable.dateStart ?? '') ||
    form.dateEnd !== (editable.dateEnd ?? '') ||
    form.pharmacistBonus !== String(editable.pharmacistBonus) ||
    form.barcode !== (savedBarcodes[0] ?? '') ||
    form.ipartId !== (editable.ipartId ?? '') ||
    form.overrideImage !== (editable.overrideImage ?? '') ||
    form.overrideDescription !== (editable.overrideDescription ?? '') ||
    form.overrideCharacteristics !== (editable.overrideCharacteristics ?? '')
  const rulesDirty =
    !!changes && JSON.stringify(rulesConfig) !== JSON.stringify(changes.bundle.rules)
  const hasLocalChanges = dirty || rulesDirty
  const mayApply =
    freshChanges &&
    changes.canApply &&
    changes.hasPendingChanges &&
    !changes.conflict &&
    !hasLocalChanges &&
    !staleRevision &&
    !pending &&
    !isArchived

  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }))

  const handleSave = (
    config = rulesConfig,
    callbacks?: { onSuccess: () => void; onError: (error: unknown) => void },
  ) => {
    if (!canEdit || !changes) return
    if (!form.title.trim()) {
      setSaveErr(t('pd.errTitleReq'))
      return
    }
    if (form.dateStart && form.dateEnd && form.dateStart > form.dateEnd) {
      setSaveErr(t('pm.dateOrderErr'))
      return
    }
    // price НЕ отправляем — read-only из Medusa. tiers тоже не шлём (T1).
    // Продвигаемый товар (medusaProductId/productName/productImage) выбирается ТОЛЬКО при
    // создании (1 кампания = 1 товар) и здесь НЕ редактируется — в patch его не кладём.
    //
    // Описание/характеристики предзаполнены из Medusa. Чтобы НЕ «замораживать» товар
    // снимком (и сохранить почасовой рефреш): если значение не меняли и оно совпадает
    // с тем, что отдаёт Medusa, и своего override раньше не было — шлём null (фоллбэк).
    const descVal = form.overrideDescription.trim()
    const hadDescOverride = !!editable.overrideDescription?.trim()
    const baseDesc = (medusaProduct?.description ?? '').trim()
    const overrideDescription = !hadDescOverride && descVal === baseDesc ? null : descVal || null

    const charVal = form.overrideCharacteristics.trim()
    const hadCharOverride = !!editable.overrideCharacteristics?.trim()
    const baseChar = medusaCharacteristics.trim()
    const overrideCharacteristics =
      !hadCharOverride && charVal === baseChar ? null : charVal || null

    const patch: UpdatePromoRequest = {
      ...changes.bundle.campaign,
      title: form.title.trim(),
      brand: form.brand.trim(),
      period: form.period.trim(),
      budget: Number(form.budget) || 0,
      kpi: form.kpi.trim(),
      cover: form.cover.trim(),
      pharmacistBonus: Math.max(0, Math.trunc(Number(form.pharmacistBonus) || 0)),
      barcode: form.barcode.trim() || null,
      barcodes: collectProductBarcodes({ barcode: form.barcode, barcodes: savedBarcodes }),
      ipartId: form.ipartId.trim() || null,
      overrideImage: form.overrideImage.trim() || null,
      overrideDescription,
      overrideCharacteristics,
      dateStart: form.dateStart || null,
      dateEnd: form.dateEnd || null,
    }
    delete patch.status
    saveChanges.mutate(
      {
        id: promo.id,
        expectedRevision: changes.revision,
        bundle: {
          campaign: patch,
          rules: normalizePromoRulesConfig(config, promo.medusaProductId ?? undefined),
        },
      },
      {
        onSuccess: () => {
          setSaveErr(null)
          if (callbacks) callbacks.onSuccess()
          else toast.push('Изменения подготовлены. Опубликованная версия пока не изменилась.')
        },
        onError: (e) => {
          setSaveErr(describeError(e))
          if (axios.isAxiosError(e) && e.response?.status === 409) setStaleRevision(true)
          callbacks?.onError(e)
        },
      },
    )
  }

  const reloadChanges = async () => {
    if (
      hasLocalChanges &&
      !confirm(
        'Загрузить сохранённую версию? Несохранённые поля формы будут потеряны. Подготовленные изменения на сервере сохранятся.',
      )
    )
      return
    const result = await changesQuery.refetch()
    if (result.data && !result.error) {
      loadedRevision.current = ''
      setResetCounter((value) => value + 1)
    }
  }

  const discardChanges = () => {
    if (
      !changes ||
      !changes.canSave ||
      pending ||
      !confirm(
        'Отменить только подготовленные изменения? Опубликованные условия и история сохранятся. Несохранённые поля формы также будут заменены.',
      )
    )
      return
    saveChanges.mutate(
      { id: promo.id, expectedRevision: changes.revision, discard: true },
      {
        onSuccess: () => {
          setSaveErr(null)
          toast.push('Подготовленные изменения отменены. Опубликованная версия сохранена.')
        },
        onError: (e) => {
          setSaveErr(describeError(e))
          if (axios.isAxiosError(e) && e.response?.status === 409) setStaleRevision(true)
        },
      },
    )
  }

  const reviewChanges = () => {
    if (mayApply) setReviewOpen(true)
  }
  const applyPrepared = () => {
    if (!mayApply || !changes) return
    applyChanges.mutate(
      { id: promo.id, expectedRevision: changes.revision },
      {
        onSuccess: () => {
          setReviewOpen(false)
          toast.push(
            promo.status === 'paused'
              ? 'Изменения применены. Кампания остаётся на паузе.'
              : 'Изменения применены. Действующие условия обновлены.',
          )
        },
        onError: (e) => {
          setSaveErr(describeError(e))
          setReviewOpen(false)
          if (axios.isAxiosError(e) && e.response?.status === 409) setStaleRevision(true)
        },
      },
    )
  }

  const handleToggle = () => {
    if (promo.status === 'draft') {
      reviewChanges()
      return
    }
    if (
      promo.status === 'paused' &&
      changes?.hasPendingChanges &&
      !confirm(
        'Возобновить опубликованную версию? Подготовленные изменения будут применены только отдельным действием.',
      )
    )
      return
    const next: PromoStatus = isActive ? 'paused' : 'active'
    updatePromo.mutate(
      { id: promo.id, patch: { status: next } },
      {
        onSuccess: () => {
          toast.push(next === 'active' ? t('pd.resumedToast') : t('pd.pausedToast'))
          setStaleRevision(true)
          if (!hasLocalChanges) void reloadChanges()
        },
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
      onSuccess: () => {
        toast.push(t('pd.restoredToast'))
        setStaleRevision(true)
        if (!hasLocalChanges) void reloadChanges()
      },
      onError: (e) => toast.push(describeError(e)),
    })
  }

  // Галерея фото товара: снимок + все фото из Medusa (в исходном порядке), без
  // дублей. «Своё фото» (overrideImage) добавляем В КОНЕЦ — если это внешняя
  // ссылка, не из Medusa; если совпадает с фото витрины — дедуп оставит его на
  // месте. Так выбор обложки не перетасовывает ленту.
  const galleryImages: string[] = []
  const pushImage = (u?: string | null) => {
    const v = u?.trim()
    if (v && !galleryImages.includes(v)) galleryImages.push(v)
  }
  pushImage(promo.productImage)
  pushImage(medusaProduct?.imageUrl)
  ;(medusaProduct?.images ?? []).forEach(pushImage)
  pushImage(form.overrideImage)
  const effectiveImage =
    form.overrideImage.trim() ||
    promo.productImage ||
    medusaProduct?.imageUrl ||
    galleryImages[0] ||
    null

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
            {changes?.hasPendingChanges && promo.status !== 'draft' && (
              <Button disabled={!mayApply} onClick={reviewChanges}>
                Проверить и применить
              </Button>
            )}
            <Button
              variant={promo.status === 'draft' ? 'primary' : 'outline'}
              leading={isActive ? <IconPause size={14} /> : <IconPlay size={14} />}
              disabled={promo.status === 'draft' ? !mayApply : pending}
              onClick={handleToggle}
            >
              {promo.status === 'draft'
                ? 'Проверить и запустить'
                : isActive
                  ? t('pd.pause')
                  : t('pd.resume')}
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
          <div className="card flex flex-col gap-3 p-4" data-testid="promo-publication-state">
            {(!changesQuery.isFetchedAfterMount || changesQuery.isFetching) && (
              <p role="status">Проверяем сохранённые условия и права…</p>
            )}
            {changesQuery.isError && (
              <p role="alert" className="text-accent-danger">
                Подготовка изменений недоступна: {describeError(changesQuery.error)}. Условия не
                будут опубликованы через старое сохранение.
              </p>
            )}
            {changes && (
              <>
                <div className="flex items-center justify-between gap-3">
                  <strong>
                    {changes.hasPendingChanges
                      ? 'Есть подготовленные изменения'
                      : 'Подготовленных изменений нет'}
                  </strong>
                  <span className="text-[12px] text-ink-500">Ревизия {changes.revision}</span>
                </div>
                <p className="text-[13px] text-ink-500">
                  Сохранение подготавливает условия и правила вместе. На кассах действует
                  опубликованная версия до отдельного применения.
                </p>
                {promo.status === 'draft' && !changes.hasPendingChanges && (
                  <p className="text-[13px]">
                    Перед первым запуском сохраните подготовленные условия и проверьте включённые
                    пары.
                  </p>
                )}
                {promo.status === 'paused' && (
                  <p className="text-[13px]">
                    Кампания на паузе. Применение изменений не возобновляет показы.
                  </p>
                )}
                {!changes.canSave && (
                  <p className="text-[13px] text-ink-500">
                    Изменение условий недоступно с вашим доступом.
                  </p>
                )}
                {hasLocalChanges && (
                  <p className="text-[13px] font-semibold">
                    Есть несохранённые поля. Сначала сохраните изменения; применяются только
                    сохранённые условия.
                  </p>
                )}
                {(changes.conflict || staleRevision) && (
                  <p role="alert" className="text-accent-danger">
                    Условия уже изменились. Обновите сохранённую версию. Если подготовленный
                    черновик конфликтует с опубликованной версией, отмените только подготовленные
                    изменения и проверьте условия заново.
                  </p>
                )}
                {!!changes.readinessIssues.length && (
                  <ul className="list-disc pl-5 text-[12px] text-ink-500">
                    {changes.readinessIssues.map((issue, index) => (
                      <li key={index}>{issue}</li>
                    ))}
                  </ul>
                )}
              </>
            )}
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={pending || changesQuery.isFetching}
                onClick={reloadChanges}
              >
                Загрузить сохранённую версию
              </Button>
              {changes?.hasPendingChanges && changes.canSave && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={pending || staleRevision || !freshChanges}
                  onClick={discardChanges}
                >
                  Отменить подготовленные изменения
                </Button>
              )}
            </div>
          </div>
          {saveErr && (
            <div
              role="alert"
              className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger"
            >
              {saveErr}
            </div>
          )}

          {isArchived && (
            <div className="card-soft border-accent-warning/30 bg-accent-warning/5 p-3 text-[12px] text-ink-700">
              {t('pd.archivedBanner')}
            </div>
          )}

          {/* Товарная акция: товар Medusa + цена (read-only) + бонус + даты + override */}
          <div className="card flex flex-col gap-4 p-5">
            <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
              {t('pd.productSection')}
            </div>
            {/* Продвигаемый товар выбирается ТОЛЬКО при создании кампании (1:1) и здесь
                не меняется — показываем read-only. Замены/кросс-селл к нему — ниже. */}
            <Field label={t('pm.fldProduct')} hint={t('pd.productLocked')}>
              <div
                className="rounded-xl border border-ink-100 bg-paper-card px-3 py-2.5"
                data-testid="detail-product-readonly"
              >
                <div className="text-[14px] font-extrabold text-ink-900">
                  {form.product?.productName || t('pd.noProduct')}
                </div>
                {form.product?.brand && (
                  <div className="text-[12px] font-bold text-ink-500">{form.product.brand}</div>
                )}
                {savedBarcodes.length > 1 && (
                  <div
                    className="num max-h-16 overflow-y-auto break-all text-[11px] text-ink-500"
                    data-testid="detail-barcodes"
                  >
                    {t('pp.barcodesCount', { count: savedBarcodes.length })} ·{' '}
                    {savedBarcodes.join(', ')}
                  </div>
                )}
              </div>
            </Field>

            <div className="grid grid-cols-2 gap-4">
              <Field label={t('pr.barcode')}>
                <Input
                  className="num"
                  value={form.barcode}
                  onChange={set('barcode')}
                  disabled={editingDisabled}
                  data-testid="detail-barcode"
                />
              </Field>
              <Field label={t('pr.ipartId')}>
                <Input
                  className="num"
                  value={form.ipartId}
                  onChange={set('ipartId')}
                  disabled={editingDisabled}
                  data-testid="detail-ipart"
                />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-4">
              {/* Цена из Medusa — read-only (обновляется каждый час, не редактируется). */}
              <Field label={t('pm.fldPrice')} hint={t('pm.priceHint')}>
                <div
                  className="inp flex items-center bg-paper-input font-bold text-ink-700"
                  data-testid="detail-price-readonly"
                >
                  {promo.price ? formatKzt(promo.price) : t('sf.priceNa')}
                </div>
              </Field>
              <Field label={t('pm.fldBonus')} hint={t('pm.bonusHint')}>
                <Input
                  type="number"
                  min={0}
                  value={form.pharmacistBonus}
                  onChange={set('pharmacistBonus')}
                  disabled={editingDisabled}
                />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <Field label={t('pm.fldDateStart')}>
                <Input
                  type="date"
                  value={form.dateStart}
                  onChange={set('dateStart')}
                  disabled={editingDisabled}
                />
              </Field>
              <Field label={t('pm.fldDateEnd')}>
                <Input
                  type="date"
                  value={form.dateEnd}
                  onChange={set('dateEnd')}
                  disabled={editingDisabled}
                />
              </Field>
            </div>

            <Field label={t('pm.fldOverrideImage')} hint={t('pm.overrideHint')} optional>
              <Input
                value={form.overrideImage}
                onChange={set('overrideImage')}
                placeholder="https://…"
                disabled={editingDisabled}
              />
            </Field>

            {/* Галерея фото товара (просмотр): обложка + все фото из Medusa. */}
            <Field label={t('pm.galleryTitle')} hint={t('pm.galleryHint')}>
              <ProductGallery
                key={galleryImages.join('|')}
                images={galleryImages}
                effective={effectiveImage}
                resolveSrc={proxyMedia}
                onPickCover={
                  isArchived ? undefined : (url) => setForm((f) => ({ ...f, overrideImage: url }))
                }
              />
            </Field>
            <Field
              label={t('pm.fldOverrideDesc')}
              hint={medusaId ? t('pm.medusaHint') : t('pm.overrideHint')}
              optional
            >
              <textarea
                className="inp"
                rows={3}
                value={form.overrideDescription}
                onChange={(e) => setForm((f) => ({ ...f, overrideDescription: e.target.value }))}
                disabled={editingDisabled}
              />
            </Field>
            <Field
              label={t('pm.fldOverrideChars')}
              hint={medusaId ? t('pm.medusaCharsHint') : t('pm.overrideCharsHint')}
              optional
            >
              <textarea
                className="inp font-mono text-[13px]"
                rows={5}
                value={form.overrideCharacteristics}
                onChange={(e) =>
                  setForm((f) => ({ ...f, overrideCharacteristics: e.target.value }))
                }
                placeholder={t('pm.charsPlaceholder')}
                disabled={editingDisabled}
              />
            </Field>
            {medusaProduct && medusaCharacteristics && (
              <div className="rounded-xl bg-ink-50 px-3.5 py-3 text-[12px] text-ink-500">
                <div className="mb-1 font-bold uppercase tracking-[0.05em] text-ink-400">
                  {t('pm.medusaCharsRef')}
                </div>
                <ul className="list-disc space-y-0.5 pl-4">
                  {(medusaProduct.keyFacts ?? []).map((k, i) => (
                    <li key={i}>{k}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          <div className="card flex flex-col gap-4 p-5">
            <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
              {t('pd.mainData')}
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Field label={t('pd.fldTitle')}>
                <Input value={form.title} onChange={set('title')} disabled={editingDisabled} />
              </Field>
              <Field label={t('pd.fldBrand')}>
                <Input value={form.brand} onChange={set('brand')} disabled={editingDisabled} />
              </Field>
              <Field label={t('pd.fldPeriod')}>
                <Input
                  value={form.period}
                  onChange={set('period')}
                  placeholder={t('pd.periodPh')}
                  disabled={editingDisabled}
                />
              </Field>
              <Field label={t('pd.fldBudget')}>
                <Input
                  type="number"
                  min={0}
                  value={form.budget}
                  onChange={set('budget')}
                  disabled={editingDisabled}
                />
              </Field>
              <Field label={t('pd.fldKpi')} optional>
                <Input
                  value={form.kpi}
                  onChange={set('kpi')}
                  placeholder={t('pd.kpiPh')}
                  disabled={editingDisabled}
                />
              </Field>
              <Field label={t('pd.fldCover')} optional>
                <Input
                  value={form.cover}
                  onChange={set('cover')}
                  placeholder={t('pd.coverPh')}
                  disabled={editingDisabled}
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
                  disabled={
                    !canEdit ||
                    (!hasLocalChanges && (promo.status !== 'draft' || changes?.hasPendingChanges))
                  }
                  onClick={() => handleSave()}
                  data-testid="promo-changes-save"
                >
                  {saveChanges.isPending ? t('pm.creating') : 'Сохранить изменения'}
                </Button>
              </div>
            )}
          </div>

          {/* T2 — Замены и кросс-селл (правила, авторинг из кампании) */}
          {changes && (
            <PromoRulesEditor
              key={`${promo.id}:${changes.revision}`}
              config={rulesConfig}
              onChange={setRulesConfig}
              onSave={handleSave}
              saving={saveChanges.isPending}
              bonus={Math.max(0, Math.trunc(Number(form.pharmacistBonus) || 0))}
              disabled={editingDisabled}
              promotedProductId={promo.medusaProductId ?? undefined}
              promotedName={promo.productName}
              promotedPrice={promo.price}
            />
          )}
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
      {reviewOpen && changes && (
        <Modal
          open
          onClose={() => {
            if (!applyChanges.isPending) setReviewOpen(false)
          }}
          title={
            promo.status === 'draft'
              ? 'Проверить и запустить кампанию'
              : 'Проверить и применить изменения'
          }
          subtitle={`Применяется сохранённая ревизия ${changes.revision}.`}
          width={700}
          footer={
            <>
              <Button
                variant="ghost"
                disabled={applyChanges.isPending}
                onClick={() => setReviewOpen(false)}
              >
                Назад к редактированию
              </Button>
              <Button disabled={!mayApply} onClick={applyPrepared}>
                {applyChanges.isPending
                  ? 'Применяем…'
                  : promo.status === 'draft'
                    ? 'Запустить кампанию'
                    : 'Применить изменения'}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4 text-[13px]">
            <div>
              <strong>{changes.bundle.campaign.title ?? promo.title}</strong>
              <p>{promo.productName}</p>
              <p>Вся сеть · {promo.pharmacies} аптек</p>
              <p>
                Сроки: {changes.bundle.campaign.dateStart || 'Без начала'} —{' '}
                {changes.bundle.campaign.dateEnd || 'Без окончания'}
              </p>
              <p>
                Бонус основного товара:{' '}
                {formatKzt(changes.bundle.campaign.pharmacistBonus ?? promo.pharmacistBonus)}
              </p>
            </div>
            {promo.status === 'paused' && (
              <p className="font-semibold">Кампания останется на паузе после применения.</p>
            )}
            <p>
              Проверьте типы триггеров, включённые пары и все предлагаемые товары. Выключенные пары
              не включаются автоматически.
            </p>
            {[
              ...changes.bundle.rules.replacements.map((pair) => ({ pair, kind: 'Замена' })),
              ...changes.bundle.rules.crossSells.map((pair) => ({ pair, kind: 'Кросс-селл' })),
            ].map(({ pair, kind }, index) => (
              <div key={index} className="rounded-lg border border-ink-100 p-3">
                <strong>
                  {kind} · {pair.active === false ? 'Выключена' : 'Включена'}
                </strong>
                <p>
                  {
                    {
                      product: 'Конкретный товар',
                      acc_group: 'Группа АСС',
                      acc_subgroup: 'Подгруппа АСС',
                      acc_mnn: 'МНН АСС',
                    }[promoTriggerKind(pair)]
                  }
                  : {pair.triggerLabel || pair.name}
                </p>
                <p>
                  Предложить: {promo.productName} · бонус{' '}
                  {formatKzt(
                    pair.bonus ?? changes.bundle.campaign.pharmacistBonus ?? promo.pharmacistBonus,
                  )}
                </p>
                {pair.additionalRecommendations?.map((offer, offerIndex) => (
                  <p key={offerIndex}>
                    {offer.name} · бонус{' '}
                    {formatKzt(
                      offer.bonus ??
                        changes.bundle.campaign.pharmacistBonus ??
                        promo.pharmacistBonus,
                    )}
                  </p>
                ))}
              </div>
            ))}
            <p className="text-[12px] text-ink-500">
              После применения сервер использует новые условия для подходящих запросов касс.
              Подтверждение этого действия не означает фактический показ окна на каждом устройстве.
            </p>
          </div>
        </Modal>
      )}
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
