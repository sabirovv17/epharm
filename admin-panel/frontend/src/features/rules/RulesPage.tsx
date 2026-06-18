// RulesPage — главный экран ТЗ §3.2 (Figure 32), READ-ONLY.
// Правила замены/кросс-селла создаются ТОЛЬКО из кампаний (раздел «Промо»).
// Здесь — просмотр: список + конфигурация/аналитика/превью кассы. Никаких мутаций.

import { useMemo, useState } from 'react'
import type { Rule } from '@/mocks/fixtures'
import {
  Button,
  Empty,
  PageHeader,
  SearchInput,
  Select,
  Tabs,
  type SelectOption,
  type TabItem,
} from '@/ui'
import { IconRules } from '@/ui/icons'
import { useProductLookup } from '@/lib/queries/catalog'
import { useRules } from '@/lib/queries/rules'
import { SummaryBar, computeMetrics } from './SummaryBar'
import { RuleRow } from './RuleRow'
import { RuleBuilder } from './RuleBuilder'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'

type RulesTab = 'substitution' | 'crosssell' | 'archive'
type StatusFilter = 'all' | 'active' | 'paused'

export default function RulesPage() {
  const t = useT()
  const productById = useProductLookup()

  const STATUS_OPTIONS: SelectOption[] = [
    { value: 'all', label: t('rules.statusAll') },
    { value: 'active', label: t('rules.statusActive') },
    { value: 'paused', label: t('rules.statusPaused') },
  ]

  const { data: rules = [], isLoading, isError, error, refetch } = useRules()
  const hasData = rules.length > 0
  const showWarningBanner = isError && hasData

  const [tab, setTab] = useState<RulesTab>('substitution')
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<StatusFilter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const { subst, cross, archive } = useMemo(() => {
    const s: Rule[] = []
    const c: Rule[] = []
    const a: Rule[] = []
    for (const r of rules) {
      if (r.status === 'archived') a.push(r)
      else if (r.type === 'substitution') s.push(r)
      else c.push(r)
    }
    return { subst: s, cross: c, archive: a }
  }, [rules])

  const selected = useMemo(
    () => rules.find((r) => r.id === selectedId) ?? null,
    [rules, selectedId],
  )

  const currentList: Rule[] = tab === 'substitution' ? subst : tab === 'crosssell' ? cross : archive

  const filtered = currentList.filter((r) => {
    if (filter !== 'all' && r.status !== filter && tab !== 'archive') return false
    if (!q) return true
    const trigName =
      r.trigger.kind === 'product'
        ? productById(r.trigger.value as string)?.name
        : (r.trigger.value as string)
    const recName = productById(r.recommend)?.name ?? ''
    return `${trigName ?? ''} ${recName}`.toLowerCase().includes(q.toLowerCase())
  })

  const metrics = useMemo(() => computeMetrics(subst, cross), [subst, cross])

  const tabItems: TabItem<RulesTab>[] = [
    { value: 'substitution', label: t('rules.tabSubst'), count: subst.length },
    { value: 'crosssell', label: t('rules.tabCross'), count: cross.length },
    { value: 'archive', label: t('rules.tabArchive'), count: archive.length },
  ]

  // При переключении таба выбираем первое правило нового таба (или сбрасываем).
  const onChangeTab = (v: RulesTab) => {
    setTab(v)
    const next = v === 'substitution' ? subst[0] : v === 'crosssell' ? cross[0] : archive[0]
    setSelectedId(next?.id ?? null)
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.rules.title')} subtitle={t('page.rules.subtitle')} />

      <div
        className="hairline rounded-xl border bg-brand-green-50 px-4 py-2.5 text-[12px] font-semibold text-ink-700"
        data-testid="rules-from-campaigns-banner"
      >
        {t('pr.standaloneBanner')}
      </div>

      <SummaryBar metrics={metrics} />

      {showWarningBanner && (
        <div className="hairline flex items-center gap-3 rounded-xl border bg-accent-warning/10 px-4 py-2 text-[12px] font-semibold text-ink-700">
          <span>{t('rules.staleWarn', { e: describeError(error) })}</span>
          <button
            type="button"
            onClick={() => refetch()}
            className="ml-auto font-bold text-brand-green-700 underline"
          >
            {t('common.retry')}
          </button>
        </div>
      )}

      {/* Main work area: list + read-only detail */}
      <div className="grid gap-4" style={{ gridTemplateColumns: 'minmax(0, 1fr) 480px' }}>
        {/* Left — rules list */}
        <div className="card flex min-h-[640px] flex-col">
          <div className="px-5 pt-4">
            <Tabs<RulesTab>
              value={tab}
              onChange={onChangeTab}
              items={tabItems}
              trailing={
                // flex-none — каждый input сохраняет ширину, не сжимается; если узко —
                // search+select переносятся на следующую строку (flex-wrap).
                <div className="flex flex-wrap items-center justify-end gap-2">
                  <SearchInput
                    value={q}
                    onChange={setQ}
                    placeholder={t('rules.searchPh')}
                    className="!h-9 !w-[220px] flex-none"
                  />
                  {tab !== 'archive' && (
                    <Select
                      value={filter}
                      onChange={(v) => setFilter(v as StatusFilter)}
                      options={STATUS_OPTIONS}
                      className="w-[170px] flex-none"
                    />
                  )}
                </div>
              }
            />
          </div>

          <div className="scrollbar-thin flex-1 overflow-auto">
            {isLoading && !hasData ? (
              <Empty
                title={t('rules.loading')}
                body={t('rules.loadingBody')}
                icon={<IconRules size={26} />}
              />
            ) : isError && !hasData ? (
              <Empty
                title={t('rules.errTitle')}
                body={describeError(error)}
                icon={<IconRules size={26} />}
                action={
                  <Button onClick={() => refetch()} variant="primary">
                    {t('common.retry')}
                  </Button>
                }
              />
            ) : filtered.length === 0 ? (
              <Empty
                title={t('rules.emptyTitle')}
                body={tab === 'archive' ? t('rules.emptyArchiveBody') : t('rules.emptyBody')}
                icon={<IconRules size={26} />}
              />
            ) : (
              <ul className="divide-hairline" data-testid="rules-list">
                {filtered.map((r) => (
                  <RuleRow
                    key={r.id}
                    rule={r}
                    selected={selectedId === r.id}
                    onSelect={() => setSelectedId(r.id)}
                  />
                ))}
              </ul>
            )}
          </div>
        </div>

        {/* Right — read-only detail panel */}
        <div
          className="card sticky top-[88px] flex min-h-[640px] flex-col self-start"
          data-testid="rule-builder-panel"
        >
          {selected ? (
            <RuleBuilder key={selected.id} rule={selected} />
          ) : (
            <Empty
              title={t('rules.selectTitle')}
              body={t('rules.selectBody')}
              icon={<IconRules size={26} />}
            />
          )}
        </div>
      </div>
    </div>
  )
}
