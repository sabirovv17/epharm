import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { credentialExportApi, credentialWorkbookMime } from './credential-export-api'

vi.mock('@/lib/api', () => ({ api: { post: vi.fn(), delete: vi.fn() } }))
const id = '00000000-0000-4000-8000-000000000001'
const prepared = { id, expiresAt: '2026-01-01T00:00:00Z', rowCount: 1 }
const password = '  Synthetic-admin-password-only  '
const blob = new Blob(['PK-synthetic'], { type: credentialWorkbookMime })
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.delete).mockResolvedValue({ data: null })
})

it('uses two fresh password checks and accepts the server expiry authority without using the client clock', async () => {
  vi.mocked(api.post)
    .mockResolvedValueOnce({ data: prepared })
    .mockResolvedValueOnce({ data: blob })
  const signal = new AbortController().signal
  expect(await credentialExportApi.download(['synthetic-staff'], 'Audit', password, signal)).toBe(
    blob,
  )
  expect(api.post).toHaveBeenNthCalledWith(
    1,
    '/api/admin/learner-credentials/exports',
    {
      pharmacistIds: ['synthetic-staff'],
      purpose: 'Audit',
      currentPassword: password,
    },
    { signal },
  )
  expect(api.post).toHaveBeenNthCalledWith(
    2,
    `/api/admin/learner-credentials/exports/${id}/download`,
    { currentPassword: password },
    { signal, responseType: 'blob' },
  )
})

it('refuses metadata for another selection and revokes the prepared report', async () => {
  vi.mocked(api.post).mockResolvedValueOnce({ data: { ...prepared, rowCount: 2 } })
  await expect(
    credentialExportApi.download(['one'], 'Audit', password, new AbortController().signal),
  ).rejects.toThrow('Не удалось скачать')
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(api.delete).toHaveBeenCalledWith(`/api/admin/learner-credentials/exports/${id}`, {
    timeout: 5000,
  })
})

it('removes the password-bearing error config and never echoes a server exception', async () => {
  const failure = {
    isAxiosError: true,
    config: { data: password },
    response: { status: 403, data: { message: password } },
  }
  vi.mocked(api.post).mockResolvedValueOnce({ data: prepared }).mockRejectedValueOnce(failure)
  await expect(
    credentialExportApi.download(['one'], 'Audit', password, new AbortController().signal),
  ).rejects.toThrow('Проверьте текущий пароль')
  expect(failure.config.data).toBeUndefined()
  expect(api.delete).toHaveBeenCalledTimes(1)
})

it('does not download after cancellation even when the prepare transport resolves late', async () => {
  const controller = new AbortController()
  vi.mocked(api.post).mockImplementationOnce(async () => {
    controller.abort()
    return { data: prepared }
  })
  await expect(
    credentialExportApi.download(['one'], 'Audit', password, controller.signal),
  ).rejects.toHaveProperty('name', 'AbortError')
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(api.delete).toHaveBeenCalledTimes(1)
})

it.each(['text/html', 'application/json'])(
  'does not save a %s response as credentials',
  async (type) => {
    vi.mocked(api.post)
      .mockResolvedValueOnce({ data: prepared })
      .mockResolvedValueOnce({ data: new Blob(['not a workbook'], { type }) })
    await expect(
      credentialExportApi.download(['one'], 'Audit', password, new AbortController().signal),
    ).rejects.toThrow('Не удалось скачать')
    expect(api.delete).toHaveBeenCalledTimes(1)
  },
)
