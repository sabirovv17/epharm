// PromoRulesEditor (T2 + per-pair карточка) — секция «Замены и кросс-селл».
//
// Модель: 1 кампания = 1 продвигаемый товар (выбран при создании, тут НЕ меняется).
// Каждая пара (замена/кросс-селл) = ОДНА рекомендация на кассе и несёт СВОИ поля,
// которые на ней показываются: скрипт «что сказать и почему», преимущества, метка
// партнёра, таблица-сравнение, цель. Товары добавляются по кнопке «Добавить» (модалка).

import { useEffect, useState } from 'react'
import { Button, Field, Input, Modal, Toggle, useToast } from '@/ui'
import { IconChevDown, IconClose, IconPlus } from '@/ui/icons'
import type {
  PromoRuleProductRef,
  PromoRulesConfigDto,
  RuleComparisonRowDto,
  StorefrontProductDto,
} from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { usePromoRules, useSavePromoRules } from '@/lib/queries/promoRules'
import { MultiProductPicker } from './PromoProductPicker'

type ListKey = 'replacements' | 'crossSells'
type PairKind = 'replacement' | 'crossSell'

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
    script: '',
    advantages: [],
    partnerLabel: null,
    comparison: [],
    active: true,
  }
}

/** Цель кампании в виде, удобном для превью карточки кассы. */
export interface CampaignGoal {
  label: string | null
  target: number | null
  bonus: number | null
}

export function PromoRulesEditor({
  promoId,
  bonus = 0,
  disabled = false,
  promotedName,
  promotedPrice,
}: {
  promoId: string
  /** Бонус фармацевту за продажу (из кампании) — для превью карточки кассы. */
  bonus?: number
  disabled?: boolean
  /** Продвигаемый товар кампании — триггер кросс-селла / предложение замены в превью. */
  promotedName?: string
  promotedPrice?: number | null
}) {
  const t = useT()
  const toast = useToast()
  const { data, isLoading, isError, error, refetch } = usePromoRules(promoId)
  const save = useSavePromoRules()

  const [cfg, setCfg] = useState<PromoRulesConfigDto>(EMPTY_CONFIG)

  // Populate from server config on load / promo change.
  useEffect(() => {
    if (data) setCfg(data.config)
  }, [data])

  // ── Замены / кросс-селл ───────────────────────────────────────────────────
  const toggleIn = (key: ListKey) => (p: StorefrontProductDto) => {
    setCfg((c) => {
      const list = c[key]
      const exists = list.some((r) => r.medusaProductId === p.id)
      return {
        ...c,
        [key]: exists ? list.filter((r) => r.medusaProductId !== p.id) : [...list, toRef(p)],
      }
    })
  }
  const removeFrom = (key: ListKey, medusaProductId: string) =>
    setCfg((c) => ({
      ...c,
      [key]: c[key].filter((r) => r.medusaProductId !== medusaProductId),
    }))
  const updatePair = (key: ListKey, medusaProductId: string, patch: Partial<PromoRuleProductRef>) =>
    setCfg((c) => ({
      ...c,
      [key]: c[key].map((r) => (r.medusaProductId === medusaProductId ? { ...r, ...patch } : r)),
    }))

  // Дедуп по medusaProductId + чистка per-pair полей перед отправкой.
  const dedupe = (list: PromoRuleProductRef[]) => [
    ...new Map(list.map((r) => [r.medusaProductId, r])).values(),
  ]
  const cleanRef = (r: PromoRuleProductRef): PromoRuleProductRef => ({
    ...r,
    script: (r.script ?? '').trim(),
    advantages: (r.advantages ?? []).map((a) => a.trim()).filter((a) => a.length > 0),
    comparison: (r.comparison ?? []).filter((row) => row.label.trim().length > 0),
    partnerLabel: r.partnerLabel?.trim() || null,
    active: r.active !== false,
  })

  const onSave = () => {
    const config: PromoRulesConfigDto = {
      ...cfg,
      // script/advantages/partnerLabel/comparison — per-pair; общий уровень пуст.
      script: '',
      advantages: [],
      partnerLabel: null,
      comparison: [],
      // Цель — на уровне кампании: сохраняем как есть.
      goalLabel: cfg.goalLabel?.trim() || null,
      goalTarget: cfg.goalTarget ?? null,
      goalBonus: cfg.goalBonus ?? null,
      replacements: dedupe(cfg.replacements).map(cleanRef),
      crossSells: dedupe(cfg.crossSells).map(cleanRef),
    }
    save.mutate(
      { promoId, config },
      {
        onSuccess: () => toast.push(t('pr.savedToast')),
        onError: (e) => toast.push(describeError(e)),
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
            promotedPrice={promotedPrice}
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
            promotedPrice={promotedPrice}
            onToggle={toggleIn('crossSells')}
            onRemove={(idp) => removeFrom('crossSells', idp)}
            onPatch={(idp, p) => updatePair('crossSells', idp, p)}
          />

          {/* Цель — одна на всю кампанию (применяется ко всем парам). */}
          <Field label={t('pr.campaignGoal')} hint={t('pr.campaignGoalHint')}>
            <div className="grid grid-cols-3 gap-2">
              <Input
                value={cfg.goalLabel ?? ''}
                disabled={disabled}
                placeholder={t('pr.goalLabel')}
                data-testid="pr-goal-label"
                onChange={(e) => setCfg((c) => ({ ...c, goalLabel: e.target.value || null }))}
              />
              <Input
                type="number"
                value={cfg.goalTarget ?? ''}
                disabled={disabled}
                placeholder={t('pr.goalTarget')}
                data-testid="pr-goal-target"
                onChange={(e) =>
                  setCfg((c) => ({
                    ...c,
                    goalTarget: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
              <Input
                type="number"
                value={cfg.goalBonus ?? ''}
                disabled={disabled}
                placeholder={t('pr.goalBonus')}
                data-testid="pr-goal-bonus"
                onChange={(e) =>
                  setCfg((c) => ({
                    ...c,
                    goalBonus: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
            </div>
          </Field>

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
  promotedPrice,
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
  promotedPrice?: number | null
  onToggle: (p: StorefrontProductDto) => void
  onRemove: (medusaProductId: string) => void
  onPatch: (medusaProductId: string, patch: Partial<PromoRuleProductRef>) => void
}) {
  const t = useT()
  const [pickerOpen, setPickerOpen] = useState(false)
  const kind: PairKind = sectionKey === 'replacements' ? 'replacement' : 'crossSell'

  return (
    <Field label={t(titleKey)} hint={t(hintKey)}>
      {items.length === 0 ? (
        <div className="text-[12px] font-semibold text-ink-400">{t('pr.noneChosen')}</div>
      ) : (
        <ul className="flex flex-col gap-2" data-testid={`pr-list-${sectionKey}`}>
          {items.map((r) => (
            <PairCard
              key={r.medusaProductId}
              r={r}
              disabled={disabled}
              kind={kind}
              bonus={bonus}
              goal={goal}
              promotedName={promotedName}
              promotedPrice={promotedPrice}
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
        subtitle={t('pr.pickerSub')}
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
  promotedPrice,
  onRemove,
  onPatch,
}: {
  r: PromoRuleProductRef
  disabled: boolean
  kind: PairKind
  bonus: number
  goal: CampaignGoal
  promotedName?: string
  promotedPrice?: number | null
  onRemove: () => void
  onPatch: (patch: Partial<PromoRuleProductRef>) => void
}) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const cmp = r.comparison ?? []
  const setComparison = (rows: RuleComparisonRowDto[]) => onPatch({ comparison: rows })

  return (
    <li
      data-testid={`pr-chosen-${r.medusaProductId}`}
      className="hairline grid gap-3 rounded-xl border bg-paper-card p-3 lg:grid-cols-[minmax(0,1fr)_340px]"
    >
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-bold text-ink-900">{r.name}</div>
            {r.brand && (
              <div className="truncate text-[11px] font-semibold text-ink-500">{r.brand}</div>
            )}
          </div>
          {/* Статус именно этой пары: Активно / Черновик. */}
          <button
            type="button"
            disabled={disabled}
            onClick={() => onPatch({ active: r.active === false })}
            data-testid={`pr-status-${r.medusaProductId}`}
            aria-pressed={r.active !== false}
            className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-bold transition-colors ${
              r.active !== false
                ? 'bg-brand-green-50 text-brand-green-700'
                : 'bg-paper-input text-ink-500'
            } ${disabled ? 'cursor-default opacity-70' : 'hover:opacity-80'}`}
          >
            {r.active !== false ? t('pr.statusActive') : t('pr.statusDraft')}
          </button>
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

        <textarea
          className="inp text-[13px]"
          rows={2}
          value={r.script ?? ''}
          disabled={disabled}
          placeholder={t('pr.pairScriptPh')}
          aria-label={t('pr.pairScript')}
          data-testid={`pr-script-${r.medusaProductId}`}
          onChange={(e) => onPatch({ script: e.target.value })}
        />

        {/* Поля, которые показываются в блоке рекомендации на кассе (per-pair). */}
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="flex items-center gap-1.5 self-start text-[12px] font-bold text-brand-green-700"
          data-testid={`pr-card-toggle-${r.medusaProductId}`}
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
              <Input
                value={r.partnerLabel ?? ''}
                disabled={disabled}
                onChange={(e) => onPatch({ partnerLabel: e.target.value || null })}
                placeholder="ПАРТНЁР EPHARM"
              />
            </Field>

            <Field label={t('pr.comparison')} hint={t('pr.comparisonHint')}>
              <div className="flex flex-col gap-2">
                {cmp.map((row, i) => (
                  <div
                    key={i}
                    className="grid grid-cols-[1fr_1fr_1fr_auto] items-center gap-2"
                    data-testid={`pr-cmp-${r.medusaProductId}-${i}`}
                  >
                    <Input
                      value={row.label}
                      disabled={disabled}
                      onChange={(e) =>
                        setComparison(
                          cmp.map((x, idx) => (idx === i ? { ...x, label: e.target.value } : x)),
                        )
                      }
                      placeholder={t('pr.colLabel')}
                    />
                    <Input
                      value={row.triggerValue}
                      disabled={disabled}
                      onChange={(e) =>
                        setComparison(
                          cmp.map((x, idx) =>
                            idx === i ? { ...x, triggerValue: e.target.value } : x,
                          ),
                        )
                      }
                      placeholder={t('pr.colWas')}
                    />
                    <Input
                      value={row.recommendValue}
                      disabled={disabled}
                      onChange={(e) =>
                        setComparison(
                          cmp.map((x, idx) =>
                            idx === i ? { ...x, recommendValue: e.target.value } : x,
                          ),
                        )
                      }
                      placeholder={t('pr.colNow')}
                    />
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
                          onClick={() => setComparison(cmp.filter((_, idx) => idx !== i))}
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
                    onClick={() =>
                      setComparison([
                        ...cmp,
                        {
                          label: '',
                          triggerValue: '',
                          recommendValue: '',
                          recommendHighlight: false,
                        },
                      ])
                    }
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
 * (App/RecommendationWindow.xaml): шапка, предложение, сравнение/преимущества,
 * скрипт, низ с бонусом и кнопками. Статичное (без интерактива) — это превью.
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
  const comparison = (r.comparison ?? []).filter((row) => row.label.trim().length > 0)
  const fmtPrice = (p?: number | null) => (p != null ? `${p.toLocaleString('ru-RU')} ₸` : null)
  const isReplace = kind === 'replacement'
  const title = isReplace ? t('pr.previewReplace') : t('pr.previewCross')
  const actionLabel = isReplace ? t('pr.previewActReplace') : t('pr.previewActAdd')

  // Семантика как на кассе (backend PromoRulesService):
  //  • замена   — триггер = заменяемый товар (r), ПРЕДЛОЖИТЕ ВМЕСТО = товар кампании;
  //  • кросс-селл — триггер = товар кампании (УЖЕ В ЧЕКЕ), ДОБАВЬТЕ = компаньон (r).
  const triggerLabel = isReplace ? t('pr.previewAsked') : t('pr.previewInCart')
  const triggerName = isReplace ? r.name : promotedName
  const offerLabel = isReplace ? t('pr.previewOfferInstead') : t('pr.previewOfferAdd')
  const offerName = isReplace ? promotedName : r.name
  const offerPrice = fmtPrice(isReplace ? promotedPrice : r.price)

  const goalText =
    goal.label && goal.target != null ? `0/${goal.target} ${goal.label}` : goal.label || null

  return (
    <div className="lg:sticky lg:top-2 lg:self-start">
      <div className="mb-1.5 text-[11px] font-bold uppercase tracking-[0.05em] text-ink-400">
        {t('pr.previewTitle')}
      </div>
      <div className="overflow-hidden rounded-2xl border border-ink-200 bg-white shadow-sm">
        {/* Шапка */}
        <div className="flex items-center gap-2 bg-[#9A4427] px-3.5 py-2.5 text-white">
          <span className="flex h-5 w-5 items-center justify-center rounded-full bg-white/20 text-[12px] font-bold">
            i
          </span>
          <span className="text-[13px] font-extrabold">{title}</span>
          <span className="ml-auto text-[15px] leading-none text-white/60">×</span>
        </div>

        {/* Триггер: УЖЕ В ЧЕКЕ / ПОКУПАТЕЛЬ ПОПРОСИЛ */}
        {triggerName && (
          <div className="px-3.5 pb-1 pt-2.5">
            <div className="text-[10px] font-bold uppercase tracking-wide text-ink-400">
              {triggerLabel}
            </div>
            <div className="text-[13px] font-extrabold text-ink-900">{triggerName}</div>
          </div>
        )}

        {/* Предложение: ПРЕДЛОЖИТЕ ВМЕСТО / ДОБАВЬТЕ К ПОКУПКЕ */}
        <div className="mt-1.5 bg-[#F8E7DD] px-3.5 py-3">
          <div className="flex items-start justify-between gap-2">
            <span className="text-[10px] font-bold uppercase tracking-wide text-[#BE5A38]">
              {offerLabel}
            </span>
            {r.partnerLabel && (
              <span className="inline-block rounded-md bg-[#9A4427] px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-white">
                {r.partnerLabel}
              </span>
            )}
          </div>
          <div className="mt-1 text-[15px] font-extrabold leading-tight text-ink-900">
            {offerName || t('pr.previewNoName')}
          </div>
          {offerPrice && (
            <div className="mt-0.5 text-[15px] font-extrabold text-[#9A4427]">{offerPrice}</div>
          )}
        </div>

        {/* Сравнение ИЛИ преимущества */}
        {comparison.length > 0 ? (
          <table className="w-full border-collapse text-[12px]">
            <tbody>
              {comparison.map((row, i) => (
                <tr key={i} className="border-b border-ink-100 last:border-0">
                  <td className="px-3 py-1.5 font-semibold text-ink-500">{row.label}</td>
                  <td className="px-2 py-1.5 text-ink-400 line-through">{row.triggerValue}</td>
                  <td
                    className={`px-3 py-1.5 text-right font-bold ${
                      row.recommendHighlight ? 'text-[#BE5A38]' : 'text-ink-700'
                    }`}
                  >
                    {row.recommendValue}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : advantages.length > 0 ? (
          <ul className="flex flex-col gap-1 px-3.5 py-2.5">
            {advantages.map((a, i) => (
              <li key={i} className="flex items-start gap-2 text-[12px] text-ink-700">
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-[#D97757]" />
                {a}
              </li>
            ))}
          </ul>
        ) : null}

        {/* Скрипт */}
        {r.script?.trim() && (
          <div className="mx-3 my-2 flex items-start gap-2 rounded-lg bg-[#FAF7F2] px-3 py-2">
            <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded bg-[#9A4427] text-[10px] font-bold text-white">
              А
            </span>
            <span className="text-[12px] italic text-ink-600">{r.script.trim()}</span>
          </div>
        )}

        {/* Низ: бонус + цель + кнопки */}
        <div className="flex flex-col gap-2 px-3.5 pb-3 pt-1">
          <div className="flex items-center gap-2">
            <span className="rounded-lg bg-[#F8E7DD] px-2.5 py-1 text-center text-[12px] font-bold text-[#BE5A38]">
              +{bonus.toLocaleString('ru-RU')} ₸
              <span className="ml-1 text-[10px] font-semibold text-[#BE5A38]/80">
                {t('pr.previewBonusYou')}
              </span>
            </span>
            {goalText && <span className="text-[11px] font-semibold text-ink-400">{goalText}</span>}
          </div>
          <div className="flex gap-2">
            <span className="flex-1 rounded-lg bg-ink-100 py-1.5 text-center text-[12px] font-bold text-ink-500">
              {t('pr.previewSkip')}
            </span>
            <span className="flex-1 rounded-lg bg-[#9A4427] py-1.5 text-center text-[12px] font-bold text-white">
              {actionLabel}
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}
