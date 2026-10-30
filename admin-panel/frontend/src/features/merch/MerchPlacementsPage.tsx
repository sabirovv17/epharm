import { api } from '@/lib/api'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button } from '@/ui'
import PlacementWorkspace from './PlacementWorkspace'
import { placementGateway } from './placements-api'
import ReportDownloadButton from '@/features/reports/ReportDownloadButton'
import PlacementProducerBinding from './PlacementProducerBinding'
import { useState } from 'react'
import ProducerCommercialWorkspace from './ProducerCommercialWorkspace'
import { commercialGateway } from './producer-commercial-api'
import { downloadReport } from '@/features/reports/ledgerReports'
import TaskInstructionPanel from './TaskInstructionPanel'
import { adminInstructionApi } from './task-instructions-api'
import { useUiStore } from '@/app/store'
import PricingEditor from './PricingEditor'

const gateway = placementGateway('admin', async <T,>(path: string, init: RequestInit = {}) => {
  const response = await api.request<T>({
    url: path,
    method: init.method ?? 'GET',
    signal: init.signal ?? undefined,
    data: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
  })
  return response.data
})
const commercial = commercialGateway(
  'admin',
  async <T,>(path: string, init: RequestInit = {}) => {
    const response = await api.request<T>({
      url: path,
      method: init.method ?? 'GET',
      signal: init.signal ?? undefined,
      data: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    })
    return response.data
  },
  (path, signal) => downloadReport(path, {}, signal),
)
export default function MerchPlacementsPage() {
  const [section, setSection] = useState('placements')
  const [visited, setVisited] = useState<string[]>(['placements'])
  const principalId = useUiStore((state) => state.authedUser?.id ?? 'signed-out')
  const epoch = useUiStore((state) => state.querySessionEpoch)
  const access = useAdministrativeCapabilities()
  const principalKey = `${principalId}:${epoch}:${access.data?.version ?? 'legacy'}:${access.data?.roleCeiling ?? ''}`
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (access.isError)
    return (
      <div role="alert" className="card p-6">
        <h1 className="text-xl font-bold">Не удалось проверить доступ</h1>
        <p className="my-3 text-ink-500">
          Данные размещений скрыты до успешной проверки. Это не отказ в правах.
        </p>
        <Button variant="outline" onClick={() => access.refetch()}>
          Повторить проверку
        </Button>
      </div>
    )
  if (!access.canViewMerchPlacements)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Нет доступа к размещениям</h1>
        <p className="mt-2 text-ink-500">
          Нужны отдельные права на сочетание аптек мерча и производителей.
        </p>
      </div>
    )
  const activeSection =
    section === 'tariffs' && !access.canViewMerchTariffs ? 'placements' : section
  const sections = [
    ['placements', 'Медиапланы'],
    ['commercial', 'Планы на период и бюджет'],
    ['instructions', 'Инструктажи по размещениям'],
    ...(access.canViewMerchTariffs ? [['tariffs', 'Тарифы поставщиков']] : []),
  ]
  return (
    <div className="min-w-0 space-y-5">
      <nav aria-label="Разделы размещений" className="card flex flex-wrap gap-2 p-3">
        {sections.map(([id, label]) => (
          <Button
            key={id}
            id={`merch-section-${id}`}
            variant={activeSection === id ? 'primary' : 'ghost'}
            aria-expanded={activeSection === id}
            aria-controls={`merch-panel-${id}`}
            className="h-auto min-h-[38px] max-w-full whitespace-normal py-2 text-left"
            onClick={() => {
              setSection(id)
              setVisited((values) => (values.includes(id) ? values : [...values, id]))
            }}
          >
            {label}
          </Button>
        ))}
      </nav>
      <div
        id="merch-panel-tariffs"
        role="region"
        aria-labelledby="merch-section-tariffs"
        hidden={activeSection !== 'tariffs'}
      >
        {visited.includes('tariffs') && access.canViewMerchTariffs && (
          <PricingEditor key={`tariffs:${principalKey}`} />
        )}
      </div>
      <div
        id="merch-panel-commercial"
        role="region"
        aria-labelledby="merch-section-commercial"
        hidden={activeSection !== 'commercial'}
      >
        {visited.includes('commercial') && (
          <ProducerCommercialWorkspace
            key={`commercial:${principalKey}`}
            gateway={commercial}
            placements={gateway}
          />
        )}
      </div>
      <div
        id="merch-panel-instructions"
        role="region"
        aria-labelledby="merch-section-instructions"
        hidden={activeSection !== 'instructions'}
      >
        {visited.includes('instructions') && (
          <TaskInstructionPanel key={`instructions:${principalKey}`} api={adminInstructionApi} />
        )}
      </div>
      <div
        id="merch-panel-placements"
        role="region"
        aria-labelledby="merch-section-placements"
        hidden={activeSection !== 'placements'}
      >
        <PlacementWorkspace
          key={`placements:${principalKey}`}
          gateway={gateway}
          renderExport={(filter) => (
            <ReportDownloadButton
              allowed={access.canExportMerchPlacements}
              path="/api/admin/merch/exports/placements.xlsx"
              params={filter}
              filename="merch-placements.xlsx"
              label="Excel: медиапланы"
              note="Все страницы по выбранному производителю и статусу; без скрытого периода."
            />
          )}
          renderBinding={(placement, producers, onChanged) =>
            access.canBindMerchProducer && placement.canBindProducer === true ? (
              <PlacementProducerBinding
                placement={placement}
                producers={producers}
                onChanged={onChanged}
              />
            ) : null
          }
        />
      </div>
    </div>
  )
}
