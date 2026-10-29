import { useState } from 'react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Field, Input, Select } from '@/ui'
import { almatyToday, reportPeriodError } from './campaignReports'
import ReportDownloadButton from './ReportDownloadButton'

export default function PayoutReportTools() {
  const access = useAdministrativeCapabilities()
  const today = almatyToday()
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`)
  const [to, setTo] = useState(today)
  const [status, setStatus] = useState('all')
  const invalid = reportPeriodError(from, to)
  return (
    <section className="card p-4">
      <h2 className="mb-3 text-sm font-bold">Отчёт по ведомостям</h2>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Созданы с даты">
          <Input
            aria-label="Ведомости с даты"
            type="date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
        </Field>
        <Field label="По дату включительно">
          <Input
            aria-label="Ведомости по дату"
            type="date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </Field>
        <Field label="Статус ведомости">
          <Select
            ariaLabel="Статус выгрузки ведомостей"
            value={status}
            onChange={setStatus}
            options={[
              { value: 'all', label: 'Все' },
              { value: 'pending', label: 'Ожидает подтверждения' },
              { value: 'approved', label: 'Одобрена' },
            ]}
          />
        </Field>
        <ReportDownloadButton
          allowed={access.canExportPayouts}
          path="/api/admin/reports/payouts.xlsx"
          params={{ from, to, status: status === 'all' ? undefined : status }}
          filename={`payouts-${from}-${to}.xlsx`}
          label="Excel: ведомости"
          invalid={invalid}
        />
      </div>
      {invalid && (
        <p role="alert" className="mt-2 text-[12px] text-accent-danger">
          {invalid}
        </p>
      )}
      <p className="mt-3 text-[12px] text-ink-500">
        Период — дата создания ведомости, время Алматы. Одобрение ведомости не подтверждает
        банковский перевод. Историческая аптека не заменяется текущей.
      </p>
    </section>
  )
}
