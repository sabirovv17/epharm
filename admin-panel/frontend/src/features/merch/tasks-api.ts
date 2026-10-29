import { api } from '@/lib/api'

export interface TaskEvidence {
  id: string
  mimeType: string
  byteSize: number
  sha256: string | null
  originalFilename: string | null
  createdAt: string
  verification: string
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
  upload(id: string, uploadId: string, file: File): Promise<TaskEvidence>
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
const base = '/api/admin/merch/tasks'
export const adminTaskApi = {
  list: (offset: number, signal: AbortSignal) =>
    api
      .get<MerchTaskPage>(base, { params: { offset, limit: 20 }, signal })
      .then((response) => response.data),
  branches: (signal: AbortSignal) =>
    api.get<TaskBranch[]>(`${base}/branches`, { signal }).then((response) => response.data),
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
  upload: (id, uploadId, file) => {
    const body = new FormData()
    body.append('file', file)
    return api
      .post<TaskEvidence>(`${base}/items/${encodeURIComponent(id)}/evidence`, body, {
        params: { clientUploadId: uploadId },
        headers: { 'Content-Type': undefined },
      })
      .then((response) => response.data)
  },
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
