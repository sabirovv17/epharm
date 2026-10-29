import axios from 'axios'
import { api } from '@/lib/api'
import type { AdminRole, ApiErrorResponse } from '@/lib/api-types'

export interface AccessGrant {
  module: string
  subsection: string
  action: string
  allPharmacies: boolean
  pharmacyIds: string[]
}

export interface AccessCapabilities {
  managed: boolean
  version: number | null
  grants: AccessGrant[]
  roleCeiling: AdminRole
  canViewAccounts: boolean
  canManageAccounts: boolean
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
  delegationScope: { allPharmacies: boolean; pharmacyIds: string[] } | null
}

export interface AccessCatalog {
  permissions: AccessPermission[]
  assignableRoles: AdminRole[]
  pharmacies: { id: string; name: string; city: string; address: string }[]
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
  const knownPharmacies = new Set(catalog.pharmacies.map((p) => p.id))
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
    if (grant.allPharmacies && grant.pharmacyIds.length)
      return `${label} — выберите одну область аптек.`
    if (
      !grant.allPharmacies &&
      (!grant.pharmacyIds.length || grant.pharmacyIds.some((id) => !knownPharmacies.has(id)))
    ) {
      return `${label} — выберите подтверждённые аптеки. Пустой список не означает всю сеть.`
    }
    if (permission.mode === 'ALL_ONLY' && !grant.allPharmacies)
      return `${label} — доступно только для явно выбранной всей сети.`
    if (
      !permission.delegationScope.allPharmacies &&
      (grant.allPharmacies ||
        grant.pharmacyIds.some((id) => !permission.delegationScope!.pharmacyIds.includes(id)))
    ) {
      return `${label} — область превышает ваши права.`
    }
  }
  return null
}
