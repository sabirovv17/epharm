// Dashboard — admin §3.2 / ТЗ §3.2 — обзорный экран HQ-команды.
// Этап 3.6: подключён backend через useDashboardSummary (read-only агрегация).
// KPI + топ-3 списка из реальных данных. Time-series блоки (lift-график, heatmap)
// остаются Empty — временных рядов в БД пока нет (честно, без фейковых цифр).

import { Button, Empty, Metric, PageHeader, SectionCard } from '@/ui'
import {
  IconArrowUp,
  IconBox,
  IconCheck,
  IconEye,
  IconPharmacist,
  IconReconcile,
  IconRules,
} from '@/ui/icons'
import { useDashboardSummary } from '@/lib/queries/dashboard'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

export default function DashboardPage() {
  const t = useT()
  const { data: s, isLoading, isError, error, refetch } = useDashboardSummary()

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title={t('page.dashboard.title')} subtitle={t('page.dashboard.subtitle')} />

      {isError && !s && (
        <Empty
          title={t('dash.errTitle')}
          body={describeError(error)}
          icon={<IconReconcile size={26} />}
          action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
        />
      )}

      {/* 4 KPI tiles */}
      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('dash.mImpr')}
          value={formatNum(s?.impressions ?? 0)}
          accent="ink"
          icon={<IconEye size={16} />}
          meta={isLoading ? t('dash.loading') : t('dash.period')}
        />
        <Metric
          label={t('dash.mAccepts')}
          value={formatNum(s?.accepts ?? 0)}
          sub={t('dash.conv', { v: s?.convRate ?? 0 })}
          accent="green"
          icon={<IconCheck size={16} />}
        />
        <Metric
          label={t('dash.mActiveRules')}
          value={formatNum(s?.activeRules ?? 0)}
          sub={t('dash.ofN', { n: formatNum(s?.totalRules ?? 0) })}
          accent="blue"
          icon={<IconRules size={16} />}
        />
        <Metric
          label={t('dash.mPaid')}
          value={formatKzt(s?.paidOut ?? 0)}
          accent="amber"
          icon={<IconArrowUp size={16} />}
          meta={t('dash.pending', { v: formatKzt(s?.pendingPayout ?? 0) })}
        />
      </div>

      {/* Двухколоночный блок: график lift + heatmap (time-series — данных пока нет) */}
      <div className="grid grid-cols-2 gap-5">
        <SectionCard title={t('dash.dynTitle')} subtitle={t('dash.dynSub')}>
          <Empty
            title={t('dash.notEnough')}
            body={t('dash.dynBody')}
            icon={<IconReconcile size={26} />}
          />
        </SectionCard>

        <SectionCard title={t('dash.heatTitle')} subtitle={t('dash.heatSub')}>
          <Empty
            title={t('dash.noActivity')}
            body={t('dash.heatBody')}
            icon={<IconPharmacist size={26} />}
          />
        </SectionCard>
      </div>

      {/* Топ списки */}
      <div className="grid grid-cols-3 gap-4">
        <SectionCard title={t('dash.topRules')} subtitle={t('dash.topRulesSub')}>
          <TopList
            rows={(s?.topRules ?? []).map((r) => ({
              id: r.id,
              primary: r.label,
              secondary: t('dash.accepted', { n: formatNum(r.accepts) }),
              value: `${r.convRate}%`,
            }))}
            testid="dash-top-rules"
            emptyIcon={<IconRules size={22} />}
          />
        </SectionCard>
        <SectionCard title={t('dash.topPharm')} subtitle={t('dash.topPharmSub')}>
          <TopList
            rows={(s?.topPharmacies ?? []).map((p) => ({
              id: p.id,
              primary: p.name,
              secondary: p.city,
              value: formatNum(p.rulesAccepted),
            }))}
            testid="dash-top-pharmacies"
            emptyIcon={<IconPharmacist size={22} />}
          />
        </SectionCard>
        <SectionCard title={t('dash.topProd')} subtitle={t('dash.topProdSub')}>
          <TopList
            rows={(s?.topProducts ?? []).map((p) => ({
              id: p.id,
              primary: p.name,
              secondary: t('dash.revFromSubs'),
              value: formatKzt(p.revenue),
            }))}
            testid="dash-top-products"
            emptyIcon={<IconBox size={22} />}
          />
        </SectionCard>
      </div>
    </div>
  )
}

interface TopRow {
  id: string
  primary: string
  secondary: string
  value: string
}

function TopList({
  rows,
  testid,
  emptyIcon,
}: {
  rows: TopRow[]
  testid: string
  emptyIcon: React.ReactNode
}) {
  const t = useT()
  if (rows.length === 0) {
    return <Empty title={t('common.noData')} icon={emptyIcon} />
  }
  return (
    <ul className="flex flex-col" data-testid={testid}>
      {rows.map((r, i) => (
        <li
          key={r.id}
          data-testid={`${testid}-${r.id}`}
          className="hairline flex items-center gap-3 border-b py-2.5 last:border-b-0"
        >
          <span className="num w-5 text-[12px] font-bold text-ink-400">{i + 1}</span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-extrabold text-ink-900">{r.primary}</div>
            <div className="truncate text-[11px] text-ink-500">{r.secondary}</div>
          </div>
          <span className="num text-[13px] font-extrabold text-brand-green-700">{r.value}</span>
        </li>
      ))}
    </ul>
  )
}
