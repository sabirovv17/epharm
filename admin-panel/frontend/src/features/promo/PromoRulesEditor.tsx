// PromoRulesEditor (T2 + UX-редизайн) — секция «Замены и кросс-селл» на странице кампании.
//
// Модель: 1 кампания = 1 продвигаемый товар (выбран при создании, тут НЕ меняется).
// Здесь админ настраивает по нему правила:
//   • «Замены»     — товары, которые ЗАМЕНЯЕТ продвигаемый товар (substitution);
//   • «Кросс-селл» — товары, ВМЕСТЕ с которыми он предлагается (cross-sell).
// Для КАЖДОЙ пары — своё описание «что сказать фармацевту и почему» (per-pair script),
// которое уходит в rules.script именно этого правила → видно на кассе.
// Товары добавляются по кнопке «Добавить» (модалка с поиском), а не вечным списком.
// Общая «богатая карточка» (преимущества/сравнение/цель/партнёр) — в сворачиваемом блоке.

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
  const [advancedOpen, setAdvancedOpen] = useState(false)

  // Populate from server config on load / promo change.
  useEffect(() => {
    if (data) setCfg(data.config)
  }, [data])

  // Сбрасываем раскрытие advanced-блока при переходе на другую кампанию,
  // чтобы он не оставался открытым с данными прошлой кампании.
  useEffect(() => setAdvancedOpen(false), [promoId])

  const patch = (p: Partial<PromoRulesConfigDto>) => setCfg((c) => ({ ...c, ...p }))

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
  const setPairScript = (key: ListKey, medusaProductId: string, script: string) =>
    setCfg((c) => ({
      ...c,
      [key]: c[key].map((r) => (r.medusaProductId === medusaProductId ? { ...r, script } : r)),
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

  // Дедуп по medusaProductId (UI и так не даёт дублей, но защищаемся от случайных
  // дублей перед отправкой — иначе backend молча схлопнул бы их distinctBy).
  const dedupe = (list: PromoRuleProductRef[]) => [
    ...new Map(list.map((r) => [r.medusaProductId, r])).values(),
  ]

  const onSave = () => {
    const config: PromoRulesConfigDto = {
      ...cfg,
      // Скрипт теперь per-pair; общий не используем.
      script: '',
      replacements: dedupe(cfg.replacements).map((r) => ({
        ...r,
        script: (r.script ?? '').trim(),
      })),
      crossSells: dedupe(cfg.crossSells).map((r) => ({ ...r, script: (r.script ?? '').trim() })),
      advantages: cfg.advantages.map((a) => a.trim()).filter((a) => a.length > 0),
      comparison: cfg.comparison.filter((r) => r.label.trim().length > 0),
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
          <RuleSection
            sectionKey="replacements"
            titleKey="pr.replacements"
            hintKey="pr.replacementsHint"
            addLabelKey="pr.addReplacement"
            items={cfg.replacements}
            disabled={disabled}
            onToggle={toggleIn('replacements')}
            onRemove={(idp) => removeFrom('replacements', idp)}
            onScript={(idp, s) => setPairScript('replacements', idp, s)}
          />

          <RuleSection
            sectionKey="crossSells"
            titleKey="pr.crossSells"
            hintKey="pr.crossSellsHint"
            addLabelKey="pr.addCrossSell"
            items={cfg.crossSells}
            disabled={disabled}
            onToggle={toggleIn('crossSells')}
            onRemove={(idp) => removeFrom('crossSells', idp)}
            onScript={(idp, s) => setPairScript('crossSells', idp, s)}
          />

          {/* Расширенная карточка — общее для всей кампании (необязательно). */}
          <div className="hairline rounded-xl border">
            <button
              type="button"
              onClick={() => setAdvancedOpen((o) => !o)}
              className="flex w-full items-center justify-between px-3 py-2.5 text-left"
              data-testid="pr-advanced-toggle"
            >
              <span className="text-[13px] font-bold text-ink-700">{t('pr.advancedSection')}</span>
              <IconChevDown
                size={16}
                className={`text-ink-400 transition-transform ${advancedOpen ? 'rotate-180' : ''}`}
              />
            </button>
            {advancedOpen && (
              <div className="hairline flex flex-col gap-4 border-t p-3">
                <Field label={t('pr.advantages')} hint={t('pr.advantagesHint')}>
                  <textarea
                    className="inp"
                    rows={3}
                    value={cfg.advantages.join('\n')}
                    disabled={disabled}
                    onChange={(e) => patch({ advantages: e.target.value.split('\n') })}
                  />
                </Field>

                <Field label={t('pr.partnerLabel')}>
                  <Input
                    value={cfg.partnerLabel ?? ''}
                    disabled={disabled}
                    onChange={(e) => patch({ partnerLabel: e.target.value || null })}
                    placeholder="ПАРТНЁР EPHARM"
                  />
                </Field>

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
              </div>
            )}
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

/**
 * Секция правил (замены ИЛИ кросс-селл): список выбранных пар-карточек с per-pair
 * скриптом + кнопка «Добавить» (открывает модалку-поиск). Полный список товаров
 * больше НЕ висит на странице — только по кнопке.
 */
function RuleSection({
  sectionKey,
  titleKey,
  hintKey,
  addLabelKey,
  items,
  disabled,
  onToggle,
  onRemove,
  onScript,
}: {
  sectionKey: ListKey
  titleKey: string
  hintKey: string
  addLabelKey: string
  items: PromoRuleProductRef[]
  disabled: boolean
  onToggle: (p: StorefrontProductDto) => void
  onRemove: (medusaProductId: string) => void
  onScript: (medusaProductId: string, script: string) => void
}) {
  const t = useT()
  const [pickerOpen, setPickerOpen] = useState(false)

  return (
    <Field label={t(titleKey)} hint={t(hintKey)}>
      {items.length === 0 ? (
        <div className="text-[12px] font-semibold text-ink-400">{t('pr.noneChosen')}</div>
      ) : (
        <ul className="flex flex-col gap-2" data-testid={`pr-list-${sectionKey}`}>
          {items.map((r) => (
            <li
              key={r.medusaProductId}
              data-testid={`pr-chosen-${r.medusaProductId}`}
              className="hairline flex flex-col gap-2 rounded-xl border bg-paper-card p-3"
            >
              <div className="flex items-center gap-2">
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
                    aria-label={t('pr.removePair')}
                    data-testid={`pr-remove-${r.medusaProductId}`}
                    className="text-ink-400 transition-colors hover:text-accent-danger"
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
                onChange={(e) => onScript(r.medusaProductId, e.target.value)}
              />
            </li>
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
