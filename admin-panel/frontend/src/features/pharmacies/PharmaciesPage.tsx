// Pharmacies — Сеть аптек ТЗ §3.2.
// Этап 3.4: подключён реальный backend через usePharmacies / useChains.

import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Empty, Metric, PageHeader, SearchInput, Tabs, useToast, type TabItem } from '@/ui'
import { IconPharmacy, IconShield, IconLift, IconPlus } from '@/ui/icons'
import type { CreatePharmacyRequest, PharmacyDto, PharmacyGroup } from '@/lib/api-types'
import { useChains, useCreatePharmacy, usePharmacies } from '@/lib/queries/pharmacies'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { CreatePharmacyModal } from './CreatePharmacyModal'

type PhTab = 'all' | PharmacyGroup

const TAB_KEYS: Record<PhTab, string> = {
  all: 'ph.tabAll',
  pilot: 'ph.tabPilot',
  control: 'ph.tabControl',
  rolled: 'ph.tabRolled',
}

export default function PharmaciesPage() {
  const t = useT()
  const toast = useToast()
  const navigate = useNavigate()
  const [tab, setTab] = useState<PhTab>('all')
  const [q, setQ] = useState('')
  const [createOpen, setCreateOpen] = useState(false)

  const { data: chains = [] } = useChains()
  const { data: pharmacies = [], isLoading, isError, error, refetch } = usePharmacies()
  const createPharmacy = useCreatePharmacy()

  const hasData = pharmacies.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData

  const counts = useMemo(() => {
    return {
      all: pharmacies.length,
      pilot: pharmacies.filter((p) => p.group === 'pilot').length,
      control: pharmacies.filter((p) => p.group === 'control').length,
      rolled: pharmacies.filter((p) => p.group === 'rolled').length,
    }
  }, [pharmacies])

  const filtered = useMemo<PharmacyDto[]>(() => {
    return pharmacies.filter((p) => {
      if (tab !== 'all' && p.group !== tab) return false
      if (!q) return true
      return `${p.id} ${p.name} ${p.chainName} ${p.city}`.toLowerCase().includes(q.toLowerCase())
    })
  }, [pharmacies, q, tab])

  const tabs: TabItem<PhTab>[] = (['all', 'pilot', 'control', 'rolled'] as PhTab[]).map((v) => ({
    value: v,
    label: t(TAB_KEYS[v]),
    count: counts[v],
  }))

  // Клик по строке → страница-редактор аптеки (паттерн Promo).
  const onRowClick = (p: PharmacyDto) => navigate(`/pharmacies/${p.id}`)

  const handleCreate = (req: CreatePharmacyRequest) => {
    createPharmacy.mutate(req, {
      onSuccess: (created) => {
        setCreateOpen(false)
        navigate(`/pharmacies/${created.id}`)
      },
      onError: (e) => toast.push(`${t('phf.createErr')}: ${describeError(e)}`),
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.pharmacies.title')}
        subtitle={t('page.pharmacies.subtitle')}
        actions={
          <Button
            variant="primary"
            leading={<IconPlus size={14} />}
            onClick={() => setCreateOpen(true)}
            data-testid="pharmacy-create-btn"
          >
            {t('phf.new')}
          </Button>
        }
      />

      <CreatePharmacyModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreate={handleCreate}
        chains={chains}
        pending={createPharmacy.isPending}
      />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('ph.mTotal')}
          value={formatNum(counts.all)}
          accent="ink"
          icon={<IconPharmacy size={18} />}
        />
        <Metric
          label={t('ph.tabPilot')}
          value={formatNum(counts.pilot)}
          accent="green"
          icon={<IconShield size={18} />}
          sub={t('ph.fromChains', { n: chains.filter((c) => c.group === 'pilot').length })}
        />
        <Metric label={t('ph.tabControl')} value={formatNum(counts.control)} accent="blue" />
        <Metric
          label={t('ph.mRolled')}
          value={formatNum(counts.rolled)}
          accent="purple"
          icon={<IconLift size={18} />}
        />
      </div>

      <div className="card flex min-h-[480px] flex-col">
        <div className="hairline flex flex-wrap items-center gap-3 border-b px-5 py-3">
          <Tabs<PhTab> items={tabs} value={tab} onChange={setTab} />
          <SearchInput
            value={q}
            onChange={setQ}
            placeholder={t('ph.search')}
            className="!h-9 !w-[280px] flex-none"
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
              title={t('ph.loading')}
              body={t('common.connecting')}
              icon={<IconPharmacy size={26} />}
            />
          ) : showFullError ? (
            <Empty
              title={t('common.loadFailed')}
              body={describeError(error)}
              icon={<IconPharmacy size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : filtered.length === 0 ? (
            <Empty
              title={q || tab !== 'all' ? t('common.notFound') : t('ph.empty')}
              body={q || tab !== 'all' ? t('common.notFoundBody') : t('ph.emptyBody')}
              icon={<IconPharmacy size={26} />}
            />
          ) : (
            <table
              className="w-full min-w-[1280px] table-fixed text-[13px]"
              data-testid="pharmacies-table"
            >
              <colgroup>
                <col className="w-[30%]" />
                <col className="w-[260px]" />
                <col className="w-[160px]" />
                <col className="w-[150px]" />
                <col className="w-[160px]" />
                <col className="w-[80px]" />
                <col className="w-[120px]" />
                <col className="w-[120px]" />
                <col className="w-[80px]" />
              </colgroup>
              <thead className="hairline border-b">
                <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                  <th className="px-5 py-2.5 align-middle">{t('ph.thName')}</th>
                  <th className="px-3 py-2.5 align-middle">{t('ph.thPosmId')}</th>
                  <th className="px-3 py-2.5 align-middle">{t('ph.thChain')}</th>
                  <th className="px-3 py-2.5 align-middle">{t('ph.thCity')}</th>
                  <th className="px-3 py-2.5 align-middle">{t('ph.thGroup')}</th>
                  <th className="px-3 py-2.5 text-right align-middle">{t('ph.thPharm')}</th>
                  <th className="px-3 py-2.5 text-right align-middle">{t('ph.thReceipts')}</th>
                  <th className="px-3 py-2.5 text-right align-middle">{t('ph.thGmv')}</th>
                  <th className="px-5 py-2.5 text-right align-middle">{t('ph.thLift')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {filtered.map((p) => (
                  <tr
                    key={p.id}
                    onClick={() => onRowClick(p)}
                    className="cursor-pointer hover:bg-paper-hover"
                    data-testid={`pharmacy-row-${p.id}`}
                  >
                    <td className="min-w-0 px-5 py-2.5 align-middle">
                      <div className="line-clamp-2 font-extrabold leading-snug text-ink-900">
                        {p.name}
                      </div>
                      <div className="mt-1 truncate text-[11px] text-ink-500">{p.addr}</div>
                    </td>
                    <td className="min-w-0 px-3 py-2.5 align-middle">
                      <span
                        className="num block w-full select-text truncate rounded-md border border-ink-100 bg-paper px-2.5 py-1.5 text-[11px] font-extrabold leading-none text-ink-700"
                        title={`${p.id} — ${t('ph.posmIdHint')}`}
                        data-testid={`pharmacy-posm-id-${p.id}`}
                      >
                        {p.id}
                      </span>
                    </td>
                    <td className="truncate px-3 py-2.5 align-middle text-ink-700">
                      {p.chainName}
                    </td>
                    <td className="truncate px-3 py-2.5 align-middle text-ink-700">{p.city}</td>
                    <td className="px-3 py-2.5 align-middle">
                      <span
                        className={`chip ${
                          !p.active
                            ? 'chip-ink'
                            : p.group === 'pilot'
                              ? 'chip-green'
                              : p.group === 'control'
                                ? 'chip-blue'
                                : 'chip-amber'
                        }`}
                      >
                        <span
                          className="chip-dot"
                          style={{
                            background: !p.active
                              ? '#9D9388' // тёплый ink-400
                              : p.group === 'pilot'
                                ? '#D97757' // коралл-PRIMARY
                                : p.group === 'control'
                                  ? '#BE5A38' // коралл-акцент
                                  : '#F1B416',
                          }}
                        />
                        {!p.active
                          ? t('ph.inactive')
                          : p.group === 'pilot'
                            ? t('ph.gPilot')
                            : p.group === 'control'
                              ? t('ph.gControl')
                              : t('ph.gRolled')}
                      </span>
                    </td>
                    <td className="num px-3 py-2.5 text-right align-middle">{p.pharmacists}</td>
                    <td className="num px-3 py-2.5 text-right align-middle">
                      {formatNum(p.receipts30d)}
                    </td>
                    <td className="num px-3 py-2.5 text-right align-middle">
                      {formatKzt(p.gmv30d)}
                    </td>
                    <td
                      className={`num px-5 py-2.5 text-right align-middle font-extrabold ${
                        p.liftPct > 0 ? 'text-brand-green-700' : 'text-ink-400'
                      }`}
                    >
                      {p.liftPct > 0 ? `+${p.liftPct.toFixed(1)}%` : '—'}
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
