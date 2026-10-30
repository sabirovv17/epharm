import { api } from '@/lib/api'
import type { MerchTask, TaskCapture, TaskEvidence } from './tasks-api'

export const STAFF_SESSION_KEY = 'epharm.merch.staff-session.v1'
export interface StaffScope {
  sessionId: string
  codeId: string
  organizationId: string
  placementMonth: string
  branchIds: string[]
  deadlineAt: string | null
  sessionExpiresAt: string
  readOnly: boolean
}
export interface StaffSession {
  token: string
  expiresAt: string
  scope: StaffScope
}
export interface StaffBranch {
  id: string
  code: string
  name: string
  address: string
  city: string
  requiredCount: number
  visitId: string | null
  status: string | null
  photoCount: number
  submittedAt: string | null
  reason: string | null
  canStart: boolean
}
export interface StaffWorkspace {
  month: string
  organization: { id: string; name: string }
  deadlineAt: string | null
  readOnly: boolean
  branches: StaffBranch[]
  limit: number
  offset: number
  hasMore: boolean
  tasks: never[]
}
export interface StaffVisit {
  id: string
  branchId: string
  month: string
  employeeName: string
  notes: string
  submittedAt: string | null
  reason: string | null
  identityStatus: string
  canEdit: boolean
  deadlineAt: string | null
  version: number
  dispatch: MerchTask
  places: {
    taskItemId: string
    placementItemId: string
    shelfId: string
    shelfCode: string
    portion: string
    category: string | null
    width: number | null
    advertiserName: string | null
    referenceAvailable: boolean
  }[]
}
export class StaffError extends Error {
  readonly status: number
  constructor(status: number) {
    super(
      status === 401
        ? 'Доступ завершён или отозван. Введите новый код либо откройте QR заново.'
        : status === 403
          ? 'Действие недоступно. Проверьте обновлённый срок и область фотоотчёта.'
          : status === 409
            ? 'Отчёт изменился. Обновите его перед повторным действием.'
            : status === 413
              ? 'Снимок слишком большой. Уменьшите изображение и повторите.'
              : status === 429
                ? 'Слишком много запросов. Подождите и повторите.'
                : status === 400
                  ? 'Проверьте введённые данные и повторите действие.'
                  : 'Кабинет временно недоступен. Повторите позже.',
    )
    this.status = status
  }
}
const opaque = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9_-]{32,256}$/.test(value)
const validScope = (value: StaffScope) =>
  !!value &&
  typeof value.sessionId === 'string' &&
  typeof value.organizationId === 'string' &&
  /^\d{4}-\d{2}-01$/.test(value.placementMonth) &&
  Array.isArray(value.branchIds) &&
  value.branchIds.length > 0 &&
  value.branchIds.every((id) => typeof id === 'string' && !!id) &&
  typeof value.readOnly === 'boolean' &&
  Number.isFinite(Date.parse(value.sessionExpiresAt))
export function storedStaffToken(): string | null {
  try {
    const value = sessionStorage.getItem(STAFF_SESSION_KEY)
    if (opaque(value)) return value
    sessionStorage.removeItem(STAFF_SESSION_KEY)
  } catch {
    /* Storage unavailable: this tab still works until reload. */
  }
  return null
}
export function storeStaffToken(token: string | null) {
  try {
    if (token && opaque(token)) sessionStorage.setItem(STAFF_SESSION_KEY, token)
    else sessionStorage.removeItem(STAFF_SESSION_KEY)
  } catch {
    /* Storage unavailable: keep this session in memory. */
  }
}
export function staffClient(onClosed: () => void, onScope: (scope: StaffScope) => void = () => {}) {
  let token: string | null = storedStaffToken()
  let epoch = 0
  const close = () => {
    token = null
    epoch++
    storeStaffToken(null)
    onClosed()
  }
  const request = async <T>(
    path: string,
    init: RequestInit = {},
    binary = false,
    auth = true,
  ): Promise<T> => {
    if (!path.startsWith('/api/staff/')) throw new StaffError(403)
    const generation = epoch
    const response = await fetch(path, {
      ...init,
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      headers: {
        ...(auth && token ? { 'X-Staff-Session': token } : {}),
        ...(init.body && !(init.body instanceof FormData)
          ? { 'Content-Type': 'application/json' }
          : {}),
      },
    })
    if (generation !== epoch) throw new StaffError(401)
    if (!response.ok) {
      if (
        auth &&
        (response.status === 401 || (response.status === 403 && path === '/api/staff/auth/me'))
      )
        close()
      else if (auth && response.status === 403) {
        // A deadline or foreign object can deny one mutation while the limited session remains valid.
        try {
          onScope(await readScope(init.signal ?? undefined))
        } catch {
          /* Expiry/revoke closes through /me; a network failure grants no new scope. */
        }
      }
      throw new StaffError(response.status)
    }
    const result =
      response.status === 204 ? undefined : await (binary ? response.blob() : response.json())
    if (generation !== epoch) throw new StaffError(401)
    return result as T
  }
  const exchange = async (kind: 'code' | 'qr', body: unknown, signal?: AbortSignal) => {
    const generation = epoch
    const result = await request<StaffSession>(
      `/api/staff/auth/${kind}`,
      {
        method: 'POST',
        body: JSON.stringify(body),
        signal,
      },
      false,
      false,
    )
    if (
      signal?.aborted ||
      generation !== epoch ||
      !opaque(result.token) ||
      !validScope(result.scope) ||
      !Number.isFinite(Date.parse(result.expiresAt))
    )
      throw new StaffError(401)
    epoch++
    token = result.token
    storeStaffToken(token)
    return result.scope
  }
  const readScope = async (signal?: AbortSignal): Promise<StaffScope> => {
    const scope = await request<StaffScope>('/api/staff/auth/me', { signal })
    if (!validScope(scope)) {
      close()
      throw new StaffError(401)
    }
    return scope
  }
  return {
    hasSession: () => !!token,
    code: (code: string, signal?: AbortSignal) => exchange('code', { code }, signal),
    qr: (access: string, signal: AbortSignal) => exchange('qr', { access }, signal),
    me: (signal: AbortSignal) => readScope(signal),
    clear: () => {
      token = null
      epoch++
      storeStaffToken(null)
    },
    logout: async () => {
      try {
        await request('/api/staff/auth/logout', { method: 'POST' })
      } finally {
        token = null
        epoch++
        storeStaffToken(null)
      }
    },
    workspace: (month: string, offset: number, signal: AbortSignal) =>
      request<StaffWorkspace>(
        `/api/staff/workspace?month=${encodeURIComponent(month)}&limit=50&offset=${offset}`,
        { signal },
      ),
    open: (branchId: string, month: string, employeeName: string) =>
      request<StaffVisit>('/api/staff/visits', {
        method: 'POST',
        body: JSON.stringify({ branchId, month, employeeName }),
      }),
    visit: (id: string, signal: AbortSignal) =>
      request<StaffVisit>(`/api/staff/visits/${encodeURIComponent(id)}`, { signal }),
    upload: (
      visitId: string,
      itemId: string,
      clientUploadId: string,
      file: File,
      capture: TaskCapture,
    ) => {
      const body = new FormData()
      body.append('file', file)
      body.append('clientUploadId', clientUploadId)
      body.append('capture', new Blob([JSON.stringify(capture)], { type: 'application/json' }))
      return request<TaskEvidence>(
        `/api/staff/visits/${encodeURIComponent(visitId)}/items/${encodeURIComponent(itemId)}/evidence`,
        { method: 'POST', body },
      )
    },
    reference: (visitId: string, itemId: string, signal: AbortSignal) =>
      request<Blob>(
        `/api/staff/visits/${encodeURIComponent(visitId)}/items/${encodeURIComponent(itemId)}/reference`,
        { signal },
        true,
      ),
    evidence: (visitId: string, evidenceId: string, signal: AbortSignal) =>
      request<Blob>(
        `/api/staff/visits/${encodeURIComponent(visitId)}/evidence/${encodeURIComponent(evidenceId)}`,
        { signal },
        true,
      ),
    submit: (id: string, expectedVersion: number, employeeName: string, notes: string) =>
      request<StaffVisit>(`/api/staff/visits/${encodeURIComponent(id)}/submit`, {
        method: 'POST',
        body: JSON.stringify({ expectedVersion, employeeName, notes }),
      }),
  }
}
export type StaffClient = ReturnType<typeof staffClient>

export interface StaffAccessCode {
  codeId: string
  organizationId: string
  placementMonth: string
  branchIds: string[]
  deadlineAt: string | null
  status: string
  maxUses: number
  useCount: number
  qrUseCount: number
  createdAt: string
  lastUsedAt: string | null
  canRevoke: boolean
  allOrgBranches: boolean
  hasQrToken: boolean
  manualRevoked: boolean
  exhausted: boolean
  expired: boolean
}
export interface StaffAccessBranchOption {
  id: string
  code: string
  name: string
  city: string
  address: string
  canCreate: boolean
}
export interface StaffAccessOptions {
  organizations: { id: string; name: string; branches: StaffAccessBranchOption[] }[]
}
export interface StaffAccessSettings {
  organizationId: string
  organizationName: string
  placementMonth: string
  branches: StaffAccessBranchOption[]
  codes: StaffAccessCode[]
  canCreate: boolean
}
const adminBase = '/api/admin/merch/staff-access'
export const adminStaffAccessApi = {
  options: (signal: AbortSignal) =>
    api.get<StaffAccessOptions>(`${adminBase}/options`, { signal }).then((r) => r.data),
  settings: (organizationId: string, month: string, signal: AbortSignal) =>
    api
      .get<StaffAccessSettings>(adminBase, {
        signal,
        params: { organizationId, month },
      })
      .then((r) => r.data),
  create: (body: {
    organizationId: string
    placementMonth: string
    branchIds: string[]
    deadlineAt: string | null
  }) =>
    api
      .post<{ code: string; accessToken: string; grant: StaffAccessCode }>(adminBase, body)
      .then((r) => r.data),
  revoke: (id: string) =>
    api.post(`${adminBase}/${encodeURIComponent(id)}/revoke`).then((r) => r.data),
}
