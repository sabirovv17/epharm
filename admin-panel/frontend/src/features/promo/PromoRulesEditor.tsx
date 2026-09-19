// PromoRulesEditor (T2 + per-pair карточка) — секция «Замены и кросс-селл».
//
// Модель: 1 кампания = 1 продвигаемый товар (выбран при создании, тут НЕ меняется).
// Каждая пара (замена/кросс-селл) = до пяти вариантов на кассе и несёт свои поля,
// которые на ней показываются: скрипт «что сказать и почему», преимущества, метка
// партнёра, таблица-сравнение, цель. Товары добавляются по кнопке «Добавить» (модалка).

import { cloneElement, useEffect, useState, type ReactElement } from 'react'
import { Button, Field, Input, Modal, Toggle, useToast } from '@/ui'
import { IconChevDown, IconClose, IconPlus } from '@/ui/icons'
import type {
  PromoOfferProductRef,
  PromoRuleProductRef,
  PromoRulesConfigDto,
  RuleComparisonRowDto,
  StorefrontProductDto,
} from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { usePromoRules, useSavePromoRules } from '@/lib/queries/promoRules'
import { MultiProductPicker } from './PromoProductPicker'
import {
  extractPromoRulesFieldErrors,
  normalizePromoRulesConfig,
  PROMO_RULE_LIMITS,
  type PromoRulesValidationErrors,
  validatePromoRulesConfig,
} from './promoRulesValidation'

type ListKey = 'replacements' | 'crossSells'
type PairKind = 'replacement' | 'crossSell'

const MAX_OFFERS_PER_PAIR = 5

const EMPTY_CONFIG: PromoRulesConfigDto = {
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

function toRef(p: StorefrontProductDto): PromoRuleProductRef {
  return {
    medusaProductId: p.id,
    name: p.name,
    brand: p.brand,
    mnn: p.mnn,
    price: p.price,
    barcode: p.barcode,
    ipartId: p.ipartId ?? null,
    script: '',
    advantages: [],
    partnerLabel: null,
    comparison: [],
    additionalRecommendations: [],
    active: true,
  }
}

function toOffer(p: StorefrontProductDto): PromoOfferProductRef {
  return {
    medusaProductId: p.id,
    name: p.name,
    brand: p.brand,
    mnn: p.mnn,
    price: p.price,
    barcode: p.barcode,
    ipartId: p.ipartId ?? null,
  }
}

/** Цель кампании в виде, удобном для превью карточки кассы. */
export interface CampaignGoal {
  label: string | null
  target: number | null
  bonus: number | null
}

function validationErrorId(path: string): string {
  return `promo-rules-error-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`
}

function InlineValidatedField({
  error,
  path,
  children,
}: {
  error?: string
  path: string
  children: ReactElement<Record<string, unknown>>
}) {
  const errorId = validationErrorId(path)
  return (
    <div>
      {cloneElement(children, {
        'data-validation-path': path,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': error ? errorId : undefined,
        className: `${String(children.props.className ?? '')} ${error ? 'border-accent-danger' : ''}`,
      })}
      {error && (
        <span id={errorId} className="mt-1 block text-[11px] font-semibold text-accent-danger">
          {error}
        </span>
      )}
    </div>
  )
}

export function PromoRulesEditor({
  promoId,
  bonus = 0,
  disabled = false,
  promotedProductId,
  promotedName,
  promotedPrice,
}: {
  promoId: string
  /** Бонус фармацевту за продажу (из кампании) — для превью карточки кассы. */
  bonus?: number
  disabled?: boolean
  /** Основной вариант каждой пары; дополнительные варианты выбираются рядом с ним. */
  promotedProductId?: string
  /** Продвигаемый товар кампании — рекомендация для замены и кросс-селла в превью. */
  promotedName?: string
  promotedPrice?: number | null
}) {
  const t = useT()
  const toast = useToast()
  const { data, isLoading, isError, error, refetch } = usePromoRules(promoId)
  const save = useSavePromoRules()

  const [cfg, setCfg] = useState<PromoRulesConfigDto>(EMPTY_CONFIG)
  const [validationErrors, setValidationErrors] = useState<PromoRulesValidationErrors>({})
  const [submitError, setSubmitError] = useState<string | null>(null)

  // Populate from server config on load / promo change.
  useEffect(() => {
    if (data) {
      setCfg(data.config)
      setValidationErrors({})
      setSubmitError(null)
    }
  }, [data])

  const validationMessages = {
    required: t('pr.validationRequired'),
    maxLength: (max: number) => t('pr.validationMaxLength', { max }),
    positiveInteger: t('pr.validationPositiveInteger'),
    nonNegativeInteger: t('pr.validationNonNegativeInteger'),
    invalidProduct: t('pr.validationInvalidProduct'),
    goalLabelRequired: t('pr.validationGoalLabelRequired'),
    goalTargetRequired: t('pr.validationGoalTargetRequired'),
  }

  const clearValidation = (path?: string) => {
    if (!path) {
      setSubmitError(null)
      setValidationErrors({})
      return
    }
    setValidationErrors((current) => {
      if (!current[path]) return current
      const next = { ...current }
      delete next[path]
      if (Object.keys(next).length === 0) setSubmitError(null)
      return next
    })
  }

  const focusFirstError = (errors: PromoRulesValidationErrors) => {
    const paths = Object.keys(errors)
    if (paths.length === 0) return
    window.setTimeout(() => {
      const candidates = Array.from(
        document.querySelectorAll<HTMLElement>('[data-validation-path]'),
      )
      const target = candidates.find((node) => {
        const nodePath = node.dataset.validationPath ?? ''
        return paths.some((path) => path === nodePath || path.startsWith(`${nodePath}.`))
      })
      target?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
      target?.focus?.({ preventScroll: true })
    }, 0)
  }

  const showValidationErrors = (errors: PromoRulesValidationErrors, message: string) => {
    setValidationErrors(errors)
    setSubmitError(message)
    focusFirstError(errors)
  }

  // ── Замены / кросс-селл ───────────────────────────────────────────────────
  const toggleIn = (key: ListKey) => (p: StorefrontProductDto) => {
    clearValidation()
    setCfg((c) => {
      const list = c[key]
      const exists = list.some((r) => r.medusaProductId === p.id)
      return {
        ...c,
        [key]: exists ? list.filter((r) => r.medusaProductId !== p.id) : [...list, toRef(p)],
      }
    })
  }
  const removeFrom = (key: ListKey, medusaProductId: string) => {
    clearValidation()
    setCfg((c) => ({
      ...c,
      [key]: c[key].filter((r) => r.medusaProductId !== medusaProductId),
    }))
  }
  const updatePair = (key: ListKey, medusaProductId: string, patch: Partial<PromoRuleProductRef>) =>
    setCfg((c) => ({
      ...c,
      [key]: c[key].map((r) => (r.medusaProductId === medusaProductId ? { ...r, ...patch } : r)),
    }))

  const onSave = () => {
    const config = normalizePromoRulesConfig(cfg, promotedProductId)
    const localErrors = validatePromoRulesConfig(config, validationMessages)
    if (Object.keys(localErrors).length > 0) {
      showValidationErrors(localErrors, t('pr.validationSummary'))
      return
    }

    clearValidation()
    save.mutate(
      { promoId, config },
      {
        onSuccess: () => {
          clearValidation()
          toast.push(t('pr.savedToast'))
        },
        onError: (e) => {
          const fields = extractPromoRulesFieldErrors(e, validationMessages)
          const message = describeError(e)
          if (Object.keys(fields).length > 0) showValidationErrors(fields, message)
          else setSubmitError(message)
          toast.push(message, { kind: 'error', duration: 6000 })
        },
      },
    )
  }

  const goal: CampaignGoal = {
    label: cfg.goalLabel,
    target: cfg.goalTarget,
    bonus: cfg.goalBonus,
  }

  return (
    <div className="card flex flex-col gap-4 p-5" data-testid="promo-rules-editor">
      <div className="flex items-center justify-between">
        <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
          {t('pr.section')}
        </div>
        {data && (
          <span className="text-[12px] font-semibold text-ink-500" data-testid="promo-rules-counts">
            {t('pr.counts', { active: data.activeCount, total: data.ruleCount })}
          </span>
        )}
      </div>

      {isLoading && !data ? (
        <div className="py-4 text-[13px] font-semibold text-ink-400">{t('pr.loading')}</div>
      ) : isError && !data ? (
        <div className="flex items-center gap-3 text-[13px] font-semibold text-accent-danger">
          {describeError(error)}
          <button type="button" onClick={() => refetch()} className="underline">
            {t('common.retry')}
          </button>
        </div>
      ) : (
        <>
          <RuleSection
            sectionKey="replacements"
            titleKey="pr.replacements"
            hintKey="pr.replacementsHint"
            addLabelKey="pr.addReplacement"
            items={cfg.replacements}
            disabled={disabled}
            bonus={bonus}
            goal={goal}
            promotedName={promotedName}
            promotedProductId={promotedProductId}
            promotedPrice={promotedPrice}
            errors={validationErrors}
            onClearError={clearValidation}
            onToggle={toggleIn('replacements')}
            onRemove={(idp) => removeFrom('replacements', idp)}
            onPatch={(idp, p) => updatePair('replacements', idp, p)}
          />

          <RuleSection
            sectionKey="crossSells"
            titleKey="pr.crossSells"
            hintKey="pr.crossSellsHint"
            addLabelKey="pr.addCrossSell"
            items={cfg.crossSells}
            disabled={disabled}
            bonus={bonus}
            goal={goal}
            promotedName={promotedName}
            promotedProductId={promotedProductId}
            promotedPrice={promotedPrice}
            errors={validationErrors}
            onClearError={clearValidation}
            onToggle={toggleIn('crossSells')}
            onRemove={(idp) => removeFrom('crossSells', idp)}
            onPatch={(idp, p) => updatePair('crossSells', idp, p)}
          />

          {/* Цель — одна на всю кампанию (применяется ко всем парам). */}
          <Field label={t('pr.campaignGoal')} hint={t('pr.campaignGoalHint')}>
            <div className="grid grid-cols-3 gap-2">
              <InlineValidatedField error={validationErrors.goalLabel} path="goalLabel">
                <Input
                  value={cfg.goalLabel ?? ''}
                  disabled={disabled}
                  maxLength={PROMO_RULE_LIMITS.goalLabel}
                  placeholder={t('pr.goalLabel')}
                  data-testid="pr-goal-label"
                  onChange={(e) => {
                    clearValidation('goalLabel')
                    setCfg((c) => ({ ...c, goalLabel: e.target.value || null }))
                  }}
                />
              </InlineValidatedField>
              <InlineValidatedField error={validationErrors.goalTarget} path="goalTarget">
                <Input
                  type="number"
                  min={1}
                  max={PROMO_RULE_LIMITS.maxInt}
                  step={1}
                  value={cfg.goalTarget ?? ''}
                  disabled={disabled}
                  placeholder={t('pr.goalTarget')}
                  data-testid="pr-goal-target"
                  onChange={(e) => {
                    clearValidation('goalTarget')
                    setCfg((c) => ({
                      ...c,
                      goalTarget: e.target.value === '' ? null : Number(e.target.value),
                    }))
                  }}
                />
              </InlineValidatedField>
              <InlineValidatedField error={validationErrors.goalBonus} path="goalBonus">
                <Input
                  type="number"
                  min={0}
                  max={PROMO_RULE_LIMITS.maxInt}
                  step={1}
                  value={cfg.goalBonus ?? ''}
                  disabled={disabled}
                  placeholder={t('pr.goalBonus')}
                  data-testid="pr-goal-bonus"
                  onChange={(e) => {
                    clearValidation('goalBonus')
                    setCfg((c) => ({
                      ...c,
                      goalBonus: e.target.value === '' ? null : Number(e.target.value),
                    }))
                  }}
                />
              </InlineValidatedField>
            </div>
          </Field>

          {submitError && (
            <div
              className="rounded-xl border border-accent-danger/30 bg-accent-danger/5 px-4 py-3 text-[13px] text-accent-danger"
              role="alert"
              aria-live="assertive"
              data-testid="promo-rules-validation-summary"
            >
              <div className="font-extrabold">{t('pr.validationTitle')}</div>
              <div className="mt-0.5 font-semibold">
                {Object.keys(validationErrors).length > 0
                  ? t('pr.validationCount', { count: Object.keys(validationErrors).length })
                  : submitError}
              </div>
            </div>
          )}

          {!disabled && (
            <div className="hairline flex items-center justify-end border-t pt-3">
              <Button
                variant="primary"
                disabled={save.isPending}
                onClick={onSave}
                data-testid="promo-rules-save"
              >
                {save.isPending ? t('pm.creating') : t('pr.save')}
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

/**
 * Секция правил (замены ИЛИ кросс-селл): список выбранных пар + кнопка «Добавить»
 * (открывает модалку-поиск). Каждая пара = карточка [PairCard] со своими полями.
 */
function RuleSection({
  sectionKey,
  titleKey,
  hintKey,
  addLabelKey,
  items,
  disabled,
  bonus,
  goal,
  promotedName,
  promotedProductId,
  promotedPrice,
  errors,
  onClearError,
  onToggle,
  onRemove,
  onPatch,
}: {
  sectionKey: ListKey
  titleKey: string
  hintKey: string
  addLabelKey: string
  items: PromoRuleProductRef[]
  disabled: boolean
  bonus: number
  goal: CampaignGoal
  promotedName?: string
  promotedProductId?: string
  promotedPrice?: number | null
  errors: PromoRulesValidationErrors
  onClearError: (path?: string) => void
  onToggle: (p: StorefrontProductDto) => void
  onRemove: (medusaProductId: string) => void
  onPatch: (medusaProductId: string, patch: Partial<PromoRuleProductRef>) => void
}) {
  const t = useT()
  const [pickerOpen, setPickerOpen] = useState(false)
  const kind: PairKind = sectionKey === 'replacements' ? 'replacement' : 'crossSell'
  return (
    <Field label={`${t(titleKey)} · ${items.length}`} hint={t(hintKey)}>
      {items.length === 0 ? (
        <div className="text-[12px] font-semibold text-ink-400">{t('pr.noneChosen')}</div>
      ) : (
        <ul className="flex flex-col gap-2" data-testid={`pr-list-${sectionKey}`}>
          {items.map((r, index) => (
            <PairCard
              key={r.medusaProductId}
              r={r}
              disabled={disabled}
              kind={kind}
              bonus={bonus}
              goal={goal}
              promotedName={promotedName}
              promotedProductId={promotedProductId}
              promotedPrice={promotedPrice}
              pathPrefix={`${sectionKey}[${index}]`}
              errors={errors}
              onClearError={onClearError}
              onRemove={() => onRemove(r.medusaProductId)}
              onPatch={(p) => onPatch(r.medusaProductId, p)}
            />
          ))}
        </ul>
      )}

      {!disabled && (
        <div className="mt-2">
          <Button
            variant="outline"
            onClick={() => setPickerOpen(true)}
            leading={<IconPlus size={14} />}
            data-testid={`pr-add-${sectionKey}`}
          >
            {t(addLabelKey)}
          </Button>
        </div>
      )}

      <Modal
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        title={t(addLabelKey)}
        subtitle={t('pr.pickerSubLimit')}
        width={560}
        footer={
          <Button variant="primary" onClick={() => setPickerOpen(false)}>
            {t('pr.pickerDone')}
          </Button>
        }
      >
        <MultiProductPicker selectedIds={items.map((r) => r.medusaProductId)} onPick={onToggle} />
      </Modal>
    </Field>
  )
}

/**
 * Карточка одной пары: товар + скрипт + сворачиваемые поля рекомендации
 * (преимущества / метка партнёра / сравнение / цель) — всё, что видно на кассе.
 */
function PairCard({
  r,
  disabled,
  kind,
  bonus,
  goal,
  promotedName,
  promotedProductId,
  promotedPrice,
  pathPrefix,
  errors,
  onClearError,
  onRemove,
  onPatch,
}: {
  r: PromoRuleProductRef
  disabled: boolean
  kind: PairKind
  bonus: number
  goal: CampaignGoal
  promotedName?: string
  promotedProductId?: string
  promotedPrice?: number | null
  pathPrefix: string
  errors: PromoRulesValidationErrors
  onClearError: (path?: string) => void
  onRemove: () => void
  onPatch: (patch: Partial<PromoRuleProductRef>) => void
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const cmp = r.comparison ?? []
  const setComparison = (rows: RuleComparisonRowDto[]) => onPatch({ comparison: rows })
  const pairActive = r.active !== false
  const offers = r.additionalRecommendations ?? []
  const [offerPickerOpen, setOfferPickerOpen] = useState(false)
  const cardError = errors[`${pathPrefix}.medusaProductId`]
  const hasAdvancedError = Object.keys(errors).some(
    (path) =>
      path.startsWith(`${pathPrefix}.partnerLabel`) ||
      path.startsWith(`${pathPrefix}.comparison[`),
  )

  useEffect(() => {
    if (hasAdvancedError) setOpen(true)
  }, [hasAdvancedError])
  const atOfferLimit = offers.length >= MAX_OFFERS_PER_PAIR - 1
  const toggleOffer = (p: StorefrontProductDto) => {
    onClearError()
    const exists = offers.some((offer) => offer.medusaProductId === p.id)
    if (exists) {
      onPatch({
        additionalRecommendations: offers.filter((offer) => offer.medusaProductId !== p.id),
      })
      return
    }
    if (atOfferLimit || p.id === promotedProductId || p.id === r.medusaProductId) return
    onPatch({ additionalRecommendations: [...offers, toOffer(p)] })
  }
  const removeOffer = (medusaProductId: string) => {
    onClearError()
    onPatch({
      additionalRecommendations: offers.filter(
        (offer) => offer.medusaProductId !== medusaProductId,
      ),
    })
  }

  return (
    <li
      data-testid={`pr-chosen-${r.medusaProductId}`}
      data-validation-path={`${pathPrefix}.medusaProductId`}
      tabIndex={cardError ? -1 : undefined}
      aria-invalid={cardError ? true : undefined}
      className={`hairline grid gap-3 rounded-xl border bg-paper-card p-3 lg:grid-cols-[minmax(0,1fr)_340px] ${cardError ? 'border-accent-danger' : ''}`}
    >
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-bold text-ink-900">{r.name}</div>
            {r.brand && (
              <div className="truncate text-[11px] font-semibold text-ink-500">{r.brand}</div>
            )}
          </div>
          {/* Статус именно этой пары: Активно / Неактивно. */}
          <div
            data-testid={`pr-status-${r.medusaProductId}`}
            className={`shrink-0 ${disabled ? 'opacity-70' : ''}`}
          >
            <Toggle
              on={pairActive}
              onChange={(next) => onPatch({ active: next })}
              label={pairActive ? t('pr.statusActive') : t('pr.statusInactive')}
              disabled={disabled}
            />
          </div>
          {!disabled && (
            <button
              type="button"
              onClick={onRemove}
              aria-label={t('pr.removePair')}
              data-testid={`pr-remove-${r.medusaProductId}`}
              className="shrink-0 text-ink-400 transition-colors hover:text-accent-danger"
            >
              <IconClose size={14} />
            </button>
          )}
        </div>

        {cardError && (
          <div className="text-[11px] font-semibold text-accent-danger" role="alert">
            {cardError}
          </div>
        )}

        <div className="grid gap-2 sm:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1">
            <span className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
              {t('pr.barcode')}
            </span>
            <Input
              className={`num ${errors[`${pathPrefix}.barcode`] ? 'border-accent-danger' : ''}`}
              value={r.barcode ?? ''}
              disabled={disabled}
              maxLength={PROMO_RULE_LIMITS.barcode}
              data-validation-path={`${pathPrefix}.barcode`}
              aria-invalid={errors[`${pathPrefix}.barcode`] ? true : undefined}
              aria-describedby={
                errors[`${pathPrefix}.barcode`]
                  ? validationErrorId(`${pathPrefix}.barcode`)
                  : undefined
              }
              data-testid={`pr-barcode-${r.medusaProductId}`}
              onChange={(e) => {
                onClearError(`${pathPrefix}.barcode`)
                onPatch({ barcode: e.target.value || null })
              }}
            />
            {errors[`${pathPrefix}.barcode`] && (
              <span
                id={validationErrorId(`${pathPrefix}.barcode`)}
                className="text-[11px] font-semibold text-accent-danger"
              >
                {errors[`${pathPrefix}.barcode`]}
              </span>
            )}
          </label>
          <label className="flex min-w-0 flex-col gap-1">
            <span className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
              {t('pr.ipartId')}
            </span>
            <Input
              className={`num ${errors[`${pathPrefix}.ipartId`] ? 'border-accent-danger' : ''}`}
              value={r.ipartId ?? ''}
              disabled={disabled}
              maxLength={PROMO_RULE_LIMITS.ipartId}
              data-validation-path={`${pathPrefix}.ipartId`}
              aria-invalid={errors[`${pathPrefix}.ipartId`] ? true : undefined}
              aria-describedby={
                errors[`${pathPrefix}.ipartId`]
                  ? validationErrorId(`${pathPrefix}.ipartId`)
                  : undefined
              }
              data-testid={`pr-ipart-${r.medusaProductId}`}
              onChange={(e) => {
                onClearError(`${pathPrefix}.ipartId`)
                onPatch({ ipartId: e.target.value || null })
              }}
            />
            {errors[`${pathPrefix}.ipartId`] && (
              <span
                id={validationErrorId(`${pathPrefix}.ipartId`)}
                className="text-[11px] font-semibold text-accent-danger"
              >
                {errors[`${pathPrefix}.ipartId`]}
              </span>
            )}
          </label>
        </div>

        <textarea
          className={`inp text-[13px] ${errors[`${pathPrefix}.script`] ? 'border-accent-danger' : ''}`}
          rows={2}
          value={r.script ?? ''}
          disabled={disabled}
          maxLength={PROMO_RULE_LIMITS.script}
          placeholder={t('pr.pairScriptPh')}
          aria-label={t('pr.pairScript')}
          aria-invalid={errors[`${pathPrefix}.script`] ? true : undefined}
          aria-describedby={
            errors[`${pathPrefix}.script`]
              ? validationErrorId(`${pathPrefix}.script`)
              : undefined
          }
          data-validation-path={`${pathPrefix}.script`}
          data-testid={`pr-script-${r.medusaProductId}`}
          onChange={(e) => {
            onClearError(`${pathPrefix}.script`)
            onPatch({ script: e.target.value })
          }}
        />
        {errors[`${pathPrefix}.script`] && (
          <span
            id={validationErrorId(`${pathPrefix}.script`)}
            className="text-[11px] font-semibold text-accent-danger"
          >
            {errors[`${pathPrefix}.script`]}
          </span>
        )}

        <div className="hairline rounded-lg border bg-paper-input p-3">
          <div className="mb-2 flex items-center justify-between gap-2">
            <div>
              <div className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
                {t('pr.offersOnRegister')}
              </div>
              <div className="text-[12px] font-semibold text-ink-500">
                {t('pr.offersCount', { n: offers.length + 1, max: MAX_OFFERS_PER_PAIR })}
              </div>
            </div>
            {!disabled && (
              <Button
                variant="outline"
                disabled={atOfferLimit}
                onClick={() => setOfferPickerOpen(true)}
                leading={<IconPlus size={13} />}
                data-testid={`pr-add-offer-${r.medusaProductId}`}
              >
                {atOfferLimit ? t('pr.limitReached') : t('pr.addOffer')}
              </Button>
            )}
          </div>

          <ul className="divide-hairline overflow-hidden rounded-lg border bg-white">
            <li className="flex items-center gap-2 px-3 py-2">
              <div className="min-w-0 flex-1">
                <div className="truncate text-[12px] font-bold text-ink-900">
                  {promotedName || t('pr.previewNoName')}
                </div>
                <div className="text-[10px] font-semibold text-brand-green-700">
                  {t('pr.primaryOffer')}
                </div>
              </div>
              {promotedPrice != null && (
                <span className="num text-[12px] font-extrabold text-ink-900">
                  {promotedPrice.toLocaleString('ru-RU')} ₸
                </span>
              )}
            </li>
            {offers.map((offer, offerIndex) => {
              const offerPath = `${pathPrefix}.additionalRecommendations[${offerIndex}].medusaProductId`
              const offerError = errors[offerPath]
              return (
              <li
                key={offer.medusaProductId}
                className={`flex items-center gap-2 px-3 py-2 ${offerError ? 'bg-accent-danger/5' : ''}`}
                data-testid={`pr-offer-${r.medusaProductId}-${offer.medusaProductId}`}
                data-validation-path={offerPath}
                tabIndex={offerError ? -1 : undefined}
                aria-invalid={offerError ? true : undefined}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[12px] font-bold text-ink-900">{offer.name}</div>
                  {offer.brand && (
                    <div className="truncate text-[10px] font-semibold text-ink-400">
                      {offer.brand}
                    </div>
                  )}
                  {offerError && (
                    <div className="text-[10px] font-semibold text-accent-danger">{offerError}</div>
                  )}
                </div>
                {offer.price != null && (
                  <span className="num text-[12px] font-extrabold text-ink-900">
                    {offer.price.toLocaleString('ru-RU')} ₸
                  </span>
                )}
                {!disabled && (
                  <button
                    type="button"
                    onClick={() => removeOffer(offer.medusaProductId)}
                    aria-label={t('pr.removeOffer')}
                    className="text-ink-400 hover:text-accent-danger"
                  >
                    <IconClose size={13} />
                  </button>
                )}
              </li>
              )
            })}
          </ul>
        </div>

        <Modal
          open={offerPickerOpen}
          onClose={() => setOfferPickerOpen(false)}
          title={t('pr.addOffer')}
          subtitle={t('pr.offerPickerSub')}
          width={560}
          footer={
            <Button variant="primary" onClick={() => setOfferPickerOpen(false)}>
              {t('pr.pickerDone')}
            </Button>
          }
        >
          <MultiProductPicker
            selectedIds={[
              ...(promotedProductId ? [promotedProductId] : []),
              r.medusaProductId,
              ...offers.map((offer) => offer.medusaProductId),
            ]}
            onPick={toggleOffer}
          />
        </Modal>

        {/* Поля, которые показываются в блоке рекомендации на кассе (per-pair). */}
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="flex items-center gap-1.5 self-start text-[12px] font-bold text-brand-green-700"
          data-testid={`pr-card-toggle-${r.medusaProductId}`}
          aria-expanded={open}
        >
          <IconChevDown size={14} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
          {t('pr.cardFields')}
        </button>

        {open && (
          <div className="hairline flex flex-col gap-3 rounded-lg border bg-paper-input p-3">
            <Field label={t('pr.advantages')} hint={t('pr.advantagesHint')}>
              <textarea
                className="inp text-[13px]"
                rows={2}
                value={(r.advantages ?? []).join('\n')}
                disabled={disabled}
                data-testid={`pr-adv-${r.medusaProductId}`}
                onChange={(e) => onPatch({ advantages: e.target.value.split('\n') })}
              />
            </Field>

            <Field label={t('pr.partnerLabel')}>
              <InlineValidatedField
                path={`${pathPrefix}.partnerLabel`}
                error={errors[`${pathPrefix}.partnerLabel`]}
              >
                <Input
                  value={r.partnerLabel ?? ''}
                  disabled={disabled}
                  maxLength={PROMO_RULE_LIMITS.partnerLabel}
                  onChange={(e) => {
                    onClearError(`${pathPrefix}.partnerLabel`)
                    onPatch({ partnerLabel: e.target.value || null })
                  }}
                  placeholder="ПАРТНЁР EPHARM"
                />
              </InlineValidatedField>
            </Field>

            <Field label={t('pr.comparison')} hint={t('pr.comparisonHint')}>
              <div className="flex flex-col gap-2">
                {cmp.map((row, i) => (
                  <div
                    key={i}
                    className="grid grid-cols-[1fr_1fr_1fr_auto] items-center gap-2"
                    data-testid={`pr-cmp-${r.medusaProductId}-${i}`}
                  >
                    <InlineValidatedField
                      path={`${pathPrefix}.comparison[${i}].label`}
                      error={errors[`${pathPrefix}.comparison[${i}].label`]}
                    >
                      <Input
                        value={row.label}
                        disabled={disabled}
                        maxLength={PROMO_RULE_LIMITS.comparisonLabel}
                        onChange={(e) => {
                          onClearError(`${pathPrefix}.comparison[${i}].label`)
                          setComparison(
                            cmp.map((x, idx) =>
                              idx === i ? { ...x, label: e.target.value } : x,
                            ),
                          )
                        }}
                        placeholder={`${t('pr.colLabel')} *`}
                      />
                    </InlineValidatedField>
                    <InlineValidatedField
                      path={`${pathPrefix}.comparison[${i}].triggerValue`}
                      error={errors[`${pathPrefix}.comparison[${i}].triggerValue`]}
                    >
                      <Input
                        value={row.triggerValue}
                        disabled={disabled}
                        maxLength={PROMO_RULE_LIMITS.comparisonValue}
                        onChange={(e) => {
                          onClearError(`${pathPrefix}.comparison[${i}].triggerValue`)
                          setComparison(
                            cmp.map((x, idx) =>
                              idx === i ? { ...x, triggerValue: e.target.value } : x,
                            ),
                          )
                        }}
                        placeholder={t('pr.colWas')}
                      />
                    </InlineValidatedField>
                    <InlineValidatedField
                      path={`${pathPrefix}.comparison[${i}].recommendValue`}
                      error={errors[`${pathPrefix}.comparison[${i}].recommendValue`]}
                    >
                      <Input
                        value={row.recommendValue}
                        disabled={disabled}
                        maxLength={PROMO_RULE_LIMITS.comparisonValue}
                        onChange={(e) => {
                          onClearError(`${pathPrefix}.comparison[${i}].recommendValue`)
                          setComparison(
                            cmp.map((x, idx) =>
                              idx === i ? { ...x, recommendValue: e.target.value } : x,
                            ),
                          )
                        }}
                        placeholder={t('pr.colNow')}
                      />
                    </InlineValidatedField>
                    <div className="flex items-center gap-1.5">
                      <Toggle
                        on={row.recommendHighlight}
                        onChange={(on) =>
                          setComparison(
                            cmp.map((x, idx) => (idx === i ? { ...x, recommendHighlight: on } : x)),
                          )
                        }
                      />
                      {!disabled && (
                        <button
                          type="button"
                          onClick={() => {
                            onClearError()
                            setComparison(cmp.filter((_, idx) => idx !== i))
                          }}
                          aria-label={t('pr.removeRow')}
                          className="text-ink-400 transition-colors hover:text-accent-danger"
                        >
                          <IconClose size={14} />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
                {!disabled && (
                  <Button
                    variant="outline"
                    onClick={() => {
                      onClearError()
                      setComparison([
                        ...cmp,
                        {
                          label: '',
                          triggerValue: '',
                          recommendValue: '',
                          recommendHighlight: false,
                        },
                      ])
                    }}
                    leading={<IconPlus size={14} />}
                  >
                    {t('pr.addRow')}
                  </Button>
                )}
              </div>
            </Field>
          </div>
        )}
      </div>

      {/* Превью карточки рекомендации — как её увидит фармацевт на кассе (C# POSM). */}
      <RecommendationPreview
        r={r}
        kind={kind}
        bonus={bonus}
        goal={goal}
        promotedName={promotedName}
        promotedPrice={promotedPrice}
      />
    </li>
  )
}

/**
 * Живое превью карточки рекомендации, повторяющее POSM-окно на кассе
 * (App/RecommendationWindow.xaml): фиксированная шапка, исходный товар и компактный
 * прокручиваемый список вариантов. Статичное (без интерактива) — это превью.
 */
function RecommendationPreview({
  r,
  kind,
  bonus,
  goal,
  promotedName,
  promotedPrice,
}: {
  r: PromoRuleProductRef
  kind: PairKind
  bonus: number
  goal: CampaignGoal
  promotedName?: string
  promotedPrice?: number | null
}) {
  const t = useT()
  const advantages = (r.advantages ?? []).map((a) => a.trim()).filter((a) => a.length > 0)
  const fmtPrice = (p?: number | null) => (p != null ? `${p.toLocaleString('ru-RU')} ₸` : null)
  const isReplace = kind === 'replacement'
  const title = isReplace ? t('pr.previewReplace') : t('pr.previewCross')

  // Семантика как на кассе (backend PromoRulesService):
  //  • замена   — триггер = заменяемый товар (r), ПРЕДЛОЖИТЕ ВМЕСТО = товар кампании;
  //  • кросс-селл — триггер = товар уже в чеке (r), ДОБАВЬТЕ = товар кампании.
  const triggerLabel = isReplace ? t('pr.previewAsked') : t('pr.previewInCart')
  const triggerName = r.name
  // EAN-13 триггера: выбранный товар пары является trigger и для замены, и для кросс-селла.
  const triggerBarcode = r.barcode
  const offerLabel = isReplace ? t('pr.previewOfferInstead') : t('pr.previewOfferAdd')
  const offerName = promotedName
  const offers = [
    {
      id: 'primary',
      name: offerName || t('pr.previewNoName'),
      brand: null as string | null,
      price: promotedPrice,
      primary: true,
    },
    ...(r.additionalRecommendations ?? []).map((offer) => ({
      id: offer.medusaProductId,
      name: offer.name,
      brand: offer.brand ?? null,
      price: offer.price,
      primary: false,
    })),
  ].slice(0, MAX_OFFERS_PER_PAIR)

  const goalText =
    goal.label && goal.target != null ? `0/${goal.target} ${goal.label}` : goal.label || null

  return (
    <div className="lg:sticky lg:top-2 lg:self-start">
      <div className="mb-1.5 text-[11px] font-bold uppercase tracking-[0.05em] text-ink-400">
        {t('pr.previewTitle')}
      </div>
      <div className="overflow-hidden rounded-xl border border-ink-200 bg-white shadow-sm">
        {/* Шапка */}
        <div className="flex items-center gap-2 bg-[#9A4427] px-3.5 py-2.5 text-white">
          <span className="flex h-5 w-5 items-center justify-center rounded-full bg-white/20 text-[12px] font-bold">
            i
          </span>
          <span className="text-[13px] font-extrabold">{t('pr.previewOffers')}</span>
          <span className="ml-auto text-[15px] leading-none text-white/60">×</span>
        </div>

        {/* Триггер: УЖЕ В ЧЕКЕ / ПОКУПАТЕЛЬ ПОПРОСИЛ */}
        {triggerName && (
          <div
            className="px-3.5 pb-1 pt-2.5"
            data-testid={`pr-preview-trigger-${r.medusaProductId}`}
          >
            <div className="text-[10px] font-bold uppercase tracking-wide text-ink-400">
              {triggerLabel}
            </div>
            <div className="text-[13px] font-extrabold text-ink-900">{triggerName}</div>
            {triggerBarcode && <div className="num text-[10px] text-ink-400">{triggerBarcode}</div>}
          </div>
        )}

        <div className="bg-[#F8E7DD] px-3.5 py-2 text-[10px] font-bold uppercase tracking-wide text-[#9A4427]">
          {offerLabel} · {offers.length}/{MAX_OFFERS_PER_PAIR}
        </div>
        <ul
          className="scrollbar-thin max-h-[300px] divide-y divide-ink-100 overflow-y-auto"
          data-testid={`pr-preview-offer-${r.medusaProductId}`}
        >
          {offers.map((offer) => (
            <li key={offer.id} className="flex items-start gap-2 px-3.5 py-2.5">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="truncate text-[13px] font-extrabold text-ink-900">
                    {offer.name}
                  </span>
                  {offer.primary && (
                    <span className="rounded bg-[#F8E7DD] px-1.5 py-0.5 text-[9px] font-bold text-[#9A4427]">
                      {t('pr.primaryOffer')}
                    </span>
                  )}
                </div>
                <div className="truncate text-[10px] font-semibold text-ink-400">
                  {offer.brand || r.script?.trim() || advantages[0] || title}
                </div>
              </div>
              <div className="flex-none text-right">
                <div className="num text-[12px] font-extrabold text-ink-900">
                  {fmtPrice(offer.price) || '—'}
                </div>
                <div className="num text-[10px] font-bold text-[#BE5A38]">
                  +{bonus.toLocaleString('ru-RU')} ₸ {t('pr.previewBonus')}
                </div>
              </div>
            </li>
          ))}
        </ul>
        {goalText && (
          <div className="hairline border-t px-3.5 py-2 text-[10px] font-semibold text-ink-400">
            {goalText}
          </div>
        )}
      </div>
    </div>
  )
}
