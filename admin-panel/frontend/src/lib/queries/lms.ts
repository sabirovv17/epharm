// LMS — TanStack Query хуки для каталога курсов (Этап 3.6).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  CourseDto,
  CourseStatus,
  ChangeAssignmentFormatRequest,
  ChangeTrainingPreferenceRequest,
  CreateOfflineEventRequest,
  CreateCourseRequest,
  CreateTrainingAssignmentsRequest,
  CreateTrainingProgramRequest,
  EventParticipantDto,
  MarkAttendanceRequest,
  MassChangeTrainingPreferencesRequest,
  MassAssignmentResultDto,
  OfflineEventDto,
  PharmacistTrainingProfileDto,
  RecordAssessmentResultRequest,
  TrainingAssessmentResultDto,
  TrainingAssignmentDto,
  TrainingAssignmentFormatHistoryDto,
  TrainingAssignmentPageDto,
  TrainingAssignmentStatus,
  TrainingCertificateDto,
  TrainingDashboardDto,
  TrainingEventQrDto,
  TrainingFormat,
  TrainingPreferenceDto,
  TrainingProgramDto,
  TrainingProgramStatus,
  UpdateCourseRequest,
  UpdateOfflineEventRequest,
  UpdateTrainingProgramRequest,
} from '@/lib/api-types'

export interface CourseListFilter {
  status?: CourseStatus
}

export interface TrainingAssignmentFilter {
  format?: TrainingFormat
  status?: TrainingAssignmentStatus
  programId?: string
  q?: string
}

export interface TrainingAssignmentPageFilter extends TrainingAssignmentFilter {
  page: number
  size: number
}

export const lmsKeys = {
  all: ['lms'] as const,
  list: (filter?: CourseListFilter) => [...lmsKeys.all, 'list', filter ?? {}] as const,
  training: ['training'] as const,
  dashboard: () => [...lmsKeys.training, 'dashboard'] as const,
  programs: (status?: TrainingProgramStatus) =>
    [...lmsKeys.training, 'programs', status ?? 'all'] as const,
  assignments: (filter?: TrainingAssignmentFilter) =>
    [...lmsKeys.training, 'assignments', filter ?? {}] as const,
  assignmentPage: (filter: TrainingAssignmentPageFilter) =>
    [...lmsKeys.training, 'assignments', 'page', filter] as const,
  assessmentResults: (assignmentId: string) =>
    [...lmsKeys.training, 'assignments', assignmentId, 'results'] as const,
  formatHistory: (assignmentId: string) =>
    [...lmsKeys.training, 'assignments', assignmentId, 'format-history'] as const,
  events: () => [...lmsKeys.training, 'events'] as const,
  eventQr: (eventId: string) => [...lmsKeys.training, 'events', eventId, 'qr'] as const,
  participants: (eventId: string) =>
    [...lmsKeys.training, 'events', eventId, 'participants'] as const,
  certificates: () => [...lmsKeys.training, 'certificates'] as const,
  preferences: () => [...lmsKeys.training, 'preferences'] as const,
  preferenceHistory: (pharmacistId: string) =>
    [...lmsKeys.training, 'preferences', pharmacistId, 'history'] as const,
  pharmacistProfile: (pharmacistId: string) =>
    [...lmsKeys.training, 'pharmacists', pharmacistId, 'profile'] as const,
}

export function useCourses(filter: CourseListFilter = {}) {
  return useQuery<CourseDto[]>({
    queryKey: lmsKeys.list(filter),
    queryFn: () =>
      api.get<CourseDto[]>('/api/admin/lms/courses', { params: filter }).then((r) => r.data),
  })
}

export function useCreateCourse() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateCourseRequest) =>
      api.post<CourseDto>('/api/admin/lms/courses', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.all }),
  })
}

export function useUpdateCourse() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateCourseRequest }) =>
      api.patch<CourseDto>(`/api/admin/lms/courses/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.all }),
  })
}

export function useDeleteCourse() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/lms/courses/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.all }),
  })
}

export function useTrainingDashboard() {
  return useQuery<TrainingDashboardDto>({
    queryKey: lmsKeys.dashboard(),
    queryFn: () =>
      api.get<TrainingDashboardDto>('/api/admin/training/dashboard').then((r) => r.data),
  })
}

export function useTrainingPrograms(status?: TrainingProgramStatus) {
  return useQuery<TrainingProgramDto[]>({
    queryKey: lmsKeys.programs(status),
    queryFn: () =>
      api
        .get<TrainingProgramDto[]>('/api/admin/training/programs', {
          params: status ? { status } : undefined,
        })
        .then((r) => r.data),
  })
}

export function useCreateTrainingProgram() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateTrainingProgramRequest) =>
      api.post<TrainingProgramDto>('/api/admin/training/programs', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useUpdateTrainingProgram() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateTrainingProgramRequest }) =>
      api
        .patch<TrainingProgramDto>(`/api/admin/training/programs/${id}`, patch)
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useTrainingAssignments(filter: TrainingAssignmentFilter = {}) {
  return useQuery<TrainingAssignmentDto[]>({
    queryKey: lmsKeys.assignments(filter),
    queryFn: () =>
      api
        .get<TrainingAssignmentDto[]>('/api/admin/training/assignments', { params: filter })
        .then((r) => r.data),
  })
}

export function useTrainingAssignmentPage(filter: TrainingAssignmentPageFilter) {
  return useQuery<TrainingAssignmentPageDto>({
    queryKey: lmsKeys.assignmentPage(filter),
    queryFn: () =>
      api
        .get<TrainingAssignmentPageDto>('/api/admin/training/assignments/page', { params: filter })
        .then((r) => r.data),
  })
}

export function useCreateTrainingAssignments() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateTrainingAssignmentsRequest) =>
      api.post<MassAssignmentResultDto>('/api/admin/training/assignments', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useRecordAssessmentResult() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      assignmentId,
      stageId,
      request,
    }: {
      assignmentId: string
      stageId: string
      request: RecordAssessmentResultRequest
    }) =>
      api
        .patch<TrainingAssignmentDto>(
          `/api/admin/training/assignments/${assignmentId}/stages/${stageId}/result`,
          request,
        )
        .then((r) => r.data),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: lmsKeys.training })
      qc.invalidateQueries({ queryKey: lmsKeys.assessmentResults(variables.assignmentId) })
    },
  })
}

export function useTrainingAssessmentResults(assignmentId: string | null) {
  return useQuery<TrainingAssessmentResultDto[]>({
    queryKey: lmsKeys.assessmentResults(assignmentId ?? ''),
    queryFn: () =>
      api
        .get<
          TrainingAssessmentResultDto[]
        >(`/api/admin/training/assignments/${assignmentId}/results`)
        .then((r) => r.data),
    enabled: !!assignmentId,
  })
}

export function useChangeAssignmentFormat() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      assignmentId,
      request,
    }: {
      assignmentId: string
      request: ChangeAssignmentFormatRequest
    }) =>
      api
        .patch<TrainingAssignmentDto>(
          `/api/admin/training/assignments/${assignmentId}/format`,
          request,
        )
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useAssignmentFormatHistory(assignmentId: string | null) {
  return useQuery<TrainingAssignmentFormatHistoryDto[]>({
    queryKey: lmsKeys.formatHistory(assignmentId ?? ''),
    queryFn: () =>
      api
        .get<
          TrainingAssignmentFormatHistoryDto[]
        >(`/api/admin/training/assignments/${assignmentId}/format-history`)
        .then((r) => r.data),
    enabled: !!assignmentId,
  })
}

export async function downloadTrainingAssignments(
  filter: TrainingAssignmentFilter = {},
): Promise<Blob> {
  const response = await api.get<Blob>('/api/admin/training/assignments/export.csv', {
    params: filter,
    responseType: 'blob',
  })
  return response.data
}

export function useTrainingPreferences() {
  return useQuery<TrainingPreferenceDto[]>({
    queryKey: lmsKeys.preferences(),
    queryFn: () =>
      api.get<TrainingPreferenceDto[]>('/api/admin/training/preferences').then((r) => r.data),
  })
}

export function useTrainingPreferenceHistory(pharmacistId: string | null) {
  return useQuery<TrainingPreferenceDto[]>({
    queryKey: lmsKeys.preferenceHistory(pharmacistId ?? ''),
    queryFn: () =>
      api
        .get<
          TrainingPreferenceDto[]
        >(`/api/admin/training/pharmacists/${pharmacistId}/preference-history`)
        .then((r) => r.data),
    enabled: !!pharmacistId,
  })
}

export function usePharmacistTrainingProfile(pharmacistId: string | null) {
  return useQuery<PharmacistTrainingProfileDto>({
    queryKey: lmsKeys.pharmacistProfile(pharmacistId ?? ''),
    queryFn: () =>
      api
        .get<PharmacistTrainingProfileDto>(
          `/api/admin/training/pharmacists/${pharmacistId}/profile`,
        )
        .then((response) => response.data),
    enabled: !!pharmacistId,
  })
}

export function useChangeTrainingPreference() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      pharmacistId,
      request,
    }: {
      pharmacistId: string
      request: ChangeTrainingPreferenceRequest
    }) =>
      api
        .patch<TrainingPreferenceDto>(
          `/api/admin/training/pharmacists/${pharmacistId}/preference`,
          request,
        )
        .then((r) => r.data),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: lmsKeys.preferences() })
      qc.invalidateQueries({ queryKey: lmsKeys.preferenceHistory(variables.pharmacistId) })
    },
  })
}

export function useMassChangeTrainingPreferences() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (request: MassChangeTrainingPreferencesRequest) =>
      api
        .patch<TrainingPreferenceDto[]>('/api/admin/training/pharmacists/preferences', request)
        .then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.preferences() }),
  })
}

export function useOfflineEvents() {
  return useQuery<OfflineEventDto[]>({
    queryKey: lmsKeys.events(),
    queryFn: () => api.get<OfflineEventDto[]>('/api/admin/training/events').then((r) => r.data),
  })
}

export function useCreateOfflineEvent() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateOfflineEventRequest) =>
      api.post<OfflineEventDto>('/api/admin/training/events', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useUpdateOfflineEvent() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateOfflineEventRequest }) =>
      api.patch<OfflineEventDto>(`/api/admin/training/events/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: lmsKeys.training }),
  })
}

export function useEventParticipants(eventId: string | null) {
  return useQuery<EventParticipantDto[]>({
    queryKey: lmsKeys.participants(eventId ?? ''),
    queryFn: () =>
      api
        .get<EventParticipantDto[]>(`/api/admin/training/events/${eventId}/participants`)
        .then((r) => r.data),
    enabled: !!eventId,
  })
}

export function useTrainingEventQr(eventId: string | null) {
  return useQuery<TrainingEventQrDto>({
    queryKey: lmsKeys.eventQr(eventId ?? ''),
    queryFn: () =>
      api
        .get<TrainingEventQrDto>(`/api/admin/training/events/${eventId}/qr`)
        .then((response) => response.data),
    enabled: !!eventId,
    staleTime: 5 * 60 * 1000,
  })
}

export function useMarkAttendance() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      eventId,
      participantId,
      request,
    }: {
      eventId: string
      participantId: string
      request: MarkAttendanceRequest
    }) =>
      api
        .patch<EventParticipantDto>(
          `/api/admin/training/events/${eventId}/participants/${participantId}`,
          request,
        )
        .then((r) => r.data),
    onSuccess: (_, variables) => {
      qc.invalidateQueries({ queryKey: lmsKeys.participants(variables.eventId) })
      qc.invalidateQueries({ queryKey: lmsKeys.dashboard() })
      qc.invalidateQueries({ queryKey: lmsKeys.assignments() })
    },
  })
}

export function useTrainingCertificates() {
  return useQuery<TrainingCertificateDto[]>({
    queryKey: lmsKeys.certificates(),
    queryFn: () =>
      api.get<TrainingCertificateDto[]>('/api/admin/training/certificates').then((r) => r.data),
  })
}
