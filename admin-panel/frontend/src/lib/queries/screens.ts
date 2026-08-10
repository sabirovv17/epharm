// Screens — TanStack Query хуки для плейлистов и слайдов (ТЗ §3.3).
// Управление контентом из админки: загрузка медиа (multipart→MinIO), сборка
// плейлистов, активация. Назначение на аптеки + рендер = Этап 5 (POSM).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  ActivePlaylistDto,
  AssignSlideRequest,
  BroadcastProfileDto,
  BroadcastProfileSummaryDto,
  ConnectedScreensDto,
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
  /** Префикс всех плейлист-запросов (любой фильтр) — для точечной инвалидации. */
  playlistsRoot: () => [...screensKeys.all, 'playlists'] as const,
  playlists: (filter?: PlaylistListFilter) =>
    [...screensKeys.all, 'playlists', filter ?? {}] as const,
  slides: () => [...screensKeys.all, 'slides'] as const,
  connected: () => [...screensKeys.all, 'connected'] as const,
  broadcast: () => [...screensKeys.all, 'broadcast'] as const,
  broadcastProfiles: () => [...screensKeys.all, 'broadcast-profiles'] as const,
  broadcastProfile: (id: string) => [...screensKeys.broadcastProfiles(), id] as const,
}

/** Текущий упорядоченный эфир на кассах (до 12 роликов). */
export function useBroadcast() {
  return useQuery<ActivePlaylistDto>({
    queryKey: screensKeys.broadcast(),
    queryFn: () => api.get<ActivePlaylistDto>('/api/admin/screens/broadcast').then((r) => r.data),
  })
}

/** Legacy: заменить весь эфир одним роликом. */
export function useUploadBroadcast() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ file, title }: { file: File; title?: string }) => {
      const form = new FormData()
      form.append('file', file)
      if (title) form.append('title', title)
      return api.post<ActivePlaylistDto>('/api/admin/screens/broadcast', form).then((r) => r.data)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: screensKeys.broadcast() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
    },
  })
}

/** Загрузить/заменить один слот 1..12, не затрагивая остальные ролики эфира. */
export function useUploadBroadcastSlot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      slot,
      file,
      title,
      durationSec = 15,
    }: {
      slot: number
      file: File
      title?: string
      durationSec?: number
    }) => {
      const form = new FormData()
      form.append('file', file)
      if (title) form.append('title', title)
      form.append('durationSec', String(durationSec))
      return api
        .post<ActivePlaylistDto>(`/api/admin/screens/broadcast/slots/${slot}`, form)
        .then((r) => r.data)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: screensKeys.broadcast() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
    },
  })
}

/** Два управляемых профиля: общий эфир и overrides для выбранных аптек. */
export function useBroadcastProfiles() {
  return useQuery<BroadcastProfileSummaryDto[]>({
    queryKey: screensKeys.broadcastProfiles(),
    queryFn: () =>
      api
        .get<BroadcastProfileSummaryDto[]>('/api/admin/screens/broadcast/profiles')
        .then((r) => r.data),
  })
}

export function useBroadcastProfile(id: string) {
  return useQuery<BroadcastProfileDto>({
    queryKey: screensKeys.broadcastProfile(id),
    queryFn: () =>
      api
        .get<BroadcastProfileDto>(`/api/admin/screens/broadcast/profiles/${id}`)
        .then((r) => r.data),
    enabled: id.length > 0,
  })
}

export function useUploadBroadcastProfileSlot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      profileId,
      slot,
      file,
      title,
      durationSec = 15,
    }: {
      profileId: string
      slot: number
      file: File
      title?: string
      durationSec?: number
    }) => {
      const form = new FormData()
      form.append('file', file)
      if (title) form.append('title', title)
      form.append('durationSec', String(durationSec))
      return api
        .post<BroadcastProfileDto>(
          `/api/admin/screens/broadcast/profiles/${profileId}/slots/${slot}`,
          form,
        )
        .then((r) => r.data)
    },
    onSuccess: (data) => {
      qc.setQueryData(screensKeys.broadcastProfile(data.id), data)
      qc.invalidateQueries({ queryKey: screensKeys.broadcastProfiles() })
      qc.invalidateQueries({ queryKey: screensKeys.broadcast() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
    },
  })
}

export function useRemoveBroadcastProfileSlot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ profileId, slot }: { profileId: string; slot: number }) =>
      api
        .delete<BroadcastProfileDto>(
          `/api/admin/screens/broadcast/profiles/${profileId}/slots/${slot}`,
        )
        .then((r) => r.data),
    onSuccess: (data) => {
      qc.setQueryData(screensKeys.broadcastProfile(data.id), data)
      qc.invalidateQueries({ queryKey: screensKeys.broadcastProfiles() })
      qc.invalidateQueries({ queryKey: screensKeys.broadcast() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
    },
  })
}

export function useSetBroadcastProfilePharmacies() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ profileId, pharmacyIds }: { profileId: string; pharmacyIds: string[] }) =>
      api
        .put<BroadcastProfileDto>(`/api/admin/screens/broadcast/profiles/${profileId}/pharmacies`, {
          pharmacyIds,
        })
        .then((r) => r.data),
    onSuccess: (data) => {
      qc.setQueryData(screensKeys.broadcastProfile(data.id), data)
      qc.invalidateQueries({ queryKey: screensKeys.broadcastProfiles() })
    },
  })
}

/**
 * Подключённые кассы (T4) — heartbeat POSM-плееров. Поллинг каждые 30с, чтобы
 * счётчик «вживую» отражал онлайн-устройства без ручного refetch.
 */
export function useConnectedScreens() {
  return useQuery<ConnectedScreensDto>({
    queryKey: screensKeys.connected(),
    queryFn: () => api.get<ConnectedScreensDto>('/api/admin/screens/connected').then((r) => r.data),
    refetchInterval: 30_000,
  })
}

/** Excel-срез live-списка POSM с адресами и признаком клиентского экрана. */
export async function downloadConnectedScreensReport(): Promise<Blob> {
  const response = await api.get<Blob>('/api/admin/screens/connected/export.xlsx', {
    responseType: 'blob',
  })
  return response.data
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
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() }),
  })
}

export function useUpdatePlaylist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdatePlaylistRequest }) =>
      api.patch<PlaylistDto>(`/api/admin/screens/playlists/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() }),
  })
}

export function useDeletePlaylist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/screens/playlists/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() }),
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
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
    },
  })
}

export function useDeleteSlide() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/screens/slides/${id}`).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
    },
  })
}

export function useAssignSlide() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, req }: { id: string; req: AssignSlideRequest }) =>
      api.post<SlideDto>(`/api/admin/screens/slides/${id}/assign`, req).then((r) => r.data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: screensKeys.slides() })
      qc.invalidateQueries({ queryKey: screensKeys.playlistsRoot() })
    },
  })
}
