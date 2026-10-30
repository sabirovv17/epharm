import { useUiStore } from '@/app/store'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import AdminStaffAccessPanel from './AdminStaffAccessPanel'

export default function AdminStaffAccessPage() {
  const access = useAdministrativeCapabilities()
  const userId = useUiStore((state) => state.authedUser?.id)
  const sessionEpoch = useUiStore((state) => state.querySessionEpoch)
  if (access.isPending || (access.isFetching && !access.isFetchedAfterMount))
    return <p role="status">Проверяем доступ…</p>
  if (!access.canViewMerchStaffAccess)
    return (
      <div className="card p-6">
        <h1 className="text-xl font-bold">Нет доступа к кодам сотрудников</h1>
        <p className="mt-2">Нужно отдельное разрешение на просмотр кодов доступа аптек мерча.</p>
      </div>
    )
  return (
    <AdminStaffAccessPanel
      enabled={access.canViewMerchStaffAccess}
      principalKey={`${userId}:${sessionEpoch}`}
    />
  )
}
