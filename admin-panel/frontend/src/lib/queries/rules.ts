// Rules Engine — TanStack Query хуки.
// Каждый mutation инвалидирует список — таблица обновляется без локального state.

import { useMutation, useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query'
import { api } from '@/lib/api'
import type {
  CreateRuleRequest,
  RuleDto,
  RuleStatus,
  RuleType,
  UpdateRuleRequest,
} from '@/lib/api-types'

export interface RuleListFilter {
  type?: RuleType
  status?: RuleStatus
}

export const rulesKeys = {
  all: ['rules'] as const,
  list: (filter?: RuleListFilter) => [...rulesKeys.all, 'list', filter ?? {}] as const,
  detail: (id: string) => [...rulesKeys.all, 'detail', id] as const,
}

export function useRules(
  filter: RuleListFilter = {},
  options?: Partial<UseQueryOptions<RuleDto[]>>,
) {
  return useQuery<RuleDto[]>({
    queryKey: rulesKeys.list(filter),
    queryFn: () => api.get<RuleDto[]>('/api/admin/rules', { params: filter }).then((r) => r.data),
    ...options,
  })
}

export function useRule(id: string | null) {
  return useQuery<RuleDto>({
    queryKey: rulesKeys.detail(id ?? ''),
    queryFn: () => api.get<RuleDto>(`/api/admin/rules/${id}`).then((r) => r.data),
    enabled: !!id,
  })
}

export function useCreateRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateRuleRequest) =>
      api.post<RuleDto>('/api/admin/rules', req).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: rulesKeys.all }),
  })
}

export function useUpdateRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, patch }: { id: string; patch: UpdateRuleRequest }) =>
      api.patch<RuleDto>(`/api/admin/rules/${id}`, patch).then((r) => r.data),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: rulesKeys.all })
      qc.setQueryData(rulesKeys.detail(data.id), data)
    },
  })
}

export function useArchiveRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<RuleDto>(`/api/admin/rules/${id}/archive`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: rulesKeys.all }),
  })
}

export function useDuplicateRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      api.post<RuleDto>(`/api/admin/rules/${id}/duplicate`).then((r) => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: rulesKeys.all }),
  })
}
