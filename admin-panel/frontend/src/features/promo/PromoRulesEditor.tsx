// PromoRulesEditor (T2) — секция «Замены и кросс-селл» на странице кампании.
// Авторинг правил рекомендации прямо из кампании:
//   • «Замены»     — товары, которые ЗАМЕНЯЕТ продвигаемый товар (substitution);
//   • «Кросс-селл» — товары, ВМЕСТЕ с которыми он предлагается (cross-sell);
//   • общий текст фармацевта: скрипт, преимущества, партнёр, сравнение, цель.
// GET /promo/{id}/rules при открытии → PUT при «Сохранить правила».
// Показывает «Активных: {activeCount} / Всего: {ruleCount}».

import { useEffect, useState } from 'react'
import { Button, Field, Input, Toggle, useToast } from '@/ui'
import { IconClose, IconPlus } from '@/ui/icons'
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
  }
}

export function PromoRulesEditor({
  promoId,
  disabled = false,
}: {
  promoId: string
  disabled?: boolean
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

  const patch = (p: Partial<PromoRulesConfigDto>) => setCfg((c) => ({ ...c, ...p }))

  // ── Замены / кросс-селл (toggle товара в списке) ──────────────────────────
  const toggleIn = (key: 'replacements' | 'crossSells') => (p: StorefrontProductDto) => {
    setCfg((c) => {
      const list = c[key]
      const exists = list.some((r) => r.medusaProductId === p.id)
      return {
        ...c,
        [key]: exists ? list.filter((r) => r.medusaProductId !== p.id) : [...list, toRef(p)],
      }
    })
  }
  const removeFrom = (key: 'replacements' | 'crossSells', medusaProductId: string) =>
    setCfg((c) => ({
      ...c,
      [key]: c[key].filter((r) => r.medusaProductId !== medusaProductId),
    }))

  // ── Сравнение (таблица) ────────────────────────────────────────────────────
  const addRow = () =>
    patch({
      comparison: [
        ...cfg.comparison,
        { label: '', triggerValue: '', recommendValue: '', recommendHighlight: false },
      ],
    })
  const updateRow = (i: number, p: Partial<RuleComparisonRowDto>) =>
    patch({ comparison: cfg.comparison.map((r, idx) => (idx === i ? { ...r, ...p } : r)) })
  const removeRow = (i: number) =>
    patch({ comparison: cfg.comparison.filter((_, idx) => idx !== i) })

  const onSave = () => {
    // Чистим пустые строки сравнения/преимуществ перед отправкой.
    const config: PromoRulesConfigDto = {
      ...cfg,
      advantages: cfg.advantages.map((a) => a.trim()).filter((a) => a.length > 0),
      comparison: cfg.comparison.filter((r) => r.label.trim().length > 0),
      script: cfg.script.trim(),
      partnerLabel: cfg.partnerLabel?.trim() || null,
      goalLabel: cfg.goalLabel?.trim() || null,
    }
    save.mutate(
      { promoId, config },
      {
        onSuccess: () => toast.push(t('pr.savedToast')),
        onError: (e) => toast.push(describeError(e)),
      },
    )
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
          {/* Замены */}
          <Field label={t('pr.replacements')} hint={t('pr.replacementsHint')}>
            <ChosenList
              items={cfg.replacements}
              onRemove={(idp) => removeFrom('replacements', idp)}
              disabled={disabled}
              emptyText={t('pr.noneChosen')}
            />
            {!disabled && (
              <div className="mt-2">
                <MultiProductPicker
                  selectedIds={cfg.replacements.map((r) => r.medusaProductId)}
                  onPick={toggleIn('replacements')}
                />
              </div>
            )}
          </Field>

          {/* Кросс-селл */}
          <Field label={t('pr.crossSells')} hint={t('pr.crossSellsHint')}>
            <ChosenList
              items={cfg.crossSells}
              onRemove={(idp) => removeFrom('crossSells', idp)}
              disabled={disabled}
              emptyText={t('pr.noneChosen')}
            />
            {!disabled && (
              <div className="mt-2">
                <MultiProductPicker
                  selectedIds={cfg.crossSells.map((r) => r.medusaProductId)}
                  onPick={toggleIn('crossSells')}
                />
              </div>
            )}
          </Field>

          {/* Скрипт + преимущества */}
          <Field label={t('pr.script')} hint={t('pr.scriptHint')}>
            <textarea
              className="inp"
              rows={3}
              value={cfg.script}
              disabled={disabled}
              onChange={(e) => patch({ script: e.target.value })}
            />
          </Field>
          <Field label={t('pr.advantages')} hint={t('pr.advantagesHint')}>
            <textarea
              className="inp"
              rows={3}
              value={cfg.advantages.join('\n')}
              disabled={disabled}
              onChange={(e) => patch({ advantages: e.target.value.split('\n') })}
            />
          </Field>

          {/* Партнёр */}
          <Field label={t('pr.partnerLabel')}>
            <Input
              value={cfg.partnerLabel ?? ''}
              disabled={disabled}
              onChange={(e) => patch({ partnerLabel: e.target.value || null })}
              placeholder="ПАРТНЁР EPHARM"
            />
          </Field>

          {/* Сравнение */}
          <Field label={t('pr.comparison')} hint={t('pr.comparisonHint')}>
            <div className="flex flex-col gap-2">
              {cfg.comparison.map((row, i) => (
                <div
                  key={i}
                  className="hairline grid grid-cols-[1fr_1fr_1fr_auto] items-center gap-2 rounded-lg border bg-paper-input p-2"
                  data-testid={`pr-cmp-row-${i}`}
                >
                  <Input
                    value={row.label}
                    disabled={disabled}
                    onChange={(e) => updateRow(i, { label: e.target.value })}
                    placeholder={t('pr.colLabel')}
                  />
                  <Input
                    value={row.triggerValue}
                    disabled={disabled}
                    onChange={(e) => updateRow(i, { triggerValue: e.target.value })}
                    placeholder={t('pr.colWas')}
                  />
                  <Input
                    value={row.recommendValue}
                    disabled={disabled}
                    onChange={(e) => updateRow(i, { recommendValue: e.target.value })}
                    placeholder={t('pr.colNow')}
                  />
                  <div className="flex items-center gap-1.5">
                    <Toggle
                      on={row.recommendHighlight}
                      onChange={(on) => updateRow(i, { recommendHighlight: on })}
                    />
                    {!disabled && (
                      <button
                        type="button"
                        onClick={() => removeRow(i)}
                        aria-label={t('pr.removeRow')}
                        data-testid={`pr-cmp-remove-${i}`}
                        className="text-ink-400 transition-colors hover:text-accent-danger"
                      >
                        <IconClose size={14} />
                      </button>
                    )}
                  </div>
                </div>
              ))}
              {!disabled && (
                <Button variant="outline" onClick={addRow} leading={<IconPlus size={14} />}>
                  {t('pr.addRow')}
                </Button>
              )}
            </div>
          </Field>

          {/* Цель */}
          <div className="grid grid-cols-3 gap-3">
            <Field label={t('pr.goalLabel')}>
              <Input
                value={cfg.goalLabel ?? ''}
                disabled={disabled}
                onChange={(e) => patch({ goalLabel: e.target.value || null })}
              />
            </Field>
            <Field label={t('pr.goalTarget')}>
              <Input
                type="number"
                value={cfg.goalTarget ?? ''}
                disabled={disabled}
                onChange={(e) =>
                  patch({ goalTarget: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </Field>
            <Field label={t('pr.goalBonus')}>
              <Input
                type="number"
                value={cfg.goalBonus ?? ''}
                disabled={disabled}
                onChange={(e) =>
                  patch({ goalBonus: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </Field>
          </div>

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

function ChosenList({
  items,
  onRemove,
  disabled,
  emptyText,
}: {
  items: PromoRuleProductRef[]
  onRemove: (medusaProductId: string) => void
  disabled: boolean
  emptyText: string
}) {
  if (items.length === 0) {
    return <div className="text-[12px] font-semibold text-ink-400">{emptyText}</div>
  }
  return (
    <ul className="flex flex-col gap-1.5">
      {items.map((r) => (
        <li
          key={r.medusaProductId}
          data-testid={`pr-chosen-${r.medusaProductId}`}
          className="hairline flex items-center gap-2 rounded-lg border bg-paper-card px-3 py-2"
        >
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-bold text-ink-900">{r.name}</div>
            {r.brand && (
              <div className="truncate text-[11px] font-semibold text-ink-500">{r.brand}</div>
            )}
          </div>
          {!disabled && (
            <button
              type="button"
              onClick={() => onRemove(r.medusaProductId)}
              aria-label="remove"
              className="text-ink-400 transition-colors hover:text-accent-danger"
            >
              <IconClose size={14} />
            </button>
          )}
        </li>
      ))}
    </ul>
  )
}
