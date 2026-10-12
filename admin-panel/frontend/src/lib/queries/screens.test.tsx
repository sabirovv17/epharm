import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  screensKeys,
  useUploadBroadcast,
  useUploadBroadcastProfileSlot,
  useUploadBroadcastSlot,
  useUploadSlide,
} from './screens'

const post = vi.hoisted(() => vi.fn())
vi.mock('@/lib/api', () => ({ api: { post } }))

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

beforeEach(() => {
  post.mockReset()
  qc.clear()
})

describe('screen media uploads', () => {
  it('uploads slot 5 with a media-specific timeout and updates only its profile', async () => {
    const profile = {
      id: 'pl_broadcast',
      name: 'Общий эфир',
      defaultProfile: true,
      assignedPharmacyIds: [],
      slides: [],
    }
    post.mockResolvedValueOnce({ data: profile })
    const file = new File(['video'], 'Normobakt.mp4', { type: 'video/mp4' })
    const { result } = renderHook(() => useUploadBroadcastProfileSlot(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ profileId: 'pl_broadcast', slot: 5, file })
    })

    expect(post).toHaveBeenCalledWith(
      '/api/admin/screens/broadcast/profiles/pl_broadcast/slots/5',
      expect.any(FormData),
      { timeout: 120_000 },
    )
    const form = post.mock.calls[0][1] as FormData
    expect(form.get('file')).toBe(file)
    expect(form.get('durationSec')).toBe('15')
    expect(qc.getQueryData(screensKeys.broadcastProfile('pl_broadcast'))).toEqual(profile)
  })

  it('refetches the slot after an ambiguous network failure', async () => {
    post.mockRejectedValueOnce(new Error('timeout'))
    const invalidate = vi.spyOn(qc, 'invalidateQueries')
    const { result } = renderHook(() => useUploadBroadcastProfileSlot(), { wrapper })

    await act(async () => {
      await expect(
        result.current.mutateAsync({
          profileId: 'pl_broadcast',
          slot: 5,
          file: new File(['video'], 'Normobakt.mp4', { type: 'video/mp4' }),
        }),
      ).rejects.toThrow('timeout')
    })

    expect(invalidate).toHaveBeenCalledWith({
      queryKey: screensKeys.broadcastProfile('pl_broadcast'),
    })
    invalidate.mockRestore()
  })

  it('keeps the longer timeout on legacy video and slide upload paths', async () => {
    post.mockResolvedValue({ data: {} })
    const file = new File(['video'], 'clip.mp4', { type: 'video/mp4' })
    const legacy = renderHook(() => useUploadBroadcast(), { wrapper })
    const slot = renderHook(() => useUploadBroadcastSlot(), { wrapper })
    const slide = renderHook(() => useUploadSlide(), { wrapper })

    await act(async () => {
      await legacy.result.current.mutateAsync({ file })
      await slot.result.current.mutateAsync({ slot: 5, file })
      await slide.result.current.mutateAsync({ file, title: 'Ролик', durationSec: 15 })
    })

    expect(post.mock.calls.map((call) => call[2])).toEqual([
      { timeout: 120_000 },
      { timeout: 120_000 },
      { timeout: 120_000 },
    ])
  })
})
