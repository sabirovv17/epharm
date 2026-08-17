import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import type { SectionId } from '@/mocks/fixtures'
import { canAccessSection, defaultPathForRole } from './accessPolicy'
import { useUiStore } from './store'

/** Second-layer UI guard. Backend authorization remains the source of truth. */
export function SectionRoute({ section, children }: { section: SectionId; children: ReactNode }) {
  const user = useUiStore((state) => state.authedUser)
  if (!user) return null
  if (!canAccessSection(user.role, section)) {
    return <Navigate to={defaultPathForRole(user.role)} replace />
  }
  return children
}
