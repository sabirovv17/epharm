import { api } from '@/lib/api'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { Button } from '@/ui'
import PlacementWorkspace from './PlacementWorkspace'
import { placementGateway } from './placements-api'

const gateway = placementGateway('admin', async <T,>(path: string, init: RequestInit = {}) => {
  const response = await api.request<T>({
    url: path,
    method: init.method ?? 'GET',
    signal: init.signal ?? undefined,
    data: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
  })
  return response.data
})
export default function MerchPlacementsPage() {
  const access = useAdministrativeCapabilities()
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
  return <PlacementWorkspace gateway={gateway} />
}
