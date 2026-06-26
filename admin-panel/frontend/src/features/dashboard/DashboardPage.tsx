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
import { useDashboardSummary, useRecommendationAnalytics } from '@/lib/queries/dashboard'
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

      {/* Показано рекомендаций — конверсия показ→продажа + время до продажи (V032) */}
      <RecommendationsSection />
    </div>
  )
}

/** Формат «время до продажи»: «45 с» / «1 мин 5 с» / «3 мин». */
function fmtSecs(s: number): string {
  if (s < 60) return `${s} с`
  const m = Math.floor(s / 60)
  const r = s % 60
  return r > 0 ? `${m} мин ${r} с` : `${m} мин`
}

/**
 * Точное дата/время события (дд.мм чч:мм:сс). Рендерим в ЛОКАЛЬНОМ поясе зрителя (без фиксации
 * timeZone): кто из какого региона открыл админку — у того и пояс. Бэкенд отдаёт UTC (ISO с 'Z'),
 * браузер сам переводит в местное время.
 */
function fmtWhen(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function MiniKpi({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="hairline rounded-xl border p-3">
      <div className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-500">{label}</div>
      <div
        className={`num mt-1 text-[18px] font-extrabold ${accent ? 'text-brand-green-700' : 'text-ink-900'}`}
      >
        {value}
      </div>
    </div>
  )
}

/**
 * Раздел «Журнал показов и продаж» (V032): KPI конверсии показ→продажа и времени до продажи +
 * единый лог событий из ДВУХ таблиц (показы + продажи) с точным временем (Задача 1 + 1.2).
 */
function RecommendationsSection() {
  const t = useT()
  const { data: a, isLoading } = useRecommendationAnalytics()
  const log = a?.log ?? []

  return (
    <SectionCard title={t('recan.title')} subtitle={t('recan.subtitle')}>
      <div
        className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5"
        data-testid="recan-kpis"
      >
        <MiniKpi label={t('recan.shown')} value={formatNum(a?.shown ?? 0)} />
        <MiniKpi
          label={t('recan.converted')}
          value={`${formatNum(a?.converted ?? 0)} · ${a?.convRate ?? 0}%`}
          accent
        />
        <MiniKpi label={t('recan.under2m')} value={formatNum(a?.convertedUnder2m ?? 0)} />
        <MiniKpi
          label={t('recan.avgTime')}
          value={a?.avgSecondsToSale != null ? fmtSecs(a.avgSecondsToSale) : '—'}
        />
        <MiniKpi label={t('recan.revenue')} value={formatKzt(a?.attributedRevenue ?? 0)} />
      </div>

      {log.length === 0 ? (
        <Empty
          title={isLoading ? t('dash.loading') : t('recan.empty')}
          icon={<IconEye size={22} />}
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]" data-testid="recan-table">
            <thead className="hairline border-b">
              <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                <th className="py-2.5 pr-3">{t('recan.colTime')}</th>
                <th className="py-2.5 pr-3">{t('recan.colKind')}</th>
                <th className="py-2.5 pr-3">{t('recan.colWhat')}</th>
                <th className="py-2.5 pr-3">{t('recan.colPharmacy')}</th>
                <th className="py-2.5 pr-3">{t('recan.colPharmacist')}</th>
                <th className="py-2.5 text-right">{t('recan.colAmount')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {log.map((e) => (
                <tr
                  key={`${e.type}-${e.id}`}
                  data-testid={`recan-row-${e.id}`}
                  className="hover:bg-paper-hover"
                >
                  <td className="num whitespace-nowrap py-2.5 pr-3 text-[11px] text-ink-500">
                    {fmtWhen(e.at)}
                  </td>
                  <td className="py-2.5 pr-3">
                    <span className={`chip ${e.type === 'sale' ? 'chip-green' : 'chip-blue'}`}>
                      {e.type === 'sale' ? t('recan.kindSale') : t('recan.kindShow')}
                    </span>
                  </td>
                  <td className="py-2.5 pr-3 font-extrabold text-ink-900">
                    {e.title}
                    {e.type === 'show' && e.converted && (
                      <span className="chip chip-green ml-2 whitespace-nowrap font-normal">
                        {t('recan.soldIn', { t: fmtSecs(e.secondsToSale ?? 0) })}
                      </span>
                    )}
                  </td>
                  <td className="py-2.5 pr-3 text-ink-600">{e.pharmacyName}</td>
                  <td className="py-2.5 pr-3 text-ink-600">{e.pharmacistName}</td>
                  <td className="num py-2.5 text-right font-bold text-ink-900">
                    {formatKzt(e.amount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
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
