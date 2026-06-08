// Reconcile — сверка чеков (ТЗ §3.5). Очередь чеков с реального backend +
// ручные действия модератора (одобрить → начисление бонуса / отклонить).
// 3 ветки потока (auto ~80% / ручная ~15% / анти-фрод ~5%) — описаны внизу.

import { useRef, useState, type ChangeEvent } from 'react'
import { Button, Empty, Metric, PageHeader, SectionCard, Tabs, useToast, type TabItem } from '@/ui'
import { IconAlert, IconCheck, IconClock, IconReceipt, IconShield } from '@/ui/icons'
import type { ReceiptDto, ReceiptStatus } from '@/lib/api-types'
import {
  useApproveReceipt,
  useImportExcel,
  useReceipts,
  useReconcileSummary,
  useRejectReceipt,
} from '@/lib/queries/reconcile'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type RecTab = ReceiptStatus

export default function ReconcilePage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<RecTab>('pending')
  const fileRef = useRef<HTMLInputElement>(null)
  const importExcel = useImportExcel()

  const summaryQ = useReconcileSummary()
  const s = summaryQ.data
  const { data: receipts = [], isLoading, isError, error, refetch } = useReceipts(tab)

  const TABS: TabItem<RecTab>[] = [
    { value: 'pending', label: t('rec.tab.pending'), count: s?.queue ?? 0 },
    {
      value: 'moderation_required',
      label: t('rec.tab.moderation'),
      count: s?.moderationRequired ?? 0,
    },
    { value: 'flagged', label: t('rec.tab.flagged'), count: s?.flagged ?? 0 },
    { value: 'approved', label: t('rec.tab.approved'), count: s?.approved ?? 0 },
    { value: 'rejected', label: t('rec.tab.rejected'), count: s?.rejected ?? 0 },
  ]

  const onPickFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = '' // позволяем выбрать тот же файл повторно
    if (!file) return
    importExcel.mutate(file, {
      onSuccess: (res) =>
        toast.push(t('rec.importDone', { total: res.rowsTotal, matched: res.rowsMatched })),
      onError: () => toast.push(t('rec.importErr')),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.reconcile.title')}
        subtitle={t('page.reconcile.subtitle')}
        actions={
          <>
            <input
              ref={fileRef}
              type="file"
              accept=".xlsx,.xls"
              className="hidden"
              onChange={onPickFile}
              data-testid="excel-input"
            />
            <Button
              variant="outline"
              disabled={importExcel.isPending}
              onClick={() => fileRef.current?.click()}
              data-testid="import-excel"
            >
              {t('rec.importExcel')}
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('rec.mQueue')}
          value={s?.queue ?? 0}
          accent="ink"
          icon={<IconClock size={16} />}
          meta={t('rec.mQueueMeta')}
        />
        <Metric
          label={t('rec.mAuto')}
          value={s?.autoApproved ?? 0}
          accent="green"
          icon={<IconCheck size={16} />}
        />
        <Metric
          label={t('rec.mManual')}
          value={s?.moderationRequired ?? 0}
          accent="amber"
          icon={<IconAlert size={16} />}
        />
        <Metric
          label={t('rec.mFraud')}
          value={s?.flagged ?? 0}
          accent="purple"
          icon={<IconShield size={16} />}
        />
      </div>

      <div className="card flex min-h-[420px] flex-col">
        <div className="px-5 pt-4">
          <Tabs<RecTab> items={TABS} value={tab} onChange={setTab} />
        </div>
        <div className="flex-1">
          {isLoading && receipts.length === 0 ? (
            <Empty
              title={t('rec.loading')}
              body={t('common.connecting')}
              icon={<IconReceipt size={26} />}
            />
          ) : isError && receipts.length === 0 ? (
            <Empty
              title={t('rec.errTitle')}
              body={describeError(error)}
              icon={<IconReceipt size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : receipts.length === 0 ? (
            <Empty
              title={t('rec.empty')}
              body={t(`rec.body.${tab}`)}
              icon={<IconReceipt size={26} />}
            />
          ) : (
            <table className="w-full text-[13px]" data-testid="reconcile-table">
              <thead className="hairline border-b">
                <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                  <th className="px-5 py-2.5">{t('rec.thReceipt')}</th>
                  <th className="px-3 py-2.5">{t('rec.thPharmacist')}</th>
                  <th className="px-3 py-2.5 text-right">{t('rec.thAmount')}</th>
                  <th className="px-3 py-2.5 text-right">{t('rec.thScore')}</th>
                  <th className="px-3 py-2.5">{t('rec.thStatus')}</th>
                  <th className="px-3 py-2.5">{t('rec.thSources')}</th>
                  <th className="px-5 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {receipts.map((r) => (
                  <ReceiptRow key={r.id} r={r} />
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <SectionCard title={t('rec.howTitle')} subtitle={t('rec.howSub')}>
        <div className="grid grid-cols-3 gap-3 text-[13px]">
          <div className="card-soft p-4">
            <div className="mb-2 flex items-center gap-2 text-brand-green-700">
              <IconCheck size={16} />
              <span className="text-sm font-extrabold">{t('rec.auto80')}</span>
            </div>
            <div className="text-ink-500">{t('rec.autoBody')}</div>
          </div>
          <div className="card-soft p-4">
            <div className="mb-2 flex items-center gap-2 text-accent-warning">
              <IconAlert size={16} />
              <span className="text-sm font-extrabold">{t('rec.manual15')}</span>
            </div>
            <div className="text-ink-500">{t('rec.manualBody')}</div>
          </div>
          <div className="card-soft p-4">
            <div className="mb-2 flex items-center gap-2 text-accent-danger">
              <IconShield size={16} />
              <span className="text-sm font-extrabold">{t('rec.fraud5')}</span>
            </div>
            <div className="text-ink-500">{t('rec.fraudBody')}</div>
          </div>
        </div>
      </SectionCard>
    </div>
  )
}

function StatusCell({ r }: { r: ReceiptDto }) {
  const t = useT()
  const cls =
    r.status === 'approved'
      ? 'chip-green'
      : r.status === 'rejected'
        ? 'chip-ink'
        : r.status === 'flagged'
          ? 'chip-amber'
          : r.status === 'moderation_required'
            ? 'chip-amber'
            : 'chip-blue'
  const label =
    r.status === 'approved'
      ? t('rec.statusApproved')
      : r.status === 'rejected'
        ? t('rec.statusRejected')
        : r.status === 'flagged'
          ? t('rec.statusFlagged')
          : r.status === 'moderation_required'
            ? t('rec.statusModeration')
            : t('rec.statusPending')
  const flagKey = r.flagReason ? `rec.flag.${r.flagReason}` : null
  return (
    <div className="flex flex-col gap-0.5">
      <span className={`chip ${cls} w-fit`}>{label}</span>
      {r.status === 'approved' && r.autoApproved && (
        <span className="text-[10px] font-bold uppercase tracking-[0.06em] text-brand-green-700">
          {t('rec.auto')}
        </span>
      )}
      {flagKey && (r.status === 'flagged' || r.status === 'rejected') && (
        <span className="text-[10px] text-accent-danger">{t(flagKey)}</span>
      )}
    </div>
  )
}

function SourcesCell({ r }: { r: ReceiptDto }) {
  const t = useT()
  if (!r.confirmedByLog && !r.confirmedByExcel) {
    return <span className="text-[11px] text-ink-400">—</span>
  }
  return (
    <div className="flex flex-wrap gap-1">
      {r.confirmedByLog && <span className="chip chip-blue w-fit">{t('rec.srcLog')}</span>}
      {r.confirmedByExcel && <span className="chip chip-green w-fit">{t('rec.srcExcel')}</span>}
    </div>
  )
}

function ReceiptRow({ r }: { r: ReceiptDto }) {
  const t = useT()
  const toast = useToast()
  const approve = useApproveReceipt()
  const reject = useRejectReceipt()
  const pending = approve.isPending || reject.isPending
  const actionable =
    r.status === 'pending' || r.status === 'flagged' || r.status === 'moderation_required'

  const onApprove = () => {
    if (!confirm(t('rec.confirmApprove', { name: r.pharmacistName }))) return
    approve.mutate(r.id, {
      onSuccess: () => toast.push(t('rec.approvedToast')),
      onError: () => toast.push(t('rec.actionErr')),
    })
  }

  const onReject = () => {
    const reason = window.prompt(t('rec.rejectPrompt'))
    if (!reason || !reason.trim()) return
    reject.mutate(
      { id: r.id, reason: reason.trim() },
      {
        onSuccess: () => toast.push(t('rec.rejectedToast')),
        onError: () => toast.push(t('rec.actionErr')),
      },
    )
  }

  return (
    <tr data-testid={`receipt-${r.id}`}>
      <td className="px-5 py-2.5">
        <div className="font-extrabold text-ink-900">{r.parsedSku || '—'}</div>
        <div className="text-[11px] text-ink-500">
          {r.photoUrl ? (
            <a
              href={r.photoUrl}
              target="_blank"
              rel="noreferrer"
              className="text-brand-green-700 underline"
            >
              {t('rec.viewPhoto')}
            </a>
          ) : (
            t('rec.noPhoto')
          )}
          {r.parsedCashier ? ` · ${t('rec.cashier', { v: r.parsedCashier })}` : ''}
        </div>
      </td>
      <td className="px-3 py-2.5">
        <div className="font-semibold text-ink-900">{r.pharmacistName}</div>
        <div className="text-[11px] text-ink-500">{r.pharmacyName}</div>
      </td>
      <td className="px-3 py-2.5 text-right">
        <div className="num font-extrabold text-ink-900">{formatKzt(r.parsedAmount)}</div>
        {r.expectedAmount != null && (
          <div className="num text-[11px] text-ink-500">
            {t('rec.colExpected', { v: formatKzt(r.expectedAmount) })}
          </div>
        )}
        {r.bonusCredited > 0 && (
          <div className="num text-[11px] font-bold text-brand-green-700">
            {t('rec.bonusCredited', { v: formatKzt(r.bonusCredited) })}
          </div>
        )}
      </td>
      <td className="num px-3 py-2.5 text-right">{(r.ocrScore * 100).toFixed(0)}%</td>
      <td className="px-3 py-2.5">
        <StatusCell r={r} />
      </td>
      <td className="px-3 py-2.5">
        <SourcesCell r={r} />
      </td>
      <td className="px-5 py-2.5">
        {actionable && (
          <div className="flex items-center justify-end gap-2">
            <Button
              variant="primary"
              size="sm"
              disabled={pending}
              onClick={onApprove}
              data-testid={`receipt-approve-${r.id}`}
            >
              {t('rec.approve')}
            </Button>
            <Button
              variant="danger"
              size="sm"
              disabled={pending}
              onClick={onReject}
              data-testid={`receipt-reject-${r.id}`}
            >
              {t('rec.reject')}
            </Button>
          </div>
        )}
      </td>
    </tr>
  )
}
