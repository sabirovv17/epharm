import { describe, expect, it, vi } from 'vitest'
import { placementGateway } from './placements-api'

describe('placement gateway identity and quote boundary', () => {
  it('external create ignores a supplied customer id and cannot call HQ transitions or assignment', async () => {
    const request = vi.fn().mockResolvedValue({})
    const api = placementGateway('producer', request)
    await api.create({
      producerId: 'foreign-producer',
      placementMonth: '2026-11-01',
      items: [{ shelfId: 'shelf-1', portion: 'whole' }],
      note: null,
    })
    expect(request).toHaveBeenCalledWith('/api/producer/placements', {
      method: 'POST',
      body: JSON.stringify({
        placementMonth: '2026-11-01',
        items: [{ shelfId: 'shelf-1', portion: 'whole' }],
        note: null,
      }),
    })
    await expect(api.transition('demo', 'approve', 1, null)).rejects.toThrow('HQ')
    await expect(
      api.create({ placementMonth: '2026-11-01', items: [], note: null }, true),
    ).rejects.toThrow('HQ')
    expect(request).toHaveBeenCalledTimes(1)
  })
  it('uses the placement-scoped internal catalog without any inventory endpoint', async () => {
    const request = vi.fn().mockResolvedValue({})
    const api = placementGateway('admin', request)
    const signal = new AbortController().signal
    await api.producers(signal)
    await api.catalog('producer-01', '2026-11-01', 0, signal)
    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/admin/merch/placements/producers',
      '/api/admin/merch/placements/catalog?producerId=producer-01&month=2026-11-01&limit=50&offset=0',
    ])
  })
  it('external picker fetches only the selected branch quote instead of fanning out all branches', async () => {
    const request = vi.fn().mockImplementation(async (path) =>
      String(path).includes('/availability')
        ? { items: [] }
        : {
            items: [
              { id: 'branch-a', name: 'Демо A', city: 'Демо', address: 'Демо, 1' },
              { id: 'branch-b', name: 'Демо B', city: 'Демо', address: 'Демо, 2' },
            ],
            total: 2,
            limit: 50,
            offset: 0,
          },
    )
    const api = placementGateway('producer', request)
    await api.catalog('', '2026-11-01', 0, new AbortController().signal, 'branch-b')
    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/producer/branches?limit=50&offset=0',
      '/api/producer/branches/branch-b/availability?month=2026-11-01',
    ])
  })
})
