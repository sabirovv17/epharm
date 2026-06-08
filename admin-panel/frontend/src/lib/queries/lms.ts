// LMS — TanStack Query хуки для каталога курсов (Этап 3.6).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  CourseDto,
  CourseStatus,
  CreateCourseRequest,
  UpdateCourseRequest,
} from '@/lib/api-types'

export interface CourseListFilter {
  status?: CourseStatus
}

export const lmsKeys = {
  all: ['lms'] as const,
  list: (filter?: CourseListFilter) => [...lmsKeys.all, 'list', filter ?? {}] as const,
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
