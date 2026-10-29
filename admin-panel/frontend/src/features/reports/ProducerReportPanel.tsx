import { useCallback, useState } from 'react'
import { usePlacementRead } from '@/features/merch/usePlacementRead'
import { Button, Field, Input, SectionCard, Select } from '@/ui'
import { describeError } from '@/lib/describeError'
import { almatyToday, reportPeriodError } from './campaignReports'
import ReportDownloadButton from './ReportDownloadButton'
import CampaignProducerBinding from './CampaignProducerBinding'
import { producerReportApi } from './producerReports'
import CreateReportProducer from './CreateReportProducer'

export default function ProducerReportPanel({
  canExport,
  canManageBindings,
  campaignId,
}: {
  canExport: boolean
  canManageBindings: boolean
  campaignId: string
}) {
  if (!canExport && !canManageBindings)
    return (
      <p className="text-[13px] text-ink-500">
        Сводка производителя требует отдельного серверного доступа к отчётам и финансовым данным.
      </p>
    )
  return (
    <ProducerReportWorkspace
      canExport={canExport}
      canManageBindings={canManageBindings}
      campaignId={campaignId}
    />
  )
}
function ProducerReportWorkspace({
  canExport,
  canManageBindings,
  campaignId,
}: {
  canExport: boolean
  canManageBindings: boolean
  campaignId: string
}) {
  const load = useCallback((signal: AbortSignal) => producerReportApi.index(signal), [])
  const index = usePlacementRead(load)
  const [producerId, setProducerId] = useState('')
  const today = almatyToday()
  const [from, setFrom] = useState(`${today.slice(0, 7)}-01`)
  const [to, setTo] = useState(today)
  const selected = index.data?.producers.find((producer) => producer.id === producerId)
  return (
    <SectionCard
      title="Сводка по производителю — рекомендации"
      subtitle="Кампании включаются только по явно подтверждённым связям и их периодам. Название бренда не определяет производителя."
    >
      <div className="flex flex-col gap-4">
        {canManageBindings && (
          <CreateReportProducer
            onCreated={(id) => {
              setProducerId(id)
              index.reload()
            }}
          />
        )}
        {index.pending && <p role="status">Загружаем производителей…</p>}
        {!!index.error && (
          <div role="alert">
            <p className="text-sm text-accent-danger">
              {describeError(index.error)} Для списка нужен отдельный доступ к отчётам производителя
              и финансовым данным.
            </p>
            <Button className="mt-2" variant="outline" onClick={index.reload}>
              Повторить
            </Button>
          </div>
        )}
        {index.data && (
          <>
            <Field label="Производитель отчёта">
              <Select
                ariaLabel="Производитель отчёта"
                value={producerId}
                onChange={setProducerId}
                options={index.data.producers.map((producer) => ({
                  value: producer.id,
                  label: `${producer.name ?? 'Компания не указана'} · связей: ${producer.activeBindingCount}${producer.active ? '' : ' · архив'}`,
                }))}
              />
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Начало сводного периода">
                <Input
                  aria-label="Начало сводного периода"
                  type="date"
                  value={from}
                  onChange={(event) => setFrom(event.target.value)}
                />
              </Field>
              <Field
                label="Окончание сводного периода"
                hint="Включительно, время Алматы, до 366 дней"
              >
                <Input
                  aria-label="Окончание сводного периода"
                  type="date"
                  value={to}
                  onChange={(event) => setTo(event.target.value)}
                />
              </Field>
            </div>
            <ReportDownloadButton
              allowed={canExport}
              path={`/api/admin/reports/producers/${encodeURIComponent(producerId)}/xlsx`}
              params={{ from, to }}
              filename={`producer-report-${from}-${to}.xlsx`}
              label="Excel: производитель"
              invalid={!selected ? 'Выберите производителя из списка' : reportPeriodError(from, to)}
              note={
                !selected
                  ? 'Выберите производителя из списка.'
                  : !selected.profileComplete
                    ? 'В источнике не заполнена компания. Отчёт сохраняет это ограничение.'
                    : undefined
              }
            />
            <p className="text-[12px] text-ink-500">{index.data.notice}</p>
            {index.data.unboundCampaigns.length > 0 && (
              <details className="rounded-lg bg-paper-input p-3 text-[13px]">
                <summary className="cursor-pointer font-semibold">
                  Без подтверждённого производителя: {index.data.unboundCampaigns.length}
                </summary>
                <p className="my-2 text-ink-500">
                  Эти кампании не добавляются к производителю по бренду, автору или сходству
                  названий.
                </p>
                <ul className="list-disc pl-5">
                  {index.data.unboundCampaigns.map((campaign) => (
                    <li key={campaign.id}>{campaign.title}</li>
                  ))}
                </ul>
              </details>
            )}
            {canManageBindings &&
              (campaignId ? (
                <CampaignProducerBinding
                  key={campaignId}
                  campaignId={campaignId}
                  producers={index.data.producers}
                  onChanged={index.reload}
                />
              ) : (
                <p className="text-[13px] text-ink-500">
                  Для подтверждения или отзыва связи выберите кампанию в форме выше.
                </p>
              ))}
          </>
        )}
      </div>
    </SectionCard>
  )
}
