import { api } from '@/lib/api'
export interface ReportProducer {
  id: string
  name: string | null
  active: boolean
  profileComplete: boolean
  activeBindingCount: number
}
export interface ProducerReportIndex {
  producers: ReportProducer[]
  unboundCampaigns: { id: string; title: string }[]
  notice: string
}
export interface CampaignProducerBinding {
  id: string
  producerId: string
  producerName: string | null
  revision: number
  wholeHistory: boolean
  from: string | null
  to: string | null
  verifiedAt: string
  retractionRevision: number | null
  retractedAt: string | null
}
export interface CampaignProducerView {
  promoId: string
  revision: number
  bindings: CampaignProducerBinding[]
}
export const producerReportApi = {
  createProducer: (body: { requestId: string; name: string; reference: string }) =>
    api
      .post<{
        id: string
        name: string
        profileComplete: boolean
      }>('/api/admin/promo/producers', body)
      .then((r) => r.data),
  index: (signal: AbortSignal) =>
    api.get<ProducerReportIndex>('/api/admin/reports/producers', { signal }).then((r) => r.data),
  binding: (id: string, signal: AbortSignal) =>
    api
      .get<CampaignProducerView>(`/api/admin/promo/${encodeURIComponent(id)}/producer`, { signal })
      .then((r) => r.data),
  bind: (
    id: string,
    body: {
      expectedRevision: number
      producerId: string
      wholeHistory: boolean
      from: string | null
      to: string | null
      reference: string
    },
  ) =>
    api
      .put<CampaignProducerView>(`/api/admin/promo/${encodeURIComponent(id)}/producer`, body)
      .then((r) => r.data),
  retract: (id: string, body: { expectedRevision: number; bindingId: string; reference: string }) =>
    api
      .post<CampaignProducerView>(
        `/api/admin/promo/${encodeURIComponent(id)}/producer/retractions`,
        body,
      )
      .then((r) => r.data),
}
