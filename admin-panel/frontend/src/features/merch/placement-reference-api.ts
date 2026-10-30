import { api } from '@/lib/api'
import { producerClient } from './producer-client'
export interface ReferencePhoto {
  id: string
  mimeType: string
  byteSize: number
  fileName: string
  createdAt: string
  archivedAt: string | null
}
export interface ReferencePhotoState {
  placementId: string
  itemId: string
  revision: number
  canManage: boolean
  photo: ReferencePhoto | null
  history: ReferencePhoto[]
}
export interface ReferencePhotoApi {
  view(plan: string, item: string, signal: AbortSignal): Promise<ReferencePhotoState>
  upload(
    plan: string,
    item: string,
    revision: number,
    requestId: string,
    file: File,
    signal: AbortSignal,
  ): Promise<ReferencePhotoState>
  archive(
    plan: string,
    item: string,
    revision: number,
    photoId: string,
  ): Promise<ReferencePhotoState>
  file(plan: string, item: string, photoId: string, signal: AbortSignal): Promise<Blob>
}
export function referencePhotoApi(realm: 'admin' | 'producer'): ReferencePhotoApi {
  const path = (plan: string, item: string) =>
    `${realm === 'admin' ? '/api/admin/merch' : '/api/producer'}/placements/${encodeURIComponent(plan)}/items/${encodeURIComponent(item)}/reference-photo`
  const request = <T>(url: string, init?: RequestInit) =>
    realm === 'producer'
      ? producerClient.request<T>(url, init)
      : api
          .request<T>({
            url,
            method: init?.method,
            data: init?.body ? JSON.parse(String(init.body)) : undefined,
            signal: init?.signal ?? undefined,
          })
          .then((r) => r.data)
  return {
    view: (plan, item, signal) => request(path(plan, item), { signal }),
    upload: (plan, item, expectedRevision, requestId, file, signal) => {
      const form = new FormData()
      form.set('file', file)
      form.set('expectedRevision', String(expectedRevision))
      form.set('requestId', requestId)
      return realm === 'producer'
        ? producerClient.upload(path(plan, item), form, signal)
        : api.post<ReferencePhotoState>(path(plan, item), form, { signal }).then((r) => r.data)
    },
    archive: (plan, item, expectedRevision, photoId) =>
      request(`${path(plan, item)}/archive`, {
        method: 'POST',
        body: JSON.stringify({ expectedRevision, photoId }),
      }),
    file: async (plan, item, photo, signal) => {
      const url = `${path(plan, item)}/${encodeURIComponent(photo)}/file`
      if (realm === 'producer') return producerClient.downloadImage(url, signal)
      const response = await api.get<Blob>(url, { responseType: 'blob', signal })
      if (
        !['image/jpeg', 'image/png', 'image/webp'].includes(
          String(response.headers['content-type']).split(';')[0].trim().toLowerCase(),
        )
      )
        throw new Error('Сервер не вернул фотографию JPG/PNG/WebP')
      return response.data
    },
  }
}
