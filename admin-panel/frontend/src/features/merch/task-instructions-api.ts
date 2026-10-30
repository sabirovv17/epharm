import { api } from '@/lib/api'
export interface Instruction {
  id: string
  producerId: string | null
  title: string
  instructions: string
  dueAt: string
  status: 'pending' | 'approved' | 'rejected'
  revision: number
  planIds: string[]
  reviewNote: string | null
  createdAt: string
  canReview: boolean
  completed: number
  totalTasks: number
}
export interface InstructionPage {
  items: Instruction[]
  total: number
  limit: number
  offset: number
}
export interface InstructionTarget {
  id: string
  producerId: string
  producerName: string | null
  month: string
  label: string
  canUpload: boolean
}
export interface TargetPage {
  items: InstructionTarget[]
  total: number
  limit: number
  offset: number
}
export interface InstructionResult {
  id: string
  branchId: string
  branchName: string
  itemTitle: string
  mimeType: string
  byteSize: number
  approvedAt: string
}
export interface ResultPage {
  items: InstructionResult[]
  total: number
  limit: number
  offset: number
}
export interface InstructionMetadata {
  id: string
  producerId?: string
  planIds: string[]
  title: string
  instructions: string
  dueAt: string
}
export interface InstructionApi {
  realm: 'admin' | 'producer'
  list(offset: number, signal: AbortSignal): Promise<InstructionPage>
  targets(offset: number, signal: AbortSignal): Promise<TargetPage>
  upload(metadata: InstructionMetadata, file: File, signal: AbortSignal): Promise<Instruction>
  review(
    id: string,
    revision: number,
    decision: 'approved' | 'rejected',
    reason: string,
  ): Promise<Instruction>
  pdf(id: string, signal: AbortSignal): Promise<Blob>
  results(id: string, offset: number, signal: AbortSignal): Promise<ResultPage>
  video(id: string, evidenceId: string, signal: AbortSignal): Promise<Blob>
}
export interface InstructionProducerClient {
  request<T>(path: string, init?: RequestInit): Promise<T>
  upload<T>(path: string, form: FormData, signal?: AbortSignal): Promise<T>
  downloadPdf(path: string, signal: AbortSignal): Promise<Blob>
  downloadVideo(path: string, signal: AbortSignal): Promise<Blob>
}
const form = (metadata: InstructionMetadata, file: File) => {
  const body = new FormData()
  body.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
  body.append('file', file)
  return body
}
const idPath = (id: string) => encodeURIComponent(id)
const adminBase = '/api/admin/merch/instructions'
async function adminFile(path: string, signal: AbortSignal, video = false) {
  const response = await api.get<Blob>(path, { responseType: 'blob', signal })
  const contentType = response.headers['content-type']
  const mime = typeof contentType === 'string' ? contentType.split(';')[0].trim().toLowerCase() : ''
  if (
    video
      ? !['video/mp4', 'video/quicktime', 'video/webm'].includes(mime)
      : mime !== 'application/pdf'
  )
    throw new Error('Сервер вернул неподходящий файл')
  if (!video && (await response.data.slice(0, 5).text()) !== '%PDF-')
    throw new Error('Файл не является PDF')
  return response.data
}
export const adminInstructionApi: InstructionApi = {
  realm: 'admin',
  list: (offset, signal) =>
    api
      .get<InstructionPage>(adminBase, { params: { limit: 20, offset }, signal })
      .then((r) => r.data),
  targets: (offset, signal) =>
    api
      .get<TargetPage>(`${adminBase}/targets`, { params: { limit: 50, offset }, signal })
      .then((r) => r.data),
  upload: (metadata, file, signal) =>
    api.post<Instruction>(adminBase, form(metadata, file), { signal }).then((r) => r.data),
  review: (id, expectedRevision, decision, reason) =>
    api
      .post<Instruction>(`${adminBase}/${idPath(id)}/review`, {
        expectedRevision,
        decision,
        reason,
      })
      .then((r) => r.data),
  pdf: (id, signal) => adminFile(`${adminBase}/${idPath(id)}/pdf`, signal),
  results: (id, offset, signal) =>
    api
      .get<ResultPage>(`${adminBase}/${idPath(id)}/results`, {
        params: { limit: 20, offset },
        signal,
      })
      .then((r) => r.data),
  video: (id, evidence, signal) =>
    adminFile(`${adminBase}/${idPath(id)}/results/${idPath(evidence)}/file`, signal, true),
}
export function producerInstructionApi(client: InstructionProducerClient): InstructionApi {
  const base = '/api/producer/instructions'
  return {
    realm: 'producer',
    list: (offset, signal) => client.request(`${base}?limit=20&offset=${offset}`, { signal }),
    targets: (offset, signal) =>
      client.request(`${base}/targets?limit=50&offset=${offset}`, { signal }),
    upload: (metadata, file, signal) =>
      client.upload(
        base,
        form(
          {
            id: metadata.id,
            planIds: metadata.planIds,
            title: metadata.title,
            instructions: metadata.instructions,
            dueAt: metadata.dueAt,
          },
          file,
        ),
        signal,
      ),
    review: () => Promise.reject(new Error('Согласование доступно только управляющей команде')),
    pdf: (id, signal) => client.downloadPdf(`${base}/${idPath(id)}/pdf`, signal),
    results: (id, offset, signal) =>
      client.request(`${base}/${idPath(id)}/results?limit=20&offset=${offset}`, { signal }),
    video: (id, evidence, signal) =>
      client.downloadVideo(`${base}/${idPath(id)}/results/${idPath(evidence)}/file`, signal),
  }
}
