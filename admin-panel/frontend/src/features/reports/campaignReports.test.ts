import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { api } from '@/lib/api'
import { almatyToday, downloadCampaignReport } from './campaignReports'

vi.mock('@/lib/api', async (original) => ({
  ...await original<typeof import('@/lib/api')>(), api: { get: vi.fn() },
}))
beforeEach(() => vi.clearAllMocks())

describe('Report PDF transport', () => {
  it('uses the authenticated API client, encoded campaign id, exact dates and a bounded timeout', async () => {
    const pdf = new Blob(['%PDF-test'], { type: 'application/pdf' })
    vi.mocked(api.get).mockResolvedValue({ data: pdf, headers: { 'content-type': 'application/pdf' } })
    const signal = new AbortController().signal
    expect(await downloadCampaignReport('campaign/id', '2026-09-01', '2026-09-30', signal)).toBe(pdf)
    expect(api.get).toHaveBeenCalledWith('/api/admin/reports/campaigns/campaign%2Fid/pdf', {
      params: { from: '2026-09-01', to: '2026-09-30' }, responseType: 'blob', signal, timeout: 30_000,
    })
  })
  it('rejects HTML and JSON success responses, not saving them as PDFs', async () => {
    for (const type of ['text/html', 'application/json', undefined]) {
      vi.mocked(api.get).mockResolvedValue({ data: new Blob(['not a pdf']), headers: { 'content-type': type } })
      await expect(downloadCampaignReport('demo', '2026-09-01', '2026-09-30')).rejects.toThrow('Сервер вернул не PDF')
    }
  })
  it('decodes the structured blob error for useful validation feedback', async () => {
    const blob = new Blob(['error'])
    Object.defineProperty(blob, 'text', { value: async () => JSON.stringify({ code: 'VALIDATION_FAILED', message: 'Период слишком большой' }) })
    const error = new AxiosError('Request failed')
    error.response = { data: blob, status: 400, statusText: 'Bad Request', headers: {}, config: {} as never }
    vi.mocked(api.get).mockRejectedValue(error)
    await expect(downloadCampaignReport('demo', '2026-09-01', '2026-09-30')).rejects.toMatchObject({ response: { data: { code: 'VALIDATION_FAILED', message: 'Период слишком большой' } } })
  })
  it('preserves HTTP failure if proxy error content is not JSON', async () => {
    const blob = new Blob(['<html>Unavailable</html>'])
    Object.defineProperty(blob, 'text', { value: async () => '<html>Unavailable</html>' })
    const error = new AxiosError('Request failed')
    error.response = { data: blob, status: 503, statusText: 'Unavailable', headers: {}, config: {} as never }
    vi.mocked(api.get).mockRejectedValue(error)
    await expect(downloadCampaignReport('demo', '2026-09-01', '2026-09-30')).rejects.toBe(error)
    expect(error.response.data).toBe(blob)
  })
  it('uses the Almaty calendar date, not the viewer time zone or UTC day', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-09-30T20:00:00Z'))
      expect(almatyToday()).toBe('2026-10-01')
    } finally { vi.useRealTimers() }
  })
})
