import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { useUiStore } from '@/app/store'
import { usePromos } from '@/lib/queries/promo'
import { useT } from '@/i18n'
import { Button, Empty, Field, Input, PageHeader, SectionCard, Select } from '@/ui'
import {
  almatyToday,
  campaignReportError,
  canExportCampaignReports,
  reportPeriodError,
} from './campaignReports'
import ReportDownloadButton from './ReportDownloadButton'
import ProducerReportPanel from './ProducerReportPanel'

export default function CampaignReportsPage() {
  const user = useUiStore((state) => state.authedUser)
  if (!user || !canExportCampaignReports(user.role))
    return (
      <Empty
        title="Нет доступа к отчётам производителя"
        body="Выгрузка доступна только руководителю HQ и системному администратору."
      />
    )
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
  const knownCampaign = campaigns?.some((campaign) => campaign.id === campaignId) === true
  return (
    <div className="flex max-w-[1040px] flex-col gap-5">
      <PageHeader
        title="Отчёты производителю"
        subtitle="Excel по кампании и подтверждённым связям производителя. Показы, продажи замен, конверсия и история; даты — по времени Алматы."
        actions={
          <Link className="btn btn-md btn-outline" to="/dashboard">
            К дашборду
          </Link>
        }
      />
      <SectionCard
        title="Отчёт по одной кампании"
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
        <fieldset
          disabled={isLoading || isError || !campaigns?.length}
          className="flex flex-col gap-4"
        >
          <Field label="Кампания *">
            <Select
              ariaLabel="Кампания"
              value={campaignId}
              onChange={setCampaignId}
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
                type="date"
                value={from}
                onChange={(event) => setFrom(event.target.value)}
              />
            </Field>
            <Field label="Окончание периода *" hint="Включительно, не более 366 дней">
              <Input
                aria-label="Окончание периода"
                type="date"
                value={to}
                onChange={(event) => setTo(event.target.value)}
              />
            </Field>
          </div>
          <ReportDownloadButton
            allowed={access.canExportCampaignReports}
            path={`/api/admin/reports/campaigns/${encodeURIComponent(campaignId)}/xlsx`}
            params={{ from, to }}
            filename={`campaign-report-${from}-${to}.xlsx`}
            label="Excel: кампания"
            invalid={!knownCampaign ? 'Выберите кампанию из списка' : reportPeriodError(from, to)}
            note={!knownCampaign ? 'Выберите кампанию из списка.' : reportPeriodError(from, to)}
          />
        </fieldset>
      </SectionCard>
      <ProducerReportPanel
        canExport={access.canExportCampaignReports}
        canManageBindings={access.canManageCampaignProducer}
        campaignId={knownCampaign ? campaignId : ''}
      />
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
            Отчёт включает дату формирования и ограничения источника. Поздно доставленные события
            могут изменить повторный отчёт. Историческая принадлежность восстанавливается по текущей
            связи правила с кампанией. В отчёте нет ИИН, телефонов и ФИО сотрудников.
          </p>
          <p>
            Отчёт по тестовой кампании не является результатом действующей программы. Перед внешней
            отправкой проверьте содержание кампании, период и источники данных.
          </p>
        </div>
      </SectionCard>
    </div>
  )
}
