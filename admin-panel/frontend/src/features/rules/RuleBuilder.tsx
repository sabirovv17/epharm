// RuleBuilder — правая панель Rules Engine.
// 3 таба: Конструктор / Аналитика / Превью. Local state с dirty-flag.

import { useEffect, useMemo, useState, type ChangeEvent } from 'react'
import { formatKzt, formatNum, PHARMACY_LIST, type Rule } from '@/mocks/fixtures'
import { useProducts, useProductLookup } from '@/lib/queries/catalog'
import {
  Button,
  Field,
  Input,
  Metric,
  SectionCard,
  Sparkline,
  Tabs,
  Toggle,
  ComingSoonBanner,
  Select,
  IconButton,
  type SelectOption,
  type TabItem,
} from '@/ui'
import {
  IconBox,
  IconCheck,
  IconChevDown,
  IconChevRight,
  IconClose,
  IconEye,
  IconLayers,
  IconSpark,
  IconStack,
  IconSwap,
} from '@/ui/icons'
import { ruleSummary } from './lib'
import { useT } from '@/i18n'
import { ProductIcon } from './ProductBlock'

type BuilderTab = 'builder' | 'analytics' | 'preview'

interface RuleBuilderProps {
  rule: Rule
  onSave: (patch: Partial<Rule>) => void
  onToggle: () => void
}

export function RuleBuilder({ rule, onSave, onToggle }: RuleBuilderProps) {
  const t = useT()
  const [local, setLocal] = useState<Rule>(rule)
  const [tab, setTab] = useState<BuilderTab>('builder')
  const productById = useProductLookup()

  const BUILDER_TABS: TabItem<BuilderTab>[] = [
    { value: 'builder', label: t('rules.btBuilder') },
    { value: 'analytics', label: t('rules.btAnalytics') },
    { value: 'preview', label: t('rules.btPreview') },
  ]

  // Sync local когда:
  //  - выбрали другое правило (rule.id)
  //  - сервер вернул обновлённую версию (rule.updatedAt) после save/toggle/archive
  // Так local не клобберит user's edits во время набора, но синхронизируется когда
  // mutation succeeded.
  useEffect(() => {
    setLocal(rule)
    setTab('builder')
  }, [rule.id, rule.updatedAt]) // eslint-disable-line react-hooks/exhaustive-deps

  // Игнорируем «техническое» поле updatedAt при определении dirty — оно меняется
  // на сервере при каждом save, и без этого фильтра save-кнопка остаётся жёлтой.
  const dirty = useMemo(() => {
    const omit = ({ updatedAt: _u, createdAt: _c, ...rest }: Rule) => rest
    return JSON.stringify(omit(local)) !== JSON.stringify(omit(rule))
  }, [local, rule])

  // Статус читаем из rule напрямую — Toggle это side-action, не часть формы.
  // Если читать из local, после mutation visual-state не обновится.
  const isArchive = rule.status === 'archived'
  const updatedAtShort = rule.updatedAt ? rule.updatedAt.slice(0, 10) : '—'

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
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
              {ruleSummary(local, productById)}
            </div>
          </div>
          {!isArchive && (
            <div className="flex flex-none items-center gap-2">
              <Toggle on={rule.status === 'active'} onChange={onToggle} />
              <span className="text-[12px] font-bold text-ink-700">
                {rule.status === 'active' ? t('rules.stActive') : t('rules.stPaused')}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="px-5">
        <Tabs<BuilderTab> items={BUILDER_TABS} value={tab} onChange={setTab} />
      </div>

      <div className="scrollbar-thin flex-1 overflow-auto px-5 py-4">
        {tab === 'builder' && <BuilderForm value={local} onChange={setLocal} />}
        {tab === 'analytics' && <RuleAnalytics rule={local} />}
        {tab === 'preview' && <RulePreview rule={local} />}
      </div>

      {/* Footer — только в Builder-табе */}
      {tab === 'builder' && (
        <div className="hairline flex items-center gap-2 rounded-b-2xl border-t bg-paper-hover px-5 py-3">
          {dirty && <span className="chip chip-amber">{t('rules.unsaved')}</span>}
          <Button
            variant="ghost"
            size="md"
            className="ml-auto"
            disabled={!dirty}
            onClick={() => setLocal(rule)}
          >
            {t('common.cancel')}
          </Button>
          <Button
            variant="primary"
            size="md"
            disabled={!dirty}
            onClick={() => onSave(local)}
            leading={<IconCheck size={14} />}
          >
            {t('common.save')}
          </Button>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// BuilderForm — 4 form-блока (Trigger / Recommendation / Bonus / Дополнительно)
// ─────────────────────────────────────────────────────────────────────────

interface BuilderFormProps {
  value: Rule
  onChange: (next: Rule) => void
}

export function BuilderForm({ value, onChange }: BuilderFormProps) {
  const t = useT()
  const v = value
  const set = (patch: Partial<Rule>) => onChange({ ...v, ...patch })

  const { data: products = [] } = useProducts()
  const productById = useProductLookup()
  const productOptions: SelectOption[] = products.map((p) => ({
    value: p.id,
    label: `${p.name} · ${p.brand}`,
  }))
  const mnnOptions: SelectOption[] = [
    'Морская вода',
    'Ксилометазолин',
    'Оксиметазолин',
    'Парацетамол',
    'Ибупрофен',
    'Лоратадин',
    'Амилметакрезол',
  ]

  const productAnyValues = Array.isArray(v.trigger.value) ? v.trigger.value : []

  return (
    <div className="flex flex-col gap-5">
      {/* TRIGGER */}
      <FormBlock
        step={1}
        title={t('rules.fbTrigger')}
        subtitle={t('rules.fbTriggerSub')}
        color="blue"
      >
        <div className="mb-3 flex gap-2">
          {(
            [
              { val: 'product', label: t('rules.trProduct'), icon: <IconBox size={14} /> },
              { val: 'mnn', label: t('rules.mnnGroup'), icon: <IconLayers size={14} /> },
              { val: 'product_any', label: t('rules.trGroup'), icon: <IconStack size={14} /> },
            ] as const
          ).map((opt) => (
            <button
              key={opt.val}
              type="button"
              onClick={() =>
                set({
                  trigger: { kind: opt.val, value: opt.val === 'product_any' ? [] : '' },
                })
              }
              className={`flex h-11 flex-1 items-center justify-center gap-2 rounded-lg border text-[13px] font-bold transition ${
                v.trigger.kind === opt.val
                  ? 'border-brand-green-600 bg-brand-green-50 text-brand-green-700'
                  : 'border-ink-200 text-ink-700 hover:bg-paper-hover'
              }`}
            >
              {opt.icon}
              {opt.label}
            </button>
          ))}
        </div>
        {v.trigger.kind === 'product' && (
          <Field label={t('rules.fldTriggerProduct')}>
            <Select
              value={v.trigger.value as string}
              onChange={(val) => set({ trigger: { ...v.trigger, value: val } })}
              options={productOptions}
              placeholder={t('rules.selectProduct')}
            />
          </Field>
        )}
        {v.trigger.kind === 'mnn' && (
          <Field label={t('rules.fldMnn')} hint={t('rules.mnnHint')}>
            <Select
              value={v.trigger.value as string}
              onChange={(val) => set({ trigger: { ...v.trigger, value: val } })}
              options={mnnOptions}
              placeholder={t('rules.selectMnn')}
            />
          </Field>
        )}
        {v.trigger.kind === 'product_any' && (
          <Field label={t('rules.fldAnyProducts')}>
            <div className="hairline flex min-h-[42px] flex-wrap gap-1.5 rounded-lg border bg-paper-input p-2">
              {productAnyValues.map((id) => (
                <span key={id} className="chip chip-blue">
                  {productById(id)?.name.split(' ').slice(0, 3).join(' ')}
                  <button
                    type="button"
                    onClick={() =>
                      set({
                        trigger: {
                          ...v.trigger,
                          value: productAnyValues.filter((x) => x !== id),
                        },
                      })
                    }
                  >
                    <IconClose size={12} />
                  </button>
                </span>
              ))}
              <Select
                value=""
                onChange={(id) => {
                  if (id && !productAnyValues.includes(id)) {
                    set({ trigger: { ...v.trigger, value: [...productAnyValues, id] } })
                  }
                }}
                options={productOptions}
                placeholder={t('rules.addMore')}
              />
            </div>
          </Field>
        )}
      </FormBlock>

      {/* RECOMMENDATION */}
      <FormBlock step={2} title={t('rules.fbRec')} subtitle={t('rules.fbRecSub')} color="green">
        <Field label={t('rules.fldRecProduct')}>
          <Select
            value={v.recommend}
            onChange={(val) => set({ recommend: val })}
            options={productOptions}
          />
        </Field>
        <Field label={t('rules.fldAdvantages')} hint={t('rules.advHint')}>
          <textarea
            className="inp"
            rows={3}
            value={(v.advantages ?? []).join('\n')}
            onChange={(e: ChangeEvent<HTMLTextAreaElement>) =>
              // Сохраняем raw split без filter(Boolean) — иначе пустые строки
              // при наборе мгновенно вылетают (cursor jump, нельзя начать новый
              // bullet нажатием Enter). Чистку выполняет RulesPage.handleSave
              // перед отправкой на backend.
              set({ advantages: e.target.value.split('\n') })
            }
          />
        </Field>
        <Field label={t('rules.fldScript')} hint={t('rules.scriptHint')}>
          <textarea
            className="inp"
            rows={3}
            value={v.script ?? ''}
            onChange={(e: ChangeEvent<HTMLTextAreaElement>) => set({ script: e.target.value })}
          />
        </Field>
      </FormBlock>

      {/* BONUS */}
      <FormBlock step={3} title={t('rules.fbBonus')} subtitle={t('rules.fbBonusSub')} color="amber">
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('rules.fldSumPack')}>
            <Input
              type="number"
              value={v.bonus}
              onChange={(e) => set({ bonus: Number(e.target.value) || 0 })}
            />
          </Field>
          <Field label={t('rules.fldMaxDay')} hint={t('rules.maxDayHint')}>
            <Input type="number" defaultValue="0" placeholder={t('rules.noLimitPh')} />
          </Field>
        </div>
      </FormBlock>

      {/* META — A/B test + dates */}
      <FormBlock
        step={4}
        title={t('rules.fbMeta')}
        subtitle={t('rules.fbMetaSub')}
        color="purple"
        collapsible
        defaultCollapsed
      >
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('rules.abTest')}>
            <div className="flex items-center gap-2">
              <Toggle
                on={!!v.abTest}
                onChange={(on) => set({ abTest: on ? { variant: 'A', share: 50 } : null })}
              />
              <span className="text-[13px] font-semibold text-ink-700">
                {v.abTest
                  ? t('rules.abVariant', { v: v.abTest.variant, s: v.abTest.share })
                  : t('rules.abOff')}
              </span>
            </div>
          </Field>
          <Field label={t('rules.fldUntil')}>
            <Input type="text" defaultValue="31.05.2026" />
          </Field>
          <Field label={t('rules.fldCities')}>
            <Input defaultValue={t('rules.allCities')} />
          </Field>
          <Field label={t('rules.fldChains')}>
            <Input defaultValue={t('rules.allChains')} />
          </Field>
        </div>
      </FormBlock>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// FormBlock — обёртка для шага формы с цветным step-индикатором
// ─────────────────────────────────────────────────────────────────────────

type StepColor = 'blue' | 'green' | 'amber' | 'purple'

interface FormBlockProps {
  step: number
  title: string
  subtitle?: string
  color: StepColor
  collapsible?: boolean
  defaultCollapsed?: boolean
  children: React.ReactNode
}

const STEP_TINT: Record<StepColor, { bg: string; fg: string }> = {
  blue: { bg: '#E8EAFE', fg: '#2A2BE2' },
  green: { bg: '#D7F5E4', fg: '#0F8F55' },
  amber: { bg: '#FEF3C7', fg: '#B45309' },
  purple: { bg: '#F3E8FF', fg: '#7C3AED' },
}

function FormBlock({
  step,
  title,
  subtitle,
  color,
  collapsible,
  defaultCollapsed,
  children,
}: FormBlockProps) {
  const [open, setOpen] = useState(!defaultCollapsed)
  const tint = STEP_TINT[color]
  return (
    <div className="card-soft p-4">
      <div className="mb-3 flex items-center gap-3">
        <span
          className="flex h-8 w-8 items-center justify-center rounded-lg text-[14px] font-extrabold"
          style={{ background: tint.bg, color: tint.fg }}
        >
          {step}
        </span>
        <div className="flex-1">
          <div className="text-sm font-extrabold text-ink-900">{title}</div>
          {subtitle && <div className="text-[12px] font-semibold text-ink-500">{subtitle}</div>}
        </div>
        {collapsible && (
          <IconButton onClick={() => setOpen((o) => !o)}>
            {open ? <IconChevDown size={16} /> : <IconChevRight size={16} />}
          </IconButton>
        )}
      </div>
      {open && <div className="flex flex-col gap-3">{children}</div>}
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

// Re-export для CreateRuleModal (использует BuilderForm + RulePreview + step-кнопки)
export { IconSwap, IconStack }
