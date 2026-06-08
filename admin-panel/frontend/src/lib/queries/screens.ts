// Screens — TanStack Query хуки для плейлистов и слайдов (ТЗ §3.3).
// Управление контентом из админки: загрузка медиа (multipart→MinIO), сборка
// плейлистов, активация. Назначение на аптеки + рендер = Этап 5 (POSM).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  AssignSlideRequest,
  CreatePlaylistRequest,
  PlaylistDto,
  PlaylistStatus,
  SlideDto,
  UpdatePlaylistRequest,
} from '@/lib/api-types'

export interface PlaylistListFilter {
  status?: PlaylistStatus
}

export const screensKeys = {
  all: ['screens'] as const,
  playlists: (filter?: PlaylistListFilter) =>
    [...screensKeys.all, 'playlists', filter ?? {}] as const,
  slides: () => [...screensKeys.all, 'slides'] as const,
}

export function usePlaylists(filter: PlaylistListFilter = {}) {
  return useQuery<PlaylistDto[]>({
    queryKey: screensKeys.playlists(filter),
    queryFn: () =>
      api
        .get<PlaylistDto[]>('/api/admin/screens/playlists', { params: filter })
        .then((r) => r.data),
  })
}

export function useSlides() {
  return useQuery<SlideDto[]>({
    queryKey: screensKeys.slides(),
    queryFn: () => api.get<SlideDto[]>('/api/admin/screens/slides').then((r) => r.data),
  })
}

// ── Мутации ────────────────────────────────────────────────────────────────

export function useCreatePlaylist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreatePlaylistRequest) =>
      api.post<PlaylistDto>('/api/admin/screens/playlists', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}

export function useUpdatePlaylist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdatePlaylistRequest }) =>
      api.patch<PlaylistDto>(`/api/admin/screens/playlists/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}

export function useDeletePlaylist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/screens/playlists/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}

/**
 * Загрузка слайда: multipart (file + title + durationSec) → backend → MinIO.
 * axios сам выставит boundary для FormData.
 */
export function useUploadSlide() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      file,
      title,
      durationSec,
    }: {
      file: File
      title: string
      durationSec: number
    }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('title', title)
      form.append('durationSec', String(durationSec))
      return api.post<SlideDto>('/api/admin/screens/slides', form).then((r) => r.data)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}

export function useDeleteSlide() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/screens/slides/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}

export function useAssignSlide() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, req }: { id: string; req: AssignSlideRequest }) =>
      api.post<SlideDto>(`/api/admin/screens/slides/${id}/assign`, req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.all }),
  })
}
