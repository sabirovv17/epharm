import type { AdminRole } from '@/lib/api-types'
import type { AccessCapabilities } from '@/features/settings/access-api'
import { defaultPathForRole } from './accessPolicy'

/** Historical staff-only profiles reach the retired notice; other role defaults stay unchanged. */
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
