// RequireAuth — guard для приватных роутов. Если authedUser=null → редирект на /login
// с сохранением текущего pathname в state (чтобы вернуть пользователя после входа).

import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useUiStore } from './store'

export function RequireAuth() {
  const authedUser = useUiStore((s) => s.authedUser)
  const location = useLocation()
  if (!authedUser) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
