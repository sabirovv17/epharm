import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '@/lib/api'
import ReportDownloadButton from './ReportDownloadButton'
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('separate XLSX action', () => {
  it('does not issue a request without the independent export permission', async () => {
    const get = vi.spyOn(api, 'get')
    render(
      <ReportDownloadButton
        allowed={false}
        path="/api/admin/reports/pharmacists.xlsx"
        params={{}}
        filename="pharmacists.xlsx"
        label="Excel: реестр"
      />,
    )
    await userEvent.click(screen.getByRole('button'))
    expect(get).not.toHaveBeenCalled()
  })
  it('downloads the exact report and cannot submit a surrounding PDF form', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({
      data: new Blob(['synthetic-xlsx']),
      headers: {
        'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      },
    })
    const submit = vi.fn((event) => event.preventDefault())
    const NativeUrl = URL
    vi.stubGlobal(
      'URL',
      class extends NativeUrl {
        static createObjectURL() {
          return 'blob:synthetic-only'
        }
        static revokeObjectURL() {}
      },
    )
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(
      <form onSubmit={submit}>
        <ReportDownloadButton
          allowed
          path="/api/admin/reports/campaigns/demo/xlsx"
          params={{ from: '2026-10-01', to: '2026-10-29' }}
          filename="campaign.xlsx"
          label="Excel: кампания"
        />
      </form>,
    )
    await userEvent.click(screen.getByRole('button'))
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith(
        '/api/admin/reports/campaigns/demo/xlsx',
        expect.objectContaining({
          params: { from: '2026-10-01', to: '2026-10-29' },
          responseType: 'blob',
          signal: expect.any(AbortSignal),
        }),
      ),
    )
    expect(submit).not.toHaveBeenCalled()
    expect(await screen.findByRole('status')).toHaveTextContent('Excel передан браузеру')
  })
  it('cancels an in-flight download when the independent permission is removed', async () => {
    let finish!: (value: unknown) => void
    const get = vi.spyOn(api, 'get').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const props = {
      path: '/api/admin/reports/ledger.xlsx',
      params: {},
      filename: 'ledger.xlsx',
      label: 'Excel',
    }
    const { rerender } = render(<ReportDownloadButton {...props} allowed />)
    await userEvent.click(screen.getByRole('button'))
    const signal = get.mock.calls[0][1]?.signal
    rerender(<ReportDownloadButton {...props} allowed={false} />)
    expect(signal?.aborted).toBe(true)
    await act(async () => {
      finish({
        data: new Blob(['synthetic-only']),
        headers: {
          'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        },
      })
    })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
