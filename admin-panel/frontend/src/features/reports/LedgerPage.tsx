import { useCallback, useState } from 'react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button, Field, Input, PageHeader, Select } from '@/ui'
import { formatKzt } from '@/mocks/fixtures'
import { describeError } from '@/lib/describeError'
import { usePlacementRead } from '@/features/merch/usePlacementRead'
import { almatyToday, reportPeriodError } from './campaignReports'
import { ledgerSources, readLedger, type LedgerFilter } from './ledgerReports'
import ReportDownloadButton from './ReportDownloadButton'

export default function LedgerPage() {
  const access = useAdministrativeCapabilities()
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!access.canViewLedger)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Нет доступа к начислениям</h1>
        <p className="mt-2 text-ink-500">
          Нужны отдельные права на финансовый журнал в вашей области.
        </p>
        {access.isError && (
          <Button variant="outline" onClick={() => access.refetch()}>
            Повторить проверку
          </Button>
        )}
      </div>
    )
  return <LedgerWorkspace canExport={access.canExportLedger} />
}
function LedgerWorkspace({ canExport }: { canExport: boolean }) {
  const today = almatyToday()
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`)
  const [to, setTo] = useState(today)
  const [source, setSource] = useState('')
  const [filter, setFilter] = useState<LedgerFilter>({ from, to })
  const invalid = reportPeriodError(from, to)
  const load = useCallback((signal: AbortSignal) => readLedger(filter, signal), [filter])
  const read = usePlacementRead(load)
  const changed = filter.from !== from || filter.to !== to || (filter.source ?? '') !== source
  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Начисления"
        subtitle="Движения единого кошелька. Суммы берутся из проводок; исходные начисления не суммируются повторно."
      />
      <div className="card flex flex-wrap items-end gap-3 p-4">
        <Field label="С даты">
          <Input
            aria-label="Начало периода начислений"
            type="date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </Field>
        <Field label="По дату включительно">
          <Input
            aria-label="Окончание периода начислений"
            type="date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </Field>
        <Field label="Источник">
          <Select
            ariaLabel="Источник начисления"
            value={source || 'all'}
            onChange={(value) => setSource(value === 'all' ? '' : value)}
            options={[
              { value: 'all', label: 'Все источники' },
              ...Object.entries(ledgerSources).map(([value, label]) => ({ value, label })),
            ]}
          />
        </Field>
        <Button
          disabled={!!invalid || read.pending}
          onClick={() =>
            changed ? setFilter({ from, to, source: source || undefined }) : read.reload()
          }
        >
          Показать
        </Button>
        <ReportDownloadButton
          allowed={canExport}
          path="/api/admin/reports/ledger.xlsx"
          params={{ from: filter.from, to: filter.to, source: filter.source }}
          filename={`ledger-${filter.from}-${filter.to}.xlsx`}
          label="Excel: начисления"
          invalid={changed ? 'Сначала примените выбранные фильтры' : invalid}
        />
      </div>
      {invalid && (
        <p role="alert" className="text-[13px] text-accent-danger">
          {invalid}
        </p>
      )}
      {changed && !invalid && (
        <p className="text-[12px] text-ink-500">
          Фильтры изменены. Нажмите «Показать», чтобы обновить журнал и выгрузку.
        </p>
      )}
      {read.pending && <p role="status">Загружаем проводки…</p>}
      {!!read.error && (
        <div role="alert" className="card p-4">
          <p>{describeError(read.error)}</p>
          <Button variant="outline" className="mt-2" onClick={read.reload}>
            Повторить
          </Button>
        </div>
      )}
      {read.data && (
        <>
          <div className="card p-4">
            <p className="text-[13px]">
              Показаны проводки единого кошелька с момента его подключения. Начальный баланс и более
              ранние основания не превращены в новые начисления.
            </p>
            <p className="mt-2 text-[12px] text-ink-500">{read.data.historicalScopeNotice}</p>
            <p className="mt-2 text-[12px] text-ink-500">
              Период: {read.data.from} — {read.data.to}. Сформировано:{' '}
              {new Date(read.data.generatedAt).toLocaleString('ru-RU', {
                timeZone: read.data.timezone,
              })}
              , время Алматы. История без установленной аптеки:{' '}
              {read.data.unresolvedHistoricalRows === null
                ? 'не раскрывается в вашей области'
                : read.data.unresolvedHistoricalRows}
              .
            </p>
          </div>
          <div className="grid grid-cols-3 gap-3">
            {read.data.totals.map((total) => (
              <div key={total.source} className="card p-4">
                <p className="text-[12px] text-ink-500">
                  {ledgerSources[total.source as keyof typeof ledgerSources] ?? 'Другой источник'} ·{' '}
                  {total.entries} проводок
                </p>
                <p className="mt-1 text-lg font-bold">{formatKzt(total.amountKzt)}</p>
                <p className="mt-1 text-[12px] text-ink-500">
                  Изменение заработанного: {formatKzt(total.earnedDeltaKzt)}
                </p>
              </div>
            ))}
          </div>
          <div className="card overflow-auto">
            <table className="w-full text-left text-[12px]">
              <thead className="bg-paper-input">
                <tr>
                  {[
                    'Дата, Алматы',
                    'Фармацевт',
                    'Источник',
                    'Историческая аптека',
                    'Движение',
                    'Заработано',
                    'Баланс после',
                    'Основание',
                  ].map((title) => (
                    <th key={title} className="p-3">
                      {title}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {read.data.entries.map((row) => (
                  <tr key={row.id} className="border-t border-paper-line">
                    <td className="p-3">
                      {new Date(row.createdAt).toLocaleString('ru-RU', {
                        timeZone: read.data!.timezone,
                      })}
                    </td>
                    <td className="p-3">{row.pharmacistName}</td>
                    <td className="p-3">
                      {ledgerSources[row.source as keyof typeof ledgerSources] ?? 'Другой источник'}
                    </td>
                    <td className="p-3">
                      {row.historicalPharmacyName ??
                        (row.historicalPharmacyId
                          ? 'Название не сохранено'
                          : 'Не записана в источнике')}
                    </td>
                    <td className="p-3 font-semibold">{formatKzt(row.amountKzt)}</td>
                    <td className="p-3">{formatKzt(row.earnedDeltaKzt)}</td>
                    <td className="p-3">
                      {row.balanceAfterKzt === null ? '—' : formatKzt(row.balanceAfterKzt)}
                    </td>
                    <td className="p-3">
                      {(
                        {
                          approved: 'Одобрено',
                          pending: 'Ожидает',
                          rejected: 'Отклонено',
                          paid: 'Отмечено в источнике',
                          cancelled: 'Отменено',
                        } as Record<string, string>
                      )[row.basisStatus ?? ''] ?? 'Статус не сохранён'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!read.data.entries.length && (
              <p className="p-6 text-ink-500">За этот период проводок в вашей области нет.</p>
            )}
          </div>
          <p className="text-[12px] text-ink-500">
            Проводка кошелька и резервирование выплаты не подтверждают банковский перевод.
            Неизвестная историческая аптека не заменяется текущим местом работы.
          </p>
        </>
      )}
    </div>
  )
}
