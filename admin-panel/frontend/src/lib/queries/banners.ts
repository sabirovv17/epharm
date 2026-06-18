// Banners — TanStack Query хуки для баннеров главного экрана приложения (п.5).
// Калька с screens.ts: список + CRUD + multipart-загрузка картинки в MinIO.
// Картинка грузится отдельным запросом (POST /image → imageUrl), затем её URL
// передаётся в create/update — так же, как слайды в Screens.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  BannerDto,
  BannerImageUploadDto,
  CreateBannerRequest,
  UpdateBannerRequest,
} from '@/lib/api-types'

export const bannersKeys = {
  all: ['banners'] as const,
  list: () => [...bannersKeys.all, 'list'] as const,
}

export function useBanners() {
  return useQuery<BannerDto[]>({
    queryKey: bannersKeys.list(),
    queryFn: () => api.get<BannerDto[]>('/api/admin/banners').then((r) => r.data),
  })
}

// ── Мутации ────────────────────────────────────────────────────────────────

export function useCreateBanner() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateBannerRequest) =>
      api.post<BannerDto>('/api/admin/banners', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: bannersKeys.list() }),
  })
}

export function useUpdateBanner() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateBannerRequest }) =>
      api.patch<BannerDto>(`/api/admin/banners/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: bannersKeys.list() }),
  })
}

export function useDeleteBanner() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/admin/banners/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: bannersKeys.list() }),
  })
}

/**
 * Переупорядочивание: принимает список { id, position } для баннеров, чья позиция
 * изменилась, и патчит каждый. position в БД НЕ уникальна (только индекс), поэтому
 * параллельные PATCH безопасны. Один invalidate после всех — один рефетч списка.
 */
export function useReorderBanner() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (updates: { id: string; position: number }[]) =>
      Promise.all(
        updates.map((u) => api.patch(`/api/admin/banners/${u.id}`, { position: u.position })),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: bannersKeys.list() }),
  })
}

/**
 * Загрузка картинки баннера: multipart (file) → backend → MinIO, возвращает
 * { imageUrl }. axios сам выставит boundary для FormData. URL потом кладётся
 * в create/update-запрос (как mediaUrl слайда).
 */
export function useUploadBannerImage() {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api.post<BannerImageUploadDto>('/api/admin/banners/image', form).then((r) => r.data)
    },
  })
}
