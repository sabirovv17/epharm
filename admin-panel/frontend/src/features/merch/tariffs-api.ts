import { api } from '@/lib/api'

export interface TariffValues {
  label: string
  multiplier: string
  facingMonthPrice: string
  promotionMonthPrice: string
  trainingEventPrice: string
  motivationPersonPrice: string
  active: boolean
}
export interface Tariff {
  category: string
  values: TariffValues
  updatedAt: string
  canEdit: boolean
}
export interface TariffIndex {
  items: Tariff[]
  canEdit: boolean
}
export interface TariffGateway {
  load(signal: AbortSignal): Promise<TariffIndex>
  save(category: string, expectedUpdatedAt: string, values: TariffValues): Promise<Tariff>
}
export const tariffGateway: TariffGateway = {
  load: async (signal) => (await api.get<TariffIndex>('/api/admin/merch/tariffs', { signal })).data,
  save: async (category, expectedUpdatedAt, values) =>
    (
      await api.put<Tariff>(`/api/admin/merch/tariffs/${encodeURIComponent(category)}`, {
        expectedUpdatedAt,
        values,
      })
    ).data,
}
