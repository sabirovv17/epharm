import { Navigate } from 'react-router-dom'
import { Button } from '@/ui'
import { administrativeHomePath } from './administrativeHomePath'
import { useUiStore } from './store'
import { useAdministrativeCapabilities } from './useAdministrativeCapabilities'

export default function AdministrativeHome() {
  const user = useUiStore((state) => state.authedUser)
  const access = useAdministrativeCapabilities()
  if (!user) return <Navigate to="/login" replace />
  if (access.isPending || access.isFetching || !access.isFetchedAfterMount)
    return <p role="status">Проверяем доступ…</p>
  if (!access.isSuccess || access.isError)
    return (
      <section className="card space-y-3 p-6" role="alert">
        <p>Не удалось проверить доступ. Повторите проверку перед открытием рабочей среды.</p>
        <Button variant="outline" onClick={() => void access.refetch()}>
          Проверить доступ
        </Button>
      </section>
    )
  return <Navigate to={administrativeHomePath(user.role, access.data)} replace />
}
