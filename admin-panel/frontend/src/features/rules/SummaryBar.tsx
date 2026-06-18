// SummaryBar — admin §10.2 — горизонтальный 4-секционный бар вместо тяжёлых 4 KPI.
// Каждая секция: цветной dot + uppercase label + value + sub-text + optional delta-chip.
import { formatKzt, formatNum, type Rule } from '@/mocks/fixtures'
import { IconArrowDown, IconArrowUp } from '@/ui/icons'
import { useT } from '@/i18n'

export interface RulesMetrics {
  active: number
  total: number
  impressions: number
  accepts: number
  payout: number
  conv: number
}

export const computeMetrics = (subst: Rule[], cross: Rule[]): RulesMetrics => {
  const total = subst.length + cross.length
  const active =
    subst.filter((r) => r.status === 'active').length +
    cross.filter((r) => r.status === 'active').length
  const impressions =
    subst.reduce((a, r) => a + r.impressions, 0) + cross.reduce((a, r) => a + r.impressions, 0)
  const accepts =
    subst.reduce((a, r) => a + r.accepts, 0) + cross.reduce((a, r) => a + r.accepts, 0)
  const payout = subst.reduce((a, r) => a + r.payout, 0) + cross.reduce((a, r) => a + r.payout, 0)
  return {
    active,
    total,
    impressions,
    accepts,
    payout,
    conv: impressions ? (accepts / impressions) * 100 : 0,
  }
}

interface BarItem {
  label: string
  value: string
  sub: string
  dot: string
  delta?: number
}

export function SummaryBar({ metrics }: { metrics: RulesMetrics }) {
  const t = useT()
  const items: BarItem[] = [
    {
      label: t('rules.mActive'),
      value: `${metrics.active}`,
      sub: t('rules.ofN', { n: metrics.total }),
      dot: '#D97757', // коралл-PRIMARY
    },
    {
      label: t('rules.mShown'),
      value: formatNum(metrics.impressions),
      sub: t('rules.recsSub'),
      dot: '#BE5A38', // коралл-акцент
    },
    {
      label: t('rules.mAccepted'),
      value: formatNum(metrics.accepts),
      sub: t('rules.convSub', { c: metrics.conv.toFixed(1) }),
      dot: '#BE5A38', // глубокий коралл
      delta: +4.8,
    },
    {
      label: t('rules.mPaid'),
      value: formatKzt(metrics.payout),
      sub: t('rules.budgetMay'),
      dot: '#F4B73A',
    },
  ]
  return (
    <div className="card flex items-stretch divide-x divide-ink-100 overflow-hidden">
      {items.map((it) => (
        <div key={it.label} className="flex flex-1 items-center gap-3 px-5 py-3.5">
          <span className="h-9 w-1.5 flex-none rounded-full" style={{ background: it.dot }} />
          <div className="min-w-0 flex-1">
            <div className="truncate text-[11px] font-bold uppercase tracking-[0.04em] text-ink-500">
              {it.label}
            </div>
            <div className="mt-0.5 flex items-baseline gap-2">
              <span className="num text-[19px] font-extrabold leading-none text-ink-900">
                {it.value}
              </span>
              {it.sub && (
                <span className="truncate text-[12px] font-semibold text-ink-500">{it.sub}</span>
              )}
            </div>
          </div>
          {it.delta != null && (
            <span className={`chip flex-none ${it.delta >= 0 ? 'chip-green' : 'chip-red'}`}>
              {it.delta >= 0 ? <IconArrowUp size={10} /> : <IconArrowDown size={10} />}
              {Math.abs(it.delta).toFixed(1)}%
            </span>
          )}
        </div>
      ))}
    </div>
  )
}
