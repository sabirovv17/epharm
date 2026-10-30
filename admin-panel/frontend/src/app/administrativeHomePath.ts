import type { AdminRole } from '@/lib/api-types'
import type { AccessCapabilities } from '@/features/settings/access-api'
import { defaultPathForRole } from './accessPolicy'

/** A sole staff-access profile has one workspace; every other role default stays unchanged. */
export function administrativeHomePath(
  role: AdminRole,
  access: Pick<AccessCapabilities, 'managed' | 'grants' | 'canViewMerchStaffAccess'>,
) {
  const soleStaff =
    access.managed === true &&
    access.canViewMerchStaffAccess === true &&
    Array.isArray(access.grants) &&
    access.grants.length > 0 &&
    access.grants.some((grant) => grant.action === 'view') &&
    access.grants.every(
      (grant) =>
        grant.module === 'merchandising' &&
        grant.subsection === 'staff_access' &&
        ['view', 'create', 'revoke'].includes(grant.action),
    )
  return soleStaff ? '/merch/staff-access' : defaultPathForRole(role)
}
