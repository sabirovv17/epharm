import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { adminTaskApi, adminTaskGateway } from './tasks-api'

vi.mock('@/lib/api', () => ({ api: { get: vi.fn() } }))
beforeEach(() => vi.resetAllMocks())

describe('merch queue metadata and server filters', () => {
  it('preserves pending counts for mixed-state summaries and sends literal search with the page offset', async () => {
    const page = {
      items: [
        {
          id: 'mixed-task',
          status: 'revision_required',
          itemCount: 5,
          pendingReviewCount: 2,
          nextPendingReviewItemId: 'submitted-item',
          items: [],
          detailsIncluded: false,
        },
      ],
      offset: 20,
      limit: 20,
      hasMore: true,
    }
    vi.mocked(api.get).mockResolvedValue({ data: page })
    const signal = new AbortController().signal
    expect(await adminTaskApi.list(20, signal, { queue: 'review', q: 'Полка 100%' })).toEqual(page)
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/admin/merch/tasks', {
      params: { offset: 20, limit: 20, queue: 'review', q: 'Полка 100%' },
      signal,
    })
  })

  it('does not turn pending metadata into an item review permission in a fresh detail response', async () => {
    const detail = {
      id: 'task/with-id',
      pendingReviewCount: 1,
      nextPendingReviewItemId: 'own-item',
      items: [{ id: 'own-item', status: 'submitted', canReview: false }],
    }
    vi.mocked(api.get).mockResolvedValue({ data: detail })
    const signal = new AbortController().signal
    expect(await adminTaskGateway.get(detail.id, signal)).toEqual(detail)
    expect(api.get).toHaveBeenCalledExactlyOnceWith('/api/admin/merch/tasks/task%2Fwith-id', {
      signal,
    })
  })
})
