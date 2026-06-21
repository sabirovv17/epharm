// Pharmacists — реестр фармацевтов ТЗ §3.2.
// Этап 3.4: подключён реальный backend через usePharmacists.

import { useMemo, useState } from 'react'
import { Button, Empty, Metric, PageHeader, SearchInput, Tabs, useToast, type TabItem } from '@/ui'
import { IconPharmacist, IconUsers } from '@/ui/icons'
import type { PharmacistDto, PharmacistStatus } from '@/lib/api-types'
import { useBlockPharmacist, usePharmacists, useUnblockPharmacist } from '@/lib/queries/pharmacists'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type PhTab = 'all' | PharmacistStatus

const TAB_KEYS: Record<PhTab, string> = {
  all: 'phc.tabAll',
  active: 'phc.tabActive',
  pending: 'phc.tabPending',
  blocked: 'phc.tabBlocked',
}

export default function PharmacistsPage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<PhTab>('all')
  const [q, setQ] = useState('')

  const { data: pharmacists = [], isLoading, isError, error, refetch } = usePharmacists()
  const blockPharmacist = useBlockPharmacist()
  const unblockPharmacist = useUnblockPharmacist()

  const hasData = pharmacists.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData

  const counts = useMemo(() => {
    return {
      all: pharmacists.length,
      active: pharmacists.filter((p) => p.status === 'active').length,
      pending: pharmacists.filter((p) => p.status === 'pending').length,
      blocked: pharmacists.filter((p) => p.status === 'blocked').length,
    }
  }, [pharmacists])

  const totalBalance = useMemo(() => pharmacists.reduce((a, p) => a + p.balance, 0), [pharmacists])

  const filtered = useMemo<PharmacistDto[]>(() => {
    return pharmacists.filter((p) => {
      if (tab !== 'all' && p.status !== tab) return false
      if (!q) return true
      return `${p.name} ${p.pharmacyName} ${p.city} ${p.iin} ${p.phone}`
        .toLowerCase()
        .includes(q.toLowerCase())
    })
  }, [pharmacists, q, tab])

  const tabs: TabItem<PhTab>[] = (['all', 'active', 'pending', 'blocked'] as PhTab[]).map((v) => ({
    value: v,
    label: t(TAB_KEYS[v]),
    count: counts[v],
  }))

  const handleToggleBlock = (p: PharmacistDto, e: React.MouseEvent) => {
    e.stopPropagation()
    if (p.status === 'blocked') {
      unblockPharmacist.mutate(p.id, {
        onSuccess: () => toast.push(t('phc.unblockedToast', { name: p.name })),
        onError: () => toast.push(t('phc.unblockErr')),
      })
    } else {
      if (!confirm(t('phc.confirmBlock', { name: p.name }))) return
      blockPharmacist.mutate(p.id, {
        onSuccess: () => toast.push(t('phc.blockedToast', { name: p.name })),
        onError: () => toast.push(t('phc.blockErr')),
      })
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.pharmacists.title')} subtitle={t('page.pharmacists.subtitle')} />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('phc.mTotal')}
          value={formatNum(counts.all)}
          accent="ink"
          icon={<IconPharmacist size={18} />}
        />
        <Metric label={t('phc.mActive')} value={formatNum(counts.active)} accent="green" />
        <Metric label={t('phc.mPending')} value={formatNum(counts.pending)} accent="amber" />
        <Metric
          label={t('phc.mBalances')}
          value={formatKzt(totalBalance)}
          accent="purple"
          sub={t('phc.toPayout')}
        />
      </div>

      <div className="card flex min-h-[480px] flex-col">
        <div className="hairline flex flex-wrap items-center gap-3 border-b px-5 py-3">
          <Tabs<PhTab> items={tabs} value={tab} onChange={setTab} />
          <SearchInput
            value={q}
            onChange={setQ}
            placeholder={t('phc.search')}
            className="!h-9 !w-[300px] flex-none"
          />
        </div>

        {showWarningBanner && (
          <div className="hairline border-b bg-accent-warning/10 px-5 py-2 text-[12px] font-semibold text-ink-700">
            {t('common.staleWarn', { e: describeError(error) })}{' '}
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
              title={t('phc.loading')}
              body={t('common.connecting')}
              icon={<IconPharmacist size={26} />}
            />
          ) : showFullError ? (
            <Empty
              title={t('common.loadFailed')}
              body={describeError(error)}
              icon={<IconPharmacist size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : filtered.length === 0 ? (
            <Empty
              title={q || tab !== 'all' ? t('common.notFound') : t('phc.empty')}
              body={q || tab !== 'all' ? t('common.notFoundBody') : t('phc.emptyBody')}
              icon={<IconPharmacist size={26} />}
            />
          ) : (
            <table className="w-full text-[13px]" data-testid="pharmacists-table">
              <thead className="hairline border-b">
                <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                  <th className="px-5 py-2.5">{t('phc.thName')}</th>
                  <th className="px-3 py-2.5">{t('phc.thPharmacy')}</th>
                  <th className="px-3 py-2.5">{t('phc.thTier')}</th>
                  <th className="px-3 py-2.5 text-right">{t('phc.thBalance')}</th>
                  <th className="px-3 py-2.5 text-right">{t('phc.thReceipts')}</th>
                  <th className="px-3 py-2.5">{t('phc.thStatus')}</th>
                  <th className="px-5 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {filtered.map((p) => (
                  <tr
                    key={p.id}
                    className="hover:bg-paper-hover"
                    data-testid={`pharmacist-row-${p.id}`}
                  >
                    <td className="px-5 py-2.5">
                      <div className="flex items-center gap-2">
                        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-ink-100 text-ink-500">
                          <IconUsers size={14} />
                        </span>
                        <div>
                          <div className="font-extrabold text-ink-900">{p.name}</div>
                          <div className="text-[11px] text-ink-500">{p.phone}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-ink-700">
                      <div>{p.pharmacyName}</div>
                      <div className="text-[11px] text-ink-500">{p.city}</div>
                    </td>
                    <td className="px-3 py-2.5">
                      <span
                        className={`chip ${
                          p.tier === 'Platinum'
                            ? 'chip-purple'
                            : p.tier === 'Gold'
                              ? 'chip-amber'
                              : 'chip-ink'
                        }`}
                      >
                        {p.tier}
                      </span>
                    </td>
                    <td className="num px-3 py-2.5 text-right font-extrabold text-brand-green-700">
                      {formatKzt(p.balance)}
                    </td>
                    <td className="num px-3 py-2.5 text-right">{formatNum(p.receipts30d)}</td>
                    <td className="px-3 py-2.5">
                      <span
                        className={`chip ${
                          p.status === 'active'
                            ? 'chip-green'
                            : p.status === 'pending'
                              ? 'chip-amber'
                              : 'chip-red'
                        }`}
                      >
                        {p.status === 'active'
                          ? t('phc.sActive')
                          : p.status === 'pending'
                            ? t('phc.sPending')
                            : t('phc.sBlocked')}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-right">
                      <button
                        type="button"
                        onClick={(e) => handleToggleBlock(p, e)}
                        // Блокируем ТОЛЬКО эту строку, пока её мутация в полёте
                        // (variables === p.id) — иначе быстрый двойной клик шлёт
                        // два block/unblock. Остальная таблица остаётся активной.
                        disabled={
                          (blockPharmacist.isPending && blockPharmacist.variables === p.id) ||
                          (unblockPharmacist.isPending && unblockPharmacist.variables === p.id)
                        }
                        className={`text-[12px] font-bold disabled:opacity-50 ${
                          p.status === 'blocked'
                            ? 'text-brand-green-700 hover:underline'
                            : 'text-accent-danger hover:underline'
                        }`}
                      >
                        {p.status === 'blocked' ? t('phc.unblock') : t('phc.block')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
