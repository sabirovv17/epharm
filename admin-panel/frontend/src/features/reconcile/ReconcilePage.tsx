// Reconcile — сверка чеков (ТЗ §3.5). Очередь чеков с реального backend +
// ручные действия модератора (одобрить → начисление бонуса / отклонить).
// Клик по строке → drawer проверки: фото чека + позиция/акция + суммы + источники.

import { useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import {
  Button,
  Drawer,
  Empty,
  Metric,
  PageHeader,
  SectionCard,
  Tabs,
  useToast,
  type TabItem,
} from '@/ui'
import { IconAlert, IconCheck, IconClock, IconReceipt, IconShield } from '@/ui/icons'
import type { ProductDto, ReceiptDto, ReceiptStatus } from '@/lib/api-types'
import {
  useApproveReceipt,
  useImportExcel,
  useReceipts,
  useReconcileSummary,
  useRejectReceipt,
} from '@/lib/queries/reconcile'
import { buildProductIndex, useProducts } from '@/lib/queries/catalog'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type RecTab = ReceiptStatus
type ProductLookup = (id: string | undefined) => ProductDto | undefined

function fmtDate(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
}

export default function ReconcilePage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<RecTab>('pending')
  const [selected, setSelected] = useState<ReceiptDto | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const importExcel = useImportExcel()

  const summaryQ = useReconcileSummary()
  const s = summaryQ.data
  const { data: receipts = [], isLoading, isError, error, refetch } = useReceipts(tab)
  const productsQ = useProducts()
  const productOf: ProductLookup = buildProductIndex(productsQ.data)

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
                  <th className="px-3 py-2.5">{t('rec.thStatus')}</th>
                  <th className="px-3 py-2.5 text-center">{t('rec.thLog')}</th>
                  <th className="px-3 py-2.5 text-center">{t('rec.thExcel')}</th>
                  <th className="px-5 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {receipts.map((r) => (
                  <ReceiptRow key={r.id} r={r} productOf={productOf} onOpen={setSelected} />
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

      <ReceiptDetailDrawer
        receipt={selected}
        productOf={productOf}
        onClose={() => setSelected(null)}
      />
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

/**
 * Ячейка-галочка для столбцов «Логи» / «Эксель». ✓ — источник подтвердил чек,
 * прочерк — нет. Две галочки → авто-одобрение; одна/ноль → ручная модерация.
 */
function CheckCell({ ok, testid }: { ok: boolean; testid?: string }) {
  return (
    <div className="flex items-center justify-center" data-testid={testid}>
      {ok ? (
        <span
          className="flex h-6 w-6 items-center justify-center rounded-full bg-brand-green-100 text-brand-green-700"
          data-checked="true"
          aria-label="подтверждён"
          title="подтверждён"
        >
          <IconCheck size={14} />
        </span>
      ) : (
        <span className="text-ink-300" data-checked="false" aria-label="нет" title="нет">
          —
        </span>
      )}
    </div>
  )
}

function ReceiptRow({
  r,
  productOf,
  onOpen,
}: {
  r: ReceiptDto
  productOf: ProductLookup
  onOpen: (r: ReceiptDto) => void
}) {
  const t = useT()
  const toast = useToast()
  const approve = useApproveReceipt()
  const reject = useRejectReceipt()
  const pending = approve.isPending || reject.isPending
  const actionable =
    r.status === 'pending' || r.status === 'flagged' || r.status === 'moderation_required'
  const productName = productOf(r.parsedSku)?.name ?? r.parsedSku

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
    <tr
      data-testid={`receipt-${r.id}`}
      className="cursor-pointer hover:bg-paper-hover"
      onClick={() => onOpen(r)}
    >
      <td className="px-5 py-2.5">
        <div className="font-extrabold text-ink-900">{productName || '—'}</div>
        <div className="text-[11px] text-ink-500">
          {r.photoUrl ? t('rec.hasPhoto') : t('rec.noPhoto')}
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
      <td className="px-3 py-2.5">
        <StatusCell r={r} />
      </td>
      <td className="px-3 py-2.5">
        <CheckCell ok={r.confirmedByLog} testid={`check-log-${r.id}`} />
      </td>
      <td className="px-3 py-2.5">
        <CheckCell ok={r.confirmedByExcel} testid={`check-excel-${r.id}`} />
      </td>
      <td className="px-5 py-2.5">
        {actionable && (
          <div className="flex items-center justify-end gap-2">
            <Button
              variant="primary"
              size="sm"
              disabled={pending}
              onClick={(e) => {
                e.stopPropagation()
                onApprove()
              }}
              data-testid={`receipt-approve-${r.id}`}
            >
              {t('rec.approve')}
            </Button>
            <Button
              variant="danger"
              size="sm"
              disabled={pending}
              onClick={(e) => {
                e.stopPropagation()
                onReject()
              }}
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

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <div className="text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">{label}</div>
      <div className="text-[13px] text-ink-900">{children}</div>
    </div>
  )
}

/** Панель проверки чека: фото + позиция/акция + суммы + источники + действия модератора. */
function ReceiptDetailDrawer({
  receipt: r,
  productOf,
  onClose,
}: {
  receipt: ReceiptDto | null
  productOf: ProductLookup
  onClose: () => void
}) {
  const t = useT()
  const toast = useToast()
  const approve = useApproveReceipt()
  const reject = useRejectReceipt()
  const busy = approve.isPending || reject.isPending
  const actionable =
    !!r && (r.status === 'pending' || r.status === 'flagged' || r.status === 'moderation_required')

  const onApprove = () => {
    if (!r) return
    if (!confirm(t('rec.confirmApprove', { name: r.pharmacistName }))) return
    approve.mutate(r.id, {
      onSuccess: () => {
        toast.push(t('rec.approvedToast'))
        onClose()
      },
      onError: () => toast.push(t('rec.actionErr')),
    })
  }

  const onReject = () => {
    if (!r) return
    const reason = window.prompt(t('rec.rejectPrompt'))
    if (!reason || !reason.trim()) return
    reject.mutate(
      { id: r.id, reason: reason.trim() },
      {
        onSuccess: () => {
          toast.push(t('rec.rejectedToast'))
          onClose()
        },
        onError: () => toast.push(t('rec.actionErr')),
      },
    )
  }

  return (
    <Drawer
      open={!!r}
      onClose={onClose}
      title={t('rec.detailTitle')}
      subtitle={r ? (productOf(r.parsedSku)?.name ?? r.parsedSku) : undefined}
      footer={
        actionable ? (
          <div className="flex gap-2">
            <Button
              variant="primary"
              disabled={busy}
              onClick={onApprove}
              data-testid="drawer-approve"
            >
              {t('rec.approve')}
            </Button>
            <Button variant="danger" disabled={busy} onClick={onReject} data-testid="drawer-reject">
              {t('rec.reject')}
            </Button>
          </div>
        ) : undefined
      }
    >
      {r && (
        <div className="flex flex-col gap-4" data-testid="receipt-detail">
          <div>
            <div className="mb-1 text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
              {t('rec.dPhoto')}
            </div>
            {r.photoUrl ? (
              <a href={r.photoUrl} target="_blank" rel="noreferrer">
                <img
                  src={r.photoUrl}
                  alt={t('rec.dPhoto')}
                  className="max-h-[360px] w-full rounded-lg border border-ink-100 bg-paper object-contain"
                  data-testid="receipt-photo"
                />
              </a>
            ) : (
              <div className="card-soft p-4 text-[13px] text-ink-500" data-testid="receipt-nophoto">
                {t('rec.dNoPhoto')}
              </div>
            )}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label={t('rec.dProduct')}>
              <div>{productOf(r.parsedSku)?.name ?? r.parsedSku ?? '—'}</div>
              {r.parsedSku && <div className="num text-[11px] text-ink-400">{r.parsedSku}</div>}
            </Field>
            <Field label={t('rec.dParsedAmount')}>
              <span className="num">{formatKzt(r.parsedAmount)}</span>
            </Field>
            {r.expectedAmount != null && (
              <Field label={t('rec.dExpected')}>
                <span className="num">{formatKzt(r.expectedAmount)}</span>
              </Field>
            )}
            {r.bonus != null && (
              <Field label={t('rec.dBonus')}>
                <span className="num">{formatKzt(r.bonus)}</span>
              </Field>
            )}
            {r.bonusCredited > 0 && (
              <Field label={t('rec.dCredited')}>
                <span className="num font-bold text-brand-green-700">
                  {formatKzt(r.bonusCredited)}
                </span>
              </Field>
            )}
            <Field label={t('rec.dCashier')}>{r.parsedCashier || '—'}</Field>
            <Field label={t('rec.dDate')}>{fmtDate(r.parsedAt ?? r.createdAt)}</Field>
            <Field label={t('rec.dPharmacist')}>{r.pharmacistName}</Field>
            <Field label={t('rec.dPharmacy')}>{r.pharmacyName || '—'}</Field>
          </div>

          {/* Заявленные фармацевтом акции (выбор в пикере мобилки) — контекст модератору. */}
          {r.claimedPromoIds && (
            <Field label={t('rec.dClaimedPromos')}>
              <div className="flex flex-wrap gap-1">
                {r.claimedPromoIds
                  .split(',')
                  .map((id) => id.trim())
                  .filter(Boolean)
                  .map((id) => (
                    <span
                      key={id}
                      className="num rounded bg-paper-input px-2 py-0.5 text-[11px] text-ink-600"
                    >
                      {id}
                    </span>
                  ))}
              </div>
            </Field>
          )}

          <Field label={t('rec.dConfirmed')}>
            {r.confirmedByLog || r.confirmedByExcel ? (
              <span className="flex flex-wrap gap-1">
                {r.confirmedByLog && (
                  <span className="chip chip-blue w-fit">{t('rec.srcLog')}</span>
                )}
                {r.confirmedByExcel && (
                  <span className="chip chip-green w-fit">{t('rec.srcExcel')}</span>
                )}
              </span>
            ) : (
              '—'
            )}
          </Field>

          <Field label={t('rec.dStatus')}>
            <StatusCell r={r} />
          </Field>

          {r.reviewer && (
            <Field label={t('rec.dReviewer')}>
              {r.reviewer}
              {r.reviewedAt ? ` · ${fmtDate(r.reviewedAt)}` : ''}
            </Field>
          )}
        </div>
      )}
    </Drawer>
  )
}
