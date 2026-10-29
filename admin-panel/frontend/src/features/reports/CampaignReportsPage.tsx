import { useEffect, useRef, useState, type FormEvent } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import ReportDownloadButton from './ReportDownloadButton'
import { useUiStore } from '@/app/store'
import { usePromos } from '@/lib/queries/promo'
import { useT } from '@/i18n'
import { Button, Empty, Field, Input, PageHeader, SectionCard, Select } from '@/ui'
import {
  almatyToday,
  campaignReportError,
  canExportCampaignReports,
  downloadCampaignReport,
  reportPeriodError,
  saveCampaignPdf,
} from './campaignReports'

export default function CampaignReportsPage() {
  const user = useUiStore((state) => state.authedUser)
  if (!user || !canExportCampaignReports(user.role)) {
    return (
      <Empty
        title="Нет доступа к отчётам производителя"
        body="Выгрузка доступна только руководителю HQ и системному администратору."
      />
    )
  }
  return <ReportForm key={user.id} />
}

function ReportForm() {
  const t = useT()
  const access = useAdministrativeCapabilities()
  const { data: campaigns, isLoading, isError, error, refetch } = usePromos()
  const today = almatyToday()
  const [campaignId, setCampaignId] = useState('')
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`)
  const [to, setTo] = useState(today)
  const [validation, setValidation] = useState<string>()
  const [failure, setFailure] = useState<string>()
  const [pending, setPending] = useState(false)
  const [downloaded, setDownloaded] = useState(false)
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => request.current?.abort(), [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    setFailure(undefined)
    setDownloaded(false)
    if (!campaigns?.some((campaign) => campaign.id === campaignId)) {
      setValidation('Выберите кампанию из списка.')
      event.currentTarget.querySelector('select')?.focus()
      return
    }
    const periodError = reportPeriodError(from, to)
    setValidation(periodError)
    if (periodError) {
      event.currentTarget.querySelector('input')?.focus()
      return
    }
    const controller = new AbortController()
    request.current = controller
    setPending(true)
    try {
      const blob = await downloadCampaignReport(campaignId, from, to, controller.signal)
      if (!controller.signal.aborted) {
        saveCampaignPdf(blob, from, to)
        setDownloaded(true)
      }
    } catch (cause) {
      if (!controller.signal.aborted && !axios.isCancel(cause))
        setFailure(campaignReportError(cause))
    } finally {
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return (
    <div className="flex max-w-[1040px] flex-col gap-5">
      <PageHeader
        title="Отчёты производителю"
        subtitle="PDF по одной кампании: подтверждённые показы, продажи замен, конверсия и дневной график. Даты — по времени Алматы."
        actions={
          <Link className="btn btn-md btn-outline" to="/dashboard">
            К дашборду
          </Link>
        }
      />
      <SectionCard
        title="Сформировать отчёт"
        subtitle="Существующие кампании и правила не изменяются, включая тестовые и архивные."
      >
        {isLoading && (
          <p role="status" className="text-sm text-ink-500">
            Загрузка кампаний…
          </p>
        )}
        {isError && (
          <div role="alert" className="mb-4 text-sm text-accent-danger">
            {campaignReportError(error)}{' '}
            <Button variant="outline" onClick={() => refetch()}>
              Повторить загрузку
            </Button>
          </div>
        )}
        {!isLoading && !isError && campaigns?.length === 0 && (
          <p className="text-sm text-ink-500">
            Кампаний пока нет. Выгрузка станет доступна после создания кампании.
          </p>
        )}
        <form onSubmit={submit} noValidate>
          <fieldset
            disabled={pending || isLoading || isError || !campaigns?.length}
            className="flex flex-col gap-4"
          >
            <Field label="Кампания *">
              <Select
                ariaLabel="Кампания"
                value={campaignId}
                onChange={(value) => {
                  setCampaignId(value)
                  setValidation(undefined)
                  setDownloaded(false)
                }}
                placeholder="Выберите кампанию"
                options={(campaigns ?? []).map((campaign) => ({
                  value: campaign.id,
                  label: `${campaign.title} · ${campaign.brand} · ${t(`status.${campaign.status}`)}`,
                }))}
              />
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Начало периода *">
                <Input
                  aria-label="Начало периода"
                  required
                  type="date"
                  value={from}
                  onChange={(event) => {
                    setFrom(event.target.value)
                    setDownloaded(false)
                    setValidation(undefined)
                  }}
                />
              </Field>
              <Field label="Окончание периода *" hint="Включительно, не более 366 дней">
                <Input
                  aria-label="Окончание периода"
                  required
                  type="date"
                  value={to}
                  onChange={(event) => {
                    setTo(event.target.value)
                    setDownloaded(false)
                    setValidation(undefined)
                  }}
                />
              </Field>
            </div>
            {validation && (
              <p role="alert" className="text-sm text-accent-danger">
                {validation}
              </p>
            )}
            <div className="flex flex-wrap items-start gap-3">
              <Button type="submit">{pending ? 'Формирование PDF…' : 'Скачать PDF'}</Button>
              <ReportDownloadButton
                allowed={access.canExportCampaignReports}
                path={`/api/admin/reports/campaigns/${encodeURIComponent(campaignId)}/xlsx`}
                params={{ from, to }}
                filename={`campaign-report-${from}-${to}.xlsx`}
                label="Excel: кампания"
                invalid={!campaignId ? 'Выберите кампанию' : reportPeriodError(from, to)}
              />
            </div>
          </fieldset>
          {failure && (
            <p role="alert" className="mt-3 text-sm text-accent-danger">
              Не удалось сформировать отчёт: {failure}
            </p>
          )}
          {downloaded && (
            <p role="status" className="mt-3 text-sm text-ink-600">
              PDF сформирован и передан браузеру для скачивания. Перед отправкой проверьте кампанию,
              период и методику внутри файла.
            </p>
          )}
        </form>
      </SectionCard>
      <SectionCard title="Что означают показатели">
        <div className="space-y-3 text-sm leading-6 text-ink-600">
          <p>
            Показ учитывается только после подтверждения кассой. «Одобренные замены» —
            подтверждённые продажи рекомендованного товара в том же чеке, а не нажатия «Принять».
          </p>
          <p>
            «Продажа по рекомендации до 2 минут» — отдельный показатель: от фактического показа до
            печати соответствующего чека прошло от 0 до 120 секунд включительно. Остальные связанные
            продажи показаны отдельно.
          </p>
          <p>
            Один чек и предложенный товар не увеличивают число продаж несколько раз. Это связь
            событий, не доказательство причинного эффекта и не подтверждение выплаты бонуса.
          </p>
          <p>
            PDF включает дату формирования и ограничения источника. Поздно доставленные события
            могут изменить повторный отчёт. Историческая принадлежность восстанавливается по текущей
            связи правила с кампанией. В отчёте нет ИИН, телефонов и ФИО сотрудников.
          </p>
          <p>
            Отчёт по тестовой кампании не является результатом действующей программы. Перед внешней
            отправкой проверьте содержание кампании и источники данных.
          </p>
        </div>
      </SectionCard>
    </div>
  )
}
