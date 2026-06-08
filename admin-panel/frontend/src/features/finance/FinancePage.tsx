// Finance — payout batches ТЗ §3.6.
// Этап 3.5: подключён backend через usePayoutBatches/usePayoutItems/useApproveBatch.

import { useMemo, useState } from 'react'
import { Button, Empty, Metric, PageHeader, Tabs, useToast, type TabItem } from '@/ui'
import { IconArrowUp, IconCheck, IconFinance, IconReconcile } from '@/ui/icons'
import type { PayoutBatchDto, PayoutItemDto } from '@/lib/api-types'
import {
  useApproveBatch,
  useGeneratePayout,
  usePayoutBatches,
  usePayoutItems,
} from '@/lib/queries/finance'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type FinTab = 'pending' | 'history'

export default function FinancePage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<FinTab>('pending')

  const { data: batches = [], isLoading, isError, error, refetch } = usePayoutBatches()
  const approveBatch = useApproveBatch()
  const generatePayout = useGeneratePayout()

  const handleGenerate = () => {
    if (!confirm(t('fin.confirmGenerate'))) return
    generatePayout.mutate(undefined, {
      onSuccess: (b) =>
        toast.push(t('fin.generated', { amount: formatKzt(b.amount), n: b.pharmacists })),
      onError: () => toast.push(t('fin.generateErr')),
    })
  }

  const hasData = batches.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData

  const pending = useMemo(() => batches.filter((b) => b.status === 'pending'), [batches])
  const approved = useMemo(() => batches.filter((b) => b.status === 'approved'), [batches])

  const currentPending: PayoutBatchDto | null = pending[0] ?? null
  const { data: items = [] } = usePayoutItems(currentPending?.id ?? null)

  const totalPendingAmount = pending.reduce((a, b) => a + b.amount, 0)
  const totalApprovedAmount = approved.reduce((a, b) => a + b.amount, 0)
  const totalApprovedItems = approved.reduce((a, b) => a + b.items, 0)
  const flaggedCount = items.filter((it) => it.flag).length

  const tabs: TabItem<FinTab>[] = [
    { value: 'pending', label: t('fin.tabPending'), count: pending.length },
    { value: 'history', label: t('fin.tabHistory'), count: approved.length },
  ]

  const handleApprove = (b: PayoutBatchDto) => {
    if (!confirm(t('fin.confirmApprove', { amount: formatKzt(b.amount), n: b.pharmacists }))) return
    approveBatch.mutate(b.id, {
      onSuccess: () => toast.push(t('fin.approved', { period: b.period })),
      onError: () => toast.push(t('fin.approveErr')),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.finance.title')}
        subtitle={t('page.finance.subtitle')}
        actions={
          <Button
            variant="primary"
            disabled={generatePayout.isPending}
            onClick={handleGenerate}
            data-testid="generate-payout"
          >
            {generatePayout.isPending ? t('fin.generating') : t('fin.generateBtn')}
          </Button>
        }
      />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('fin.mPending')}
          value={formatKzt(totalPendingAmount)}
          accent="amber"
          icon={<IconFinance size={18} />}
          sub={t('fin.pendingCount', { n: pending.length })}
        />
        <Metric
          label={t('fin.mPaid')}
          value={formatKzt(totalApprovedAmount)}
          accent="green"
          icon={<IconArrowUp size={18} />}
          sub={t('fin.approvedCount', { n: approved.length })}
        />
        <Metric label={t('fin.mPharm')} value={formatNum(totalApprovedItems)} accent="blue" />
        <Metric
          label={t('fin.mFlags')}
          value={formatNum(flaggedCount)}
          accent="purple"
          icon={<IconReconcile size={18} />}
        />
      </div>

      <div className="card flex min-h-[480px] flex-col">
        <div className="hairline border-b px-5 py-3">
          <Tabs<FinTab> items={tabs} value={tab} onChange={setTab} />
        </div>

        {showWarningBanner && (
          <div className="hairline border-b bg-accent-warning/10 px-5 py-2 text-[12px] font-semibold text-ink-700">
            {t('fin.warn', { e: describeError(error) })}{' '}
            <button
              type="button"
              onClick={() => refetch()}
              className="font-bold text-brand-green-700 underline"
            >
              {t('common.retry')}
            </button>
          </div>
        )}

        <div className="scrollbar-thin flex-1 overflow-auto">
          {isLoading && !hasData ? (
            <Empty
              title={t('fin.loading')}
              body={t('common.connecting')}
              icon={<IconFinance size={26} />}
            />
          ) : showFullError ? (
            <Empty
              title={t('fin.errTitle')}
              body={describeError(error)}
              icon={<IconFinance size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : tab === 'pending' ? (
            <PendingPanel
              batch={currentPending}
              items={items}
              onApprove={handleApprove}
              approving={approveBatch.isPending}
            />
          ) : (
            <HistoryPanel batches={approved} />
          )}
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// Panels
// ─────────────────────────────────────────────────────────────────────────

interface PendingPanelProps {
  batch: PayoutBatchDto | null
  items: PayoutItemDto[]
  onApprove: (b: PayoutBatchDto) => void
  approving: boolean
}

function PendingPanel({ batch, items, onApprove, approving }: PendingPanelProps) {
  const t = useT()
  if (!batch) {
    return (
      <Empty
        title={t('fin.noBatches')}
        body={t('fin.noBatchesBody')}
        icon={<IconFinance size={26} />}
      />
    )
  }
  return (
    <div className="flex flex-col">
      <div className="hairline flex items-center justify-between border-b px-5 py-3">
        <div>
          <div className="text-[14px] font-extrabold text-ink-900">{batch.period}</div>
          <div className="text-[12px] text-ink-500">
            {t('fin.batchMeta', { ph: batch.pharmacists, it: batch.items })}
            <span className="num font-bold text-ink-900">{formatKzt(batch.amount)}</span>
          </div>
        </div>
        <Button
          variant="primary"
          leading={<IconCheck size={14} />}
          disabled={approving}
          onClick={() => onApprove(batch)}
        >
          {approving ? t('fin.approving') : t('fin.approveBtn')}
        </Button>
      </div>
      <table className="w-full text-[13px]" data-testid="payout-items-table">
        <thead className="hairline border-b">
          <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
            <th className="px-5 py-2.5">{t('fin.thPharm')}</th>
            <th className="px-3 py-2.5">{t('fin.thPharmacy')}</th>
            <th className="px-3 py-2.5 text-right">{t('fin.thReceipts')}</th>
            <th className="px-3 py-2.5 text-right">{t('fin.thRules')}</th>
            <th className="px-3 py-2.5 text-right">{t('fin.thAmount')}</th>
            <th className="px-5 py-2.5">{t('fin.thFlag')}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-100">
          {items.map((it) => (
            <tr key={it.id} data-testid={`payout-item-${it.id}`}>
              <td className="px-5 py-2.5 font-extrabold text-ink-900">{it.pharmacistName}</td>
              <td className="px-3 py-2.5 text-ink-700">
                <div>{it.pharmacy}</div>
                <div className="text-[11px] text-ink-500">{it.city}</div>
              </td>
              <td className="num px-3 py-2.5 text-right">{formatNum(it.receipts)}</td>
              <td className="num px-3 py-2.5 text-right">{formatNum(it.rules)}</td>
              <td className="num px-3 py-2.5 text-right font-extrabold text-brand-green-700">
                {formatKzt(it.amount)}
              </td>
              <td className="px-5 py-2.5">
                {it.flag && <span className="chip chip-amber">{it.flag}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && (
        <div className="px-5 py-6 text-center text-[13px] text-ink-500">
          {t('fin.itemsLoading')}
        </div>
      )}
    </div>
  )
}

interface HistoryPanelProps {
  batches: PayoutBatchDto[]
}

function HistoryPanel({ batches }: HistoryPanelProps) {
  const t = useT()
  if (batches.length === 0) {
    return (
      <Empty
        title={t('fin.histEmpty')}
        body={t('fin.histEmptyBody')}
        icon={<IconFinance size={26} />}
      />
    )
  }
  return (
    <table className="w-full text-[13px]" data-testid="payout-history-table">
      <thead className="hairline border-b">
        <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
          <th className="px-5 py-2.5">{t('fin.thPeriod')}</th>
          <th className="px-3 py-2.5">{t('fin.thApprover')}</th>
          <th className="px-3 py-2.5 text-right">{t('fin.thPharmShort')}</th>
          <th className="px-3 py-2.5 text-right">{t('fin.thAmount')}</th>
          <th className="px-5 py-2.5">{t('fin.thApproved')}</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-ink-100">
        {batches.map((b) => (
          <tr key={b.id} data-testid={`payout-batch-${b.id}`}>
            <td className="px-5 py-2.5 font-extrabold text-ink-900">{b.period}</td>
            <td className="px-3 py-2.5 text-ink-700">{b.reviewerName ?? '—'}</td>
            <td className="num px-3 py-2.5 text-right">{formatNum(b.pharmacists)}</td>
            <td className="num px-3 py-2.5 text-right font-extrabold">{formatKzt(b.amount)}</td>
            <td className="num px-5 py-2.5 text-ink-500">
              {b.approvedAt ? b.approvedAt.slice(0, 10) : '—'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
