// AI-Exam — TanStack Query хуки для банка вопросов (Этап 3.6).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  CreateExamQuestionRequest,
  ExamQuestionDto,
  ExamQuestionKind,
  UpdateExamQuestionRequest,
} from '@/lib/api-types'

export interface ExamQuestionFilter {
  kind?: ExamQuestionKind
}

export const aiExamKeys = {
  all: ['ai-exam'] as const,
  questions: (filter?: ExamQuestionFilter) =>
    [...aiExamKeys.all, 'questions', filter ?? {}] as const,
}

export function useExamQuestions(filter: ExamQuestionFilter = {}) {
  return useQuery<ExamQuestionDto[]>({
    queryKey: aiExamKeys.questions(filter),
    queryFn: () =>
      api
        .get<ExamQuestionDto[]>('/api/admin/ai-exam/questions', { params: filter })
        .then((r) => r.data),
  })
}

export function useCreateExamQuestion() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateExamQuestionRequest) =>
      api.post<ExamQuestionDto>('/api/admin/ai-exam/questions', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiExamKeys.all }),
  })
}

export function useUpdateExamQuestion() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateExamQuestionRequest }) =>
      api.patch<ExamQuestionDto>(`/api/admin/ai-exam/questions/${id}`, patch).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiExamKeys.all }),
  })
}

export function useDeleteExamQuestion() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.delete<void>(`/api/admin/ai-exam/questions/${id}`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiExamKeys.all }),
  })
}
