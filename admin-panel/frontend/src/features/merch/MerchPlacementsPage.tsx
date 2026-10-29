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
  const [commercialOpen, setCommercialOpen] = useState(false)
  const [instructionsOpen, setInstructionsOpen] = useState(false)
  const principalId = useUiStore((state) => state.authedUser?.id ?? 'signed-out')
  const epoch = useUiStore((state) => state.querySessionEpoch)
  const access = useAdministrativeCapabilities()
  const principalKey = `${principalId}:${epoch}:${access.data?.version ?? 'legacy'}:${access.data?.roleCeiling ?? ''}`
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!access.canViewMerchPlacements)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Нет доступа к размещениям</h1>
        <p className="mt-2 text-ink-500">
          Нужны отдельные права на сочетание аптек мерча и производителей.
        </p>
        {access.isError && (
          <Button variant="outline" onClick={() => access.refetch()}>
            Повторить проверку
          </Button>
        )}
      </div>
    )
  return (
    <>
      <div className="mb-4 flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => setCommercialOpen((value) => !value)}>
          {commercialOpen ? 'Скрыть планы на период' : 'Планы на период и бюджет'}
        </Button>
        <Button variant="outline" onClick={() => setInstructionsOpen((value) => !value)}>
          {instructionsOpen ? 'Скрыть инструктажи' : 'Инструктажи по размещениям'}
        </Button>
      </div>
      {commercialOpen && (
        <ProducerCommercialWorkspace
          key={`commercial:${principalKey}`}
          gateway={commercial}
          placements={gateway}
        />
      )}
      {instructionsOpen && (
        <TaskInstructionPanel key={`instructions:${principalKey}`} api={adminInstructionApi} />
      )}
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
    </>
  )
}
