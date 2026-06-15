// Lift — Pilot vs Control аналитика прироста продаж.
// РЕАЛЬНЫЕ данные: конверсия принятых рекомендаций pilot vs control (backend
// агрегирует recommendation_events), networkLift и P-value (z-тест) — настоящие.
// insufficientData=true (нет показов в группах) → показываем «—»/«мало данных».

import { Button, Empty, Metric, PageHeader, SectionCard } from '@/ui'
import { IconArrowUp, IconLift, IconPharmacy, IconShield } from '@/ui/icons'
import { useT } from '@/i18n'
import { useLiftSummary } from '@/lib/queries/lift'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'

export default function LiftPage() {
  const t = useT()
  const { data: s, isError, error, refetch } = useLiftSummary()

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.lift.title')} subtitle={t('page.lift.subtitle')} />

      {isError && !s && (
        <Empty
          title={t('lift.errTitle')}
          body={describeError(error)}
          icon={<IconLift size={26} />}
          action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
        />
      )}

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('lift.mNetwork')}
          value={s && !s.insufficientData ? `+${s.networkLiftPct}%` : '—'}
          accent="green"
          icon={<IconArrowUp size={16} />}
          sub={s ? t('lift.receiptsPilot', { n: formatNum(s.pilotReceipts30d) }) : undefined}
        />
        <Metric
          label="P-value"
          value={s?.pValue != null ? String(s.pValue) : '—'}
          accent="blue"
          sub={
            !s || s.insufficientData
              ? t('lift.notEnoughShort')
              : s.pValue != null && s.pValue < 0.05
                ? t('lift.significant')
                : t('lift.notSignificant')
          }
        />
        <Metric
          label={t('lift.mPilot')}
          value={formatNum(s?.pilotCount ?? 0)}
          accent="purple"
          icon={<IconPharmacy size={16} />}
        />
        <Metric
          label={t('lift.mControl')}
          value={formatNum(s?.controlCount ?? 0)}
          accent="ink"
          icon={<IconShield size={16} />}
        />
      </div>

      <SectionCard title={t('lift.weeklyTitle')} subtitle={t('lift.weeklySub')}>
        <Empty
          title={t('lift.notEnough')}
          body={t('lift.weeklyBody')}
          icon={<IconLift size={26} />}
        />
      </SectionCard>

      <SectionCard title={t('lift.segTitle')} subtitle={t('lift.segSub')}>
        {!s || s.segments.length === 0 ? (
          <Empty
            title={t('lift.segEmpty')}
            body={t('lift.segEmptyBody')}
            icon={<IconLift size={22} />}
          />
        ) : (
          <ul className="flex flex-col" data-testid="lift-segments">
            {s.segments.map((seg) => (
              <li
                key={seg.name}
                data-testid={`lift-segment-${seg.name}`}
                className="hairline flex items-center gap-3 border-b py-2.5 last:border-b-0"
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13px] font-extrabold text-ink-900">{seg.name}</div>
                  <div className="text-[11px] text-ink-500">
                    {t('lift.segPharm', { n: formatNum(seg.pharmacies) })}
                  </div>
                </div>
                <span className="num text-[13px] font-extrabold text-brand-green-700">
                  +{seg.liftPct}%
                </span>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  )
}
