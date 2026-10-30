import { act, renderHook, waitFor } from '@testing-library/react'
import { keepPreviousData, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import type { PromoChangesViewDto } from '@/lib/api-types'
import { promoKeys } from './promo'
import { promoRulesKeys } from './promoRules'
import {
  promoChangesKeys,
  useApplyPromoChanges,
  usePromoChanges,
  useSavePromoChanges,
} from './promoChanges'

vi.mock('@/lib/api', () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn() } }))

const state: PromoChangesViewDto = {
  promoId: 'demo-01',
  revision: 7,
  hasPendingChanges: true,
  conflict: false,
  canSave: true,
  canApply: true,
  readinessIssues: [],
  updatedAt: null,
  updatedBy: null,
  bundle: {
    campaign: { title: 'Демо', pharmacistBonus: 0, barcodes: ['0012345678905', '4601164003157'] },
    rules: {
      replacements: [],
      crossSells: [],
      script: '',
      advantages: [],
      comparison: [],
      partnerLabel: null,
      goalLabel: null,
      goalTarget: null,
      goalBonus: null,
    },
  },
}
let client: QueryClient
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}
beforeEach(() => {
  vi.resetAllMocks()
  client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, placeholderData: keepPreviousData },
      mutations: { retry: false },
    },
  })
  vi.mocked(api.get).mockResolvedValue({ data: state })
  vi.mocked(api.put).mockResolvedValue({ data: { ...state, revision: 8 } })
  vi.mocked(api.post).mockResolvedValue({
    data: { ...state, revision: 8, hasPendingChanges: false },
  })
})

describe('D28 pending publication transport and cache', () => {
  it('forces a fresh read for cached revisions and cancels on unmount', async () => {
    client.setQueryData(promoChangesKeys.detail('demo-01'), state)
    vi.mocked(api.get).mockImplementation(() => new Promise(() => {}))
    const view = renderHook(() => usePromoChanges('demo-01'), { wrapper })
    expect(view.result.current.data).toEqual(state)
    expect(view.result.current.isFetchedAfterMount).toBe(false)
    await waitFor(() => expect(api.get).toHaveBeenCalledOnce())
    const options = vi.mocked(api.get).mock.calls[0][1]!
    expect(options.signal).toBeInstanceOf(AbortSignal)
    view.unmount()
    expect(options.signal!.aborted).toBe(true)
  })
  it('does not carry the previous campaign into a new campaign or retry a 404 through legacy endpoints', async () => {
    const view = renderHook(({ id }) => usePromoChanges(id), {
      wrapper,
      initialProps: { id: 'demo-01' },
    })
    await waitFor(() => expect(view.result.current.isSuccess).toBe(true))
    vi.mocked(api.get).mockRejectedValue(new Error('404 changes unavailable'))
    view.rerender({ id: 'other/id' })
    expect(view.result.current.data).toBeUndefined()
    await waitFor(() => expect(view.result.current.isError).toBe(true))
    expect(api.get).toHaveBeenLastCalledWith(
      '/api/admin/promo/other%2Fid/changes',
      expect.any(Object),
    )
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(api.patch).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()
  })
  it('saves a complete typed pending bundle and does not invalidate published state', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const view = renderHook(() => useSavePromoChanges(), { wrapper })
    await act(async () => {
      await view.result.current.mutateAsync({
        id: 'demo-01',
        expectedRevision: 7,
        bundle: state.bundle,
      })
    })
    expect(api.put).toHaveBeenCalledWith('/api/admin/promo/demo-01/changes', {
      expectedRevision: 7,
      bundle: state.bundle,
    })
    expect(client.getQueryData(promoChangesKeys.detail('demo-01'))).toEqual({
      ...state,
      revision: 8,
    })
    expect(invalidate).not.toHaveBeenCalled()
    expect(api.patch).not.toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()
  })
  it('applies only the saved revision and refreshes published campaign, rules and engine', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const view = renderHook(() => useApplyPromoChanges(), { wrapper })
    await act(async () => {
      await view.result.current.mutateAsync({ id: 'demo-01', expectedRevision: 7 })
    })
    expect(api.post).toHaveBeenCalledWith('/api/admin/promo/demo-01/changes/apply', {
      expectedRevision: 7,
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: promoKeys.all })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: promoRulesKeys.detail('demo-01') })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['rules'] })
    expect(client.getQueryData(promoChangesKeys.detail('demo-01'))).toMatchObject({
      hasPendingChanges: false,
      revision: 8,
    })
  })
  it('does not overwrite cached published/pending data or retry on a failed revision write', async () => {
    client.setQueryData(promoChangesKeys.detail('demo-01'), state)
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    vi.mocked(api.put).mockRejectedValue(new Error('409 revision conflict'))
    const view = renderHook(() => useSavePromoChanges(), { wrapper })
    await act(async () => {
      await expect(
        view.result.current.mutateAsync({
          id: 'demo-01',
          expectedRevision: 7,
          bundle: state.bundle,
        }),
      ).rejects.toThrow('409')
    })
    expect(api.put).toHaveBeenCalledOnce()
    expect(invalidate).not.toHaveBeenCalled()
    expect(client.getQueryData(promoChangesKeys.detail('demo-01'))).toEqual(state)
  })
  it('uses an explicit CAS discard without sending a replacement bundle or publishing', async () => {
    const view = renderHook(() => useSavePromoChanges(), { wrapper })
    await act(async () => {
      await view.result.current.mutateAsync({ id: 'demo-01', expectedRevision: 7, discard: true })
    })
    expect(api.put).toHaveBeenCalledWith('/api/admin/promo/demo-01/changes', {
      expectedRevision: 7,
      discard: true,
    })
    expect(api.post).not.toHaveBeenCalled()
    expect(api.patch).not.toHaveBeenCalled()
  })
})
