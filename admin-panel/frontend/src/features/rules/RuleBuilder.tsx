// RuleBuilder — правая панель Rules Engine (read-only).
// 3 таба: Параметры (просмотр конфигурации) / Аналитика / Превью кассы.
// Правила создаются ТОЛЬКО из кампаний (PromoRulesEditor) — здесь никаких мутаций.

import { useState } from 'react'
import { formatKzt, formatNum, PHARMACY_LIST, type Rule } from '@/mocks/fixtures'
import { Button, Metric, SectionCard, Sparkline, Tabs, ComingSoonBanner, type TabItem } from '@/ui'
import { IconCheck, IconEye, IconSpark } from '@/ui/icons'
import { useProductLookup } from '@/lib/queries/catalog'
import { ruleSummary } from './lib'
import { useT } from '@/i18n'
import { ProductIcon } from './ProductBlock'

type BuilderTab = 'config' | 'analytics' | 'preview'

interface RuleBuilderProps {
  rule: Rule
}

export function RuleBuilder({ rule }: RuleBuilderProps) {
  const t = useT()
  // tab сбрасывается на «config» при смене правила за счёт key={rule.id} в RulesPage
  // (remount), поэтому effect-сброс не нужен.
  const [tab, setTab] = useState<BuilderTab>('config')
  const productById = useProductLookup()

  const BUILDER_TABS: TabItem<BuilderTab>[] = [
    { value: 'config', label: t('rules.btConfig') },
    { value: 'analytics', label: t('rules.btAnalytics') },
    { value: 'preview', label: t('rules.btPreview') },
  ]

  const updatedAtShort = rule.updatedAt ? rule.updatedAt.slice(0, 10) : '—'
  const statusChip =
    rule.status === 'active'
      ? { cls: 'chip-green', label: t('rules.stActive') }
      : rule.status === 'archived'
        ? { cls: 'chip-ink', label: t('rules.stArchived') }
        : { cls: 'chip-ink', label: t('rules.stPaused') }

  return (
    <div className="flex h-full flex-col">
      {/* Header — статус read-only (чип), без тоггла. */}
      <div className="hairline border-b px-5 pb-3 pt-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="mb-1 text-[11px] font-bold uppercase tracking-[0.06em] text-ink-400">
              {t('rules.hMeta', {
                type: rule.type === 'substitution' ? t('rules.tReplace') : t('rules.tabCross'),
                id: rule.id,
                n: rule.pharmacies,
                date: updatedAtShort,
              })}
            </div>
            <div className="truncate text-[16px] font-extrabold text-ink-900">
              {ruleSummary(rule, productById)}
            </div>
          </div>
          <span className={`chip flex-none ${statusChip.cls}`}>{statusChip.label}</span>
        </div>
      </div>

      {/* Tabs */}
      <div className="px-5">
        <Tabs<BuilderTab> items={BUILDER_TABS} value={tab} onChange={setTab} />
      </div>

      <div className="scrollbar-thin flex-1 overflow-auto px-5 py-4">
        {tab === 'config' && <RuleConfigView rule={rule} />}
        {tab === 'analytics' && <RuleAnalytics rule={rule} />}
        {tab === 'preview' && <RulePreview rule={rule} />}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// RuleConfigView — read-only просмотр конфигурации правила
// ─────────────────────────────────────────────────────────────────────────

function RuleConfigView({ rule }: { rule: Rule }) {
  const t = useT()
  const productById = useProductLookup()
  const rec = productById(rule.recommend)
  const trigName =
    rule.trigger.kind === 'product'
      ? (productById(rule.trigger.value as string)?.name ?? (rule.trigger.value as string))
      : rule.trigger.kind === 'product_any'
        ? (rule.trigger.value as string[]).map((id) => productById(id)?.name ?? id).join(', ')
        : (rule.trigger.value as string)
  const advantages = (rule.advantages ?? []).filter((a) => a.trim().length > 0)
  const card = rule.card
  const comparison = (card?.comparison ?? []).filter((r) => r.label.trim().length > 0)
  const hasGoal = !!card?.goalLabel?.trim()

  return (
    <div className="flex flex-col gap-3" data-testid="rule-config-view">
      <ReadOnlyRow label={t('rules.cfgTrigger')} value={trigName || '—'} />
      <ReadOnlyRow label={t('rules.cfgRecommend')} value={rec?.name ?? rule.recommend} />
      <ReadOnlyRow label={t('rules.cfgBonus')} value={`+${formatKzt(rule.bonus)}`} />

      {rule.script?.trim() && (
        <div className="card-soft p-3">
          <div className="mb-1 text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('rules.cfgScript')}
          </div>
          <div className="text-[13px] italic leading-relaxed text-ink-700">
            «{rule.script.trim()}»
          </div>
        </div>
      )}

      {advantages.length > 0 && (
        <div className="card-soft p-3">
          <div className="mb-1.5 text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('rules.cfgAdvantages')}
          </div>
          <ul className="flex flex-col gap-1.5">
            {advantages.map((a, i) => (
              <li key={i} className="flex items-start gap-2 text-[13px] font-semibold text-ink-700">
                <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-md bg-brand-green-100 text-brand-green-700">
                  <IconCheck size={12} />
                </span>
                {a}
              </li>
            ))}
          </ul>
        </div>
      )}

      {comparison.length > 0 && (
        <div className="card-soft p-3">
          <div className="mb-1.5 text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('rules.cfgComparison')}
          </div>
          <table className="w-full text-[12px]">
            <tbody>
              {comparison.map((row, i) => (
                <tr key={i} className="hairline border-b last:border-0">
                  <td className="py-1.5 font-semibold text-ink-500">{row.label}</td>
                  <td className="py-1.5 text-ink-400 line-through">{row.triggerValue}</td>
                  <td
                    className={`py-1.5 text-right font-bold ${
                      row.recommendHighlight ? 'text-brand-green-700' : 'text-ink-700'
                    }`}
                  >
                    {row.recommendValue}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {card?.partnerLabel?.trim() && (
        <ReadOnlyRow label={t('rules.cardPartner')} value={card.partnerLabel.trim()} />
      )}
      {hasGoal && (
        <ReadOnlyRow
          label={t('rules.cfgGoal')}
          value={[
            card!.goalLabel!.trim(),
            card!.goalTarget != null ? `${card!.goalTarget}` : null,
            card!.goalBonus != null ? `+${formatKzt(card!.goalBonus)}` : null,
          ]
            .filter(Boolean)
            .join(' · ')}
        />
      )}

      <div className="hairline rounded-lg border border-dashed bg-paper-hover px-3 py-2.5 text-[12px] font-semibold leading-snug text-ink-500">
        {t('rules.readOnlyHint')}
      </div>
    </div>
  )
}

function ReadOnlyRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="hairline flex items-center justify-between gap-3 rounded-lg border bg-paper-input px-3 py-2.5">
      <span className="flex-none text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">
        {label}
      </span>
      <span className="min-w-0 truncate text-right text-[13px] font-extrabold text-ink-900">
        {value}
      </span>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// RuleAnalytics — таб «Аналитика»
// ─────────────────────────────────────────────────────────────────────────

function RuleAnalytics({ rule }: { rule: Rule }) {
  const t = useT()
  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Metric
          label={t('rules.mShown')}
          value={formatNum(rule.impressions)}
          accent="ink"
          icon={<IconEye size={16} />}
        />
        <Metric
          label={t('rules.mAccepted')}
          value={formatNum(rule.accepts)}
          accent="green"
          icon={<IconCheck size={16} />}
          delta={+3.2}
        />
        <Metric label={t('rules.aConv')} value={`${rule.convRate.toFixed(1)}%`} accent="green" />
        <Metric label={t('rules.aRevenue')} value={formatKzt(rule.revenue)} accent="blue" />
        <Metric label={t('rules.mPaid')} value={formatKzt(rule.payout)} accent="amber" />
        <Metric label={t('rules.aPharm')} value={rule.pharmacies} accent="purple" />
      </div>
      <SectionCard title={t('rules.dyn14')} padded={false}>
        <div className="px-5 py-3">
          <Sparkline values={rule.spark ?? []} width={460} height={120} />
        </div>
      </SectionCard>
      <SectionCard title={t('rules.topPharm')} padded={false}>
        <ul className="divide-hairline">
          {PHARMACY_LIST.slice(0, 5).map((p, i) => (
            <li key={p.id} className="flex items-center gap-3 px-4 py-2.5">
              <span className="num w-5 text-[11px] font-bold text-ink-400">{i + 1}</span>
              <div className="min-w-0 flex-1">
                <div className="truncate text-[13px] font-bold text-ink-900">{p.name}</div>
                <div className="text-[11px] font-semibold text-ink-500">{p.city}</div>
              </div>
              <span className="num text-[13px] font-extrabold text-ink-900">{8 + i * 4}</span>
            </li>
          ))}
        </ul>
      </SectionCard>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// RulePreview — таб «Превью» (как фармацевт увидит на кассе)
// ─────────────────────────────────────────────────────────────────────────

export function RulePreview({ rule }: { rule: Rule }) {
  const t = useT()
  const productById = useProductLookup()
  const trig =
    rule.trigger.kind === 'product'
      ? productById(rule.trigger.value as string)
      : rule.trigger.kind === 'product_any'
        ? productById((rule.trigger.value as string[])[0])
        : null
  const rec = productById(rule.recommend)
  const isSubst = rule.type === 'substitution'

  return (
    <div className="flex flex-col gap-3">
      <ComingSoonBanner title={t('rules.pvTitle')} body={t('rules.pvBody')} />
      <div className="card-soft border-brand-green-200/60 bg-gradient-to-br from-brand-green-50 to-white p-4">
        <div className="mb-2 flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.06em] text-brand-green-700">
          <IconSpark size={12} />
          {t('rules.pvHint')}
        </div>
        <div className="mb-3 text-[16px] font-extrabold text-ink-900">
          {isSubst
            ? t('rules.pvReplace', { name: rec?.name ?? '—' })
            : t('rules.pvOffer', { name: rec?.name ?? '—' })}
        </div>
        {rule.script && (
          <div className="hairline mb-3 rounded-xl border bg-white p-3 text-sm leading-relaxed text-ink-700">
            «{rule.script}»
          </div>
        )}
        <div className="flex flex-col gap-1.5">
          {(rule.advantages ?? []).map((a, i) => (
            <div key={i} className="flex items-start gap-2">
              <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-md bg-brand-green-100 text-brand-green-700">
                <IconCheck size={12} />
              </span>
              <span className="text-[13px] font-semibold text-ink-700">{a}</span>
            </div>
          ))}
        </div>
        <div className="hairline mt-4 flex items-center justify-between border-t pt-3">
          <div>
            <div className="text-[11px] font-bold uppercase text-ink-400">{t('rules.pvBonus')}</div>
            <div className="num text-[20px] font-extrabold text-brand-green-700">
              +{formatKzt(rule.bonus)}
            </div>
          </div>
          <Button variant="primary" size="md">
            {t('rules.pvAccept')}
          </Button>
        </div>
      </div>
      {trig && (
        <div className="card-soft flex items-center gap-3 p-3">
          <ProductIcon product={trig} size={36} />
          <div className="flex-1">
            <div className="text-[12px] font-bold uppercase text-ink-400">
              {t('rules.pvTriggersOn')}
            </div>
            <div className="text-[13px] font-extrabold text-ink-900">{trig.name}</div>
          </div>
        </div>
      )}
    </div>
  )
}
