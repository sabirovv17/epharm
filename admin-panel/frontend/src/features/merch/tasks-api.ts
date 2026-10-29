import { api } from '@/lib/api'

export interface TaskEvidence {
  id: string
  mimeType: string
  byteSize: number
  sha256: string | null
  originalFilename: string | null
  createdAt: string
  verification: string
  capture?: {
    verification?: string
    distanceMeters?: number
    location?: { status: string; accuracy?: number; reason?: string }
  }
}
export interface TaskReview {
  id: string
  attemptId: string
  reviewerAccountId: string | null
  reviewerPersonId: string | null
  decision: 'approve' | 'revision'
  reason: string
  createdAt: string
}
export interface TaskAttempt {
  id: string
  number: number
  status: string
  executorPersonId: string | null
  pharmacistId: string | null
  submittedAt: string | null
  createdAt: string
  evidence: TaskEvidence[]
  review: TaskReview | null
  comment?: string
  completedChecklistIds?: string[]
  executorIdentityStatus?: string
}
export interface TaskItem {
  id: string
  title: string
  instructions: string
  taskType: string
  status: string
  required: boolean
  requiredPhotos: number
  checklist: { id: string; title: string; required: boolean }[]
  version: number
  currentAttempt: TaskAttempt | null
  legacyEvidence: TaskEvidence[]
  availableFrom: string | null
  dueAt: string | null
  canStart?: boolean
  canUpload?: boolean
  canSubmit?: boolean
  canReview?: boolean
  canResumeSource?: boolean
  canAdoptSourceReview?: boolean
  execution?: {
    requiresStock: boolean
    products: { key: string; name: string }[]
    stock: { counts: Record<string, number>; shortageReason: string; source: string } | null
    trainingMaterialId: string | null
    requiresVideo: boolean
    referenceImage: string | null
  }
}
export interface MerchTask {
  id: string
  branchId: string
  title: string
  status: string
  dueAt: string | null
  sourceSystem: string
  version: number
  rewardAmountKzt: number | null
  items: TaskItem[]
  reward: { state: string; amountKzt: number; reason: string; walletEntryId: string | null } | null
  itemCount: number
  detailsIncluded: boolean
  priority: string
  taskType: string
}
export interface MerchTaskPage {
  items: MerchTask[]
  limit: number
  offset: number
  hasMore: boolean
}
export interface TaskBranch {
  id: string
  name: string
  city: string
  address: string
  active: boolean
  createEligible: boolean
  canConfigureReward: boolean
}
export interface CreateTask {
  branchId: string
  title: string
  dueAt: string
  rewardAmountKzt: number
  assignedPharmacistId?: string | null
  priority: string
  availableFrom: string | null
  items: {
    title: string
    instructions: string
    required: boolean
    requiredPhotos: number
    checklist: string[]
  }[]
}
export interface TaskGateway {
  realm: 'admin' | 'pharmacist' | 'capability'
  get(id: string, signal: AbortSignal): Promise<MerchTask>
  start(id: string): Promise<TaskAttempt>
  upload(id: string, uploadId: string, file: File, capture?: TaskCapture): Promise<TaskEvidence>
  saveStock?(
    id: string,
    body: { expectedAttempt: number; counts: Record<string, number>; shortageReason: string },
  ): Promise<unknown>
  instruction?(id: string, signal: AbortSignal): Promise<Blob>
  submit(
    id: string,
    expectedAttempt: number,
    completedChecklistIds: string[],
    comment: string,
  ): Promise<TaskAttempt>
  history?(
    id: string,
    offset: number,
    signal: AbortSignal,
  ): Promise<{ items: TaskAttempt[]; hasMore: boolean }>
  review?(
    id: string,
    body: {
      attemptId: string
      expectedItemVersion: number
      decision: 'approve' | 'revision'
      reason: string
      manualUnverifiedExecutor?: boolean
      manualSourcePreserved?: boolean
    },
  ): Promise<MerchTask>
  continueSource?(
    id: string,
    body: { expectedItemVersion: number; mode: 'resume' | 'review'; reason: string },
  ): Promise<unknown>
  evidence(id: string, signal: AbortSignal): Promise<Blob>
}
export interface TaskCapture {
  status: 'available' | 'unavailable'
  latitude?: number
  longitude?: number
  accuracy?: number
  measuredAt?: string
  reason?: string
  captureSource?: string
}
const base = '/api/admin/merch/tasks'
export const adminTaskApi = {
  list: (offset: number, signal: AbortSignal, filters?: { queue: string; q: string }) =>
    api
      .get<MerchTaskPage>(base, { params: { offset, limit: 20, ...filters }, signal })
      .then((response) => response.data),
  branches: (signal: AbortSignal) =>
    api.get<TaskBranch[]>(`${base}/branches`, { signal }).then((response) => response.data),
  createBulk: (
    body: Omit<CreateTask, 'branchId' | 'assignedPharmacistId'> & {
      requestId: string
      branchIds: string[]
    },
  ) => api.post<{ dispatchIds: string[] }>(`${base}/bulk`, body).then((response) => response.data),
  create: (body: CreateTask) => api.post<MerchTask>(base, body).then((response) => response.data),
}
export const adminTaskGateway: TaskGateway = {
  realm: 'admin',
  get: (id, signal) =>
    api
      .get<MerchTask>(`${base}/${encodeURIComponent(id)}`, { signal })
      .then((response) => response.data),
  start: (id) =>
    api
      .post<TaskAttempt>(`${base}/items/${encodeURIComponent(id)}/attempts`)
      .then((response) => response.data),
  upload: (id, uploadId, file, capture) => {
    const body = new FormData()
    body.append('file', file)
    if (capture)
      body.append('capture', new Blob([JSON.stringify(capture)], { type: 'application/json' }))
    return api
      .post<TaskEvidence>(`${base}/items/${encodeURIComponent(id)}/evidence`, body, {
        params: { clientUploadId: uploadId },
        headers: { 'Content-Type': undefined },
      })
      .then((response) => response.data)
  },
  saveStock: (id, body) =>
    api.post(`${base}/items/${encodeURIComponent(id)}/stock`, body).then((r) => r.data),
  instruction: (id, signal) =>
    api
      .get<Blob>(`${base}/items/${encodeURIComponent(id)}/instruction`, {
        responseType: 'blob',
        signal,
      })
      .then((r) => r.data),
  submit: (id, expectedAttempt, completedChecklistIds, comment) =>
    api
      .post<TaskAttempt>(`${base}/items/${encodeURIComponent(id)}/submit`, {
        expectedAttempt,
        completedChecklistIds,
        comment,
      })
      .then((response) => response.data),
  history: (id, offset, signal) =>
    api
      .get<{
        items: TaskAttempt[]
        hasMore: boolean
      }>(`${base}/items/${encodeURIComponent(id)}/attempts`, {
        params: { limit: 20, offset },
        signal,
      })
      .then((response) => response.data),
  review: (id, body) =>
    api
      .post<MerchTask>(`${base}/items/${encodeURIComponent(id)}/reviews`, body)
      .then((response) => response.data),
  continueSource: (id, body) =>
    api
      .post(`${base}/items/${encodeURIComponent(id)}/source-continuation`, body)
      .then((response) => response.data),
  evidence: (id, signal) =>
    api
      .get<Blob>(`${base}/evidence/${encodeURIComponent(id)}`, { responseType: 'blob', signal })
      .then((response) => response.data),
}
