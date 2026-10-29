import axios from 'axios'
import { api } from '@/lib/api'
import type { AdminRole, ApiErrorResponse } from '@/lib/api-types'

export interface AccessGrant {
  module: string
  subsection: string
  action: string
  allPharmacies: boolean
  pharmacyIds: string[]
  allMerchBranches?: boolean
  merchBranchIds?: string[]
  allProducers?: boolean
  producerIds?: string[]
}

export interface AccessCapabilities {
  managed: boolean
  version: number | null
  grants: AccessGrant[]
  roleCeiling: AdminRole
  canViewAccounts: boolean
  canManageAccounts: boolean
  canViewMerchInventory?: boolean
  canViewMerchPlacements?: boolean
  canViewMerchTasks?: boolean
  canCreateMerchTasks?: boolean
  canExecuteMerchTasks?: boolean
  canReviewMerchTasks?: boolean
  canViewLedger?: boolean
  canExportLedger?: boolean
  canExportPayouts?: boolean
  canExportPharmacists?: boolean
  canExportCampaignReports?: boolean
  canExportMerchInventory?: boolean
  canExportMerchPlacements?: boolean
  canExportMerchTasks?: boolean
  canExportTrainingCourses?: boolean
  canExportTrainingPrograms?: boolean
  canBindMerchProducer?: boolean
  canManageCampaignProducer?: boolean
  canExportTrainingAssignments?: boolean
  canExportTrainingResults?: boolean
}

export interface AccessAccount {
  accountId: string
  personId: string | null
  name: string
  email: string
  jobTitle: string
  roleCeiling: AdminRole
  status: string
  managed: boolean
  version: number | null
  primaryHqOwner: boolean
  editable: boolean
}

export interface AccessProfile {
  accountId: string
  personId: string
  jobTitle: string
  version: number
  grants: AccessGrant[]
}

export interface VerifiedPerson {
  personId: string
  displayName: string
  referenceLabel: string
}
export interface SourceAccessAccount {
  sourceAccountId: string
  name: string
  email: string | null
  sourceRole: string
  active: boolean
  hasPassword: boolean
  accountId: string | null
  personId: string | null
}
export interface IdentityProof {
  verifiedPersonId: string
  verificationReference: string
  currentPassword: string
}
export interface IdentityPharmacistPage {
  items: { id: string; name: string; pharmacyName: string; personId: string | null }[]
  limit: number
  offset: number
  hasMore: boolean
}

export interface AccessPermission {
  module: string
  subsection: string
  action: string
  moduleTitle: string
  subsectionTitle: string
  actionTitle: string
  mode: 'SCOPED' | 'ALL_ONLY' | 'NOT_READY'
  availableForRoles: AdminRole[]
  requiresPasswordReauthentication: boolean
  delegatable: boolean
  scopeType?: 'PHARMACY' | 'MERCH_BRANCH'
  requiresProducerScope?: boolean
  delegationScope: {
    allPharmacies: boolean
    pharmacyIds: string[]
    allMerchBranches?: boolean
    merchBranchIds?: string[]
    allProducers?: boolean
    producerIds?: string[]
  } | null
}

export interface AccessCatalog {
  permissions: AccessPermission[]
  assignableRoles: AdminRole[]
  pharmacies: { id: string; name: string; city: string; address: string }[]
  merchBranches?: { id: string; name: string; city: string; address: string }[]
  producers?: { id: string; name: string | null }[]
}

export interface CreateAccessAccount {
  email: string
  name: string
  password: string
  role: AdminRole
  verifiedPersonId: string
  jobTitle: string
  currentPassword: string
}

// Access metadata and password-bearing writes deliberately bypass the persisted query cache.
export const accessApi = {
  me: (signal: AbortSignal) =>
    api.get<AccessCapabilities>('/api/admin/access/me', { signal }).then((r) => r.data),
  accounts: (signal: AbortSignal) =>
    api.get<AccessAccount[]>('/api/admin/access/accounts', { signal }).then((r) => r.data),
  people: (signal: AbortSignal) =>
    api.get<VerifiedPerson[]>('/api/admin/access/people', { signal }).then((r) => r.data),
  verifyPerson: (body: { name: string; verificationReference: string; currentPassword: string }) =>
    api.post<VerifiedPerson>('/api/admin/access/people', body).then((r) => r.data),
  sourceAccounts: (signal: AbortSignal) =>
    api
      .get<SourceAccessAccount[]>('/api/admin/access/source-accounts', { signal })
      .then((r) => r.data),
  bindSource: (id: string, body: IdentityProof & { targetAccountId: string }) =>
    api
      .post(`/api/admin/access/source-accounts/${encodeURIComponent(id)}/bind`, body)
      .then((r) => r.data),
  provisionSource: (
    id: string,
    body: IdentityProof & { role: AdminRole; email?: string; jobTitle: string },
  ) =>
    api
      .post<AccessProfile>(
        `/api/admin/access/source-accounts/${encodeURIComponent(id)}/provision`,
        body,
      )
      .then((r) => r.data),
  bindPharmacist: (body: IdentityProof & { pharmacistId: string }) =>
    api.post('/api/admin/access/pharmacist-person-links', body).then((r) => r.data),
  pharmacistOptions: (q: string, offset: number, signal: AbortSignal) =>
    api
      .get<IdentityPharmacistPage>('/api/admin/access/pharmacist-options', {
        params: { q, offset, limit: 50 },
        signal,
      })
      .then((r) => r.data),
  catalog: (signal: AbortSignal) =>
    api.get<AccessCatalog>('/api/admin/access/catalog', { signal }).then((r) => r.data),
  profile: (id: string, signal: AbortSignal) =>
    api
      .get<AccessProfile>(`/api/admin/access/accounts/${encodeURIComponent(id)}`, { signal })
      .then((r) => r.data),
  create: (request: CreateAccessAccount) =>
    api.post<AccessProfile>('/api/admin/access/accounts', request).then((r) => r.data),
  replace: (id: string, expectedVersion: number, grants: AccessGrant[], currentPassword: string) =>
    api
      .put<AccessProfile>(`/api/admin/access/accounts/${encodeURIComponent(id)}/grants`, {
        expectedVersion,
        grants,
        currentPassword,
      })
      .then((r) => r.data),
}

export function accessFailure(
  error: unknown,
  staleVersion = false,
): { message: string; fields: Record<string, string>; conflict: boolean } {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    const status = error.response?.status
    return {
      message:
        status === 401
          ? 'Сессия завершена. Войдите в админку заново.'
          : status === 403
            ? 'Сервер не разрешил действие. Проверьте доступ и текущий пароль.'
            : status === 409 && staleVersion
              ? 'Данные уже изменились. Обновите список и заново проверьте права.'
              : error.response?.data?.message ||
                'Не удалось загрузить или сохранить доступ. Повторите позже.',
      fields: error.response?.data?.fields ?? {},
      conflict: status === 409,
    }
  }
  return {
    message: 'Не удалось загрузить или сохранить доступ. Повторите позже.',
    fields: {},
    conflict: false,
  }
}

export const permissionId = (permission: Pick<AccessGrant, 'module' | 'subsection' | 'action'>) =>
  `${permission.module}.${permission.subsection}.${permission.action}`

export function validateGrants(
  grants: AccessGrant[],
  catalog: AccessCatalog,
  role: AdminRole,
): string | null {
  const seen = new Set<string>()
  for (const grant of grants) {
    const id = permissionId(grant)
    const permission = catalog.permissions.find((p) => permissionId(p) === id)
    if (!permission || seen.has(id))
      return 'Удалите неизвестное или повторное разрешение перед сохранением.'
    seen.add(id)
    const label = `${permission.subsectionTitle}: ${permission.actionTitle}`
    if (permission.mode === 'NOT_READY') return `${label} — ещё не подключено к серверным правам.`
    if (!permission.delegatable || !permission.delegationScope)
      return `${label} — у вас нет права выдавать это разрешение.`
    if (!permission.availableForRoles.includes(role))
      return `${label} — недоступно для выбранной роли.`
    const merch = permission.scopeType === 'MERCH_BRANCH'
    if (
      merch
        ? grant.allPharmacies || grant.pharmacyIds.length
        : grant.allMerchBranches || grant.merchBranchIds?.length
    )
      return `${label} — область другого раздела не даёт доступ к этим аптекам.`
    const all = merch ? grant.allMerchBranches === true : grant.allPharmacies
    const ids = merch ? (grant.merchBranchIds ?? []) : grant.pharmacyIds
    const known = new Set(
      (merch ? (catalog.merchBranches ?? []) : catalog.pharmacies).map((p) => p.id),
    )
    const delegatedAll = merch
      ? permission.delegationScope.allMerchBranches === true
      : permission.delegationScope.allPharmacies
    const delegatedIds = merch
      ? (permission.delegationScope.merchBranchIds ?? [])
      : permission.delegationScope.pharmacyIds
    if (all && ids.length) return `${label} — выберите одну область аптек.`
    if (!all && (!ids.length || ids.some((id) => !known.has(id)))) {
      return `${label} — выберите подтверждённые аптеки. Пустой список не означает всю сеть.`
    }
    if (permission.mode === 'ALL_ONLY' && !all)
      return `${label} — доступно только для явно выбранной всей сети.`
    if (!delegatedAll && (all || ids.some((id) => !delegatedIds.includes(id)))) {
      return `${label} — область превышает ваши права.`
    }
    const producers = grant.producerIds ?? []
    if (!permission.requiresProducerScope && (grant.allProducers || producers.length))
      return `${label} — у этого действия нет области производителей.`
    if (permission.requiresProducerScope) {
      if (grant.allProducers && producers.length)
        return `${label} — выберите одну область производителей.`
      if (
        !grant.allProducers &&
        (!producers.length ||
          producers.some((id) => !catalog.producers?.some((producer) => producer.id === id)))
      )
        return `${label} — выберите подтверждённых производителей. Пустой список не означает всех.`
      if (
        !permission.delegationScope.allProducers &&
        (grant.allProducers ||
          producers.some((id) => !permission.delegationScope!.producerIds?.includes(id)))
      )
        return `${label} — область производителей превышает ваши права.`
    }
  }
  return null
}
