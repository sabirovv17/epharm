import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { inventoryEditorApi } from './api'
vi.mock('@/lib/api', () => ({ api: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.post).mockResolvedValue({ data: { revision: 5 } })
  vi.mocked(api.put).mockResolvedValue({ data: { revision: 5 } })
  vi.mocked(api.get).mockResolvedValue({ data: new Blob(['synthetic'], { type: 'image/png' }) })
})
it('binds mutations to the exact branch and expected revision without altering existing prices', async () => {
  await inventoryEditorApi.shelf('branch/a', 'shelf?b', 4, {
    containerId: null,
    code: 'A',
    title: null,
    category: 'AA',
    type: 'shelf',
    width: 0.8,
    position: 0,
    maxProducts: 20,
    active: true,
  })
  expect(api.put).toHaveBeenCalledWith(
    '/api/admin/merch/branches/branch%2Fa/editor/shelves/shelf%3Fb',
    expect.objectContaining({ expectedRevision: 4 }),
  )
  expect(vi.mocked(api.put).mock.calls[0][1]).not.toHaveProperty('initialPrice')
  expect(vi.mocked(api.put).mock.calls[0][1]).not.toHaveProperty('baseMonthlyPrice')
})
it('fetches private photos through the authenticated API without public keys or arbitrary URLs', async () => {
  const signal = new AbortController().signal
  await inventoryEditorApi.photo('branch1', 'photo1', signal)
  expect(api.get).toHaveBeenCalledWith('/api/admin/merch/branches/branch1/editor/media/photo1', {
    responseType: 'blob',
    signal,
  })
})
it('accepts original WebP blobs and rejects executable media in the private passport viewer', async () => {
  vi.mocked(api.get).mockResolvedValue({
    data: new Blob(['synthetic-webp'], { type: 'image/webp' }),
  })
  await expect(
    inventoryEditorApi.photo('branch1', 'photo1', new AbortController().signal),
  ).resolves.toMatchObject({ type: 'image/webp' })
  vi.mocked(api.get).mockResolvedValue({ data: new Blob(['<svg/>'], { type: 'image/svg+xml' }) })
  await expect(
    inventoryEditorApi.photo('branch1', 'photo1', new AbortController().signal),
  ).rejects.toThrow('JPG/PNG/WebP')
})
it('submits bounded period and explicit future-price intent unchanged', async () => {
  const input = {
    shelfIds: ['s1'],
    fromMonth: '2027-01-01',
    toMonth: '2027-02-01',
    price: 20000,
    saleMode: null,
    halfPrice: null,
    applyFuture: false,
  }
  await inventoryEditorApi.prices('b1', 7, input)
  expect(api.post).toHaveBeenCalledWith('/api/admin/merch/branches/b1/editor/prices', {
    ...input,
    expectedRevision: 7,
  })
})
