import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import type { AdminRole } from '@/lib/api-types'
import CampaignReportsPage from './CampaignReportsPage'
import { canExportCampaignReports, reportPeriodError } from './campaignReports'
const mocks = vi.hoisted(() => ({
  usePromos: vi.fn(),
  download: vi.fn(),
  save: vi.fn(),
  allowed: true,
}))
vi.mock('@/lib/queries/promo', () => ({ usePromos: mocks.usePromos }))
vi.mock('@/app/useAdministrativeCapabilities', () => ({
  useAdministrativeCapabilities: () => ({
    canExportCampaignReports: mocks.allowed,
    canManageCampaignProducer: false,
  }),
}))
vi.mock('./ledgerReports', () => ({ downloadReport: mocks.download, saveReport: mocks.save }))
vi.mock('./ProducerReportPanel', () => ({ default: () => null }))
function asRole(role: AdminRole) {
  useUiStore.setState({
    authedUser: { id: 'hq-test', name: 'HQ', email: 'test@example.invalid', company: 'Demo', role },
  })
}
function page() {
  return render(
    <MemoryRouter>
      <CampaignReportsPage />
    </MemoryRouter>,
  )
}
async function choose() {
  const user = userEvent.setup()
  await user.selectOptions(screen.getByRole('combobox', { name: 'Кампания' }), 'demo')
  return user
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.allowed = true
  asRole('HQ_HEAD')
  mocks.usePromos.mockReturnValue({
    data: [{ id: 'demo', title: 'Тестовая кампания', brand: 'Demo', status: 'archived' }],
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
  mocks.download.mockResolvedValue(new Blob(['synthetic-xlsx']))
})
describe('Campaign XLSX reports', () => {
  it('downloads only the selected campaign and exact dates; no PDF action remains in the new UI', async () => {
    page()
    const user = await choose()
    await user.clear(screen.getByLabelText('Начало периода'))
    await user.type(screen.getByLabelText('Начало периода'), '2026-09-01')
    await user.clear(screen.getByLabelText('Окончание периода'))
    await user.type(screen.getByLabelText('Окончание периода'), '2026-09-30')
    await user.click(screen.getByRole('button', { name: 'Excel: кампания' }))
    await waitFor(() =>
      expect(mocks.download).toHaveBeenCalledWith(
        '/api/admin/reports/campaigns/demo/xlsx',
        { from: '2026-09-01', to: '2026-09-30' },
        expect.any(AbortSignal),
      ),
    )
    expect(mocks.save).toHaveBeenCalledWith(
      expect.any(Blob),
      'campaign-report-2026-09-01-2026-09-30.xlsx',
    )
    expect(screen.queryByRole('button', { name: /PDF/ })).not.toBeInTheDocument()
  })
  it('never selects a campaign implicitly or bypasses the independent server export capability', async () => {
    const view = page()
    expect(screen.getByRole('button', { name: 'Excel: кампания' })).toBeDisabled()
    await choose()
    mocks.allowed = false
    view.rerender(
      <MemoryRouter>
        <CampaignReportsPage />
      </MemoryRouter>,
    )
    expect(screen.getByRole('button', { name: 'Excel: кампания' })).toBeDisabled()
    expect(mocks.download).not.toHaveBeenCalled()
  })
  it('keeps selection after an export failure and permits only an explicit retry', async () => {
    mocks.download.mockRejectedValueOnce(new Error('Тестовая ошибка'))
    page()
    const user = await choose()
    await user.click(screen.getByRole('button', { name: 'Excel: кампания' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Тестовая ошибка')
    expect(screen.getByRole('combobox', { name: 'Кампания' })).toHaveValue('demo')
    expect(mocks.save).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Excel: кампания' }))
    await waitFor(() => expect(mocks.save).toHaveBeenCalledOnce())
  })
  it('blocks duplicate export and aborts on leaving the page', async () => {
    mocks.download.mockImplementation(() => new Promise(() => {}))
    const view = page()
    const user = await choose()
    await user.click(screen.getByRole('button', { name: 'Excel: кампания' }))
    expect(screen.getByRole('button', { name: 'Формируем Excel…' })).toBeDisabled()
    const signal = mocks.download.mock.calls[0][2] as AbortSignal
    view.unmount()
    expect(signal.aborted).toBe(true)
    expect(mocks.download).toHaveBeenCalledOnce()
  })
  it('does not fetch campaigns for a brand manager or anonymous visitor', () => {
    asRole('BRAND_MANAGER')
    const view = page()
    expect(screen.getByText('Нет доступа к отчётам производителя')).toBeInTheDocument()
    expect(mocks.usePromos).not.toHaveBeenCalled()
    useUiStore.setState({ authedUser: null })
    view.rerender(
      <MemoryRouter>
        <CampaignReportsPage />
      </MemoryRouter>,
    )
    expect(mocks.usePromos).not.toHaveBeenCalled()
  })
  it('shows a campaign loading error and never exports an apparently successful empty report', () => {
    mocks.usePromos.mockReturnValue({
      isError: true,
      isLoading: false,
      error: new Error('Сеть недоступна'),
      refetch: vi.fn(),
    })
    page()
    expect(screen.getByRole('alert')).toHaveTextContent('Сеть недоступна')
    expect(screen.getByRole('button', { name: 'Excel: кампания' })).toBeDisabled()
  })
})
describe('Report period and roles', () => {
  it('accepts 1 and 366 days, rejects reversed, impossible, missing and longer periods', () => {
    expect(reportPeriodError('2026-10-15', '2026-10-15')).toBeUndefined()
    expect(reportPeriodError('2024-01-01', '2024-12-31')).toBeUndefined()
    for (const [from, to] of [
      ['2026-10-16', '2026-10-15'],
      ['2026-02-31', '2026-03-01'],
      ['', '2026-03-01'],
      ['2024-01-01', '2025-01-01'],
      ['0000-01-01', '0000-01-02'],
    ])
      expect(reportPeriodError(from, to)).toBeTruthy()
  })
  it('allows only HQ and system admin', () => {
    expect(canExportCampaignReports('HQ_HEAD')).toBe(true)
    expect(canExportCampaignReports('SYSTEM_ADMIN')).toBe(true)
    for (const role of [
      'BRAND_MANAGER',
      'TRAINER',
      'CATEGORY_LEAD',
      'FINANCE_REVIEWER',
      'TRAINING_MANAGER',
      'REGIONAL_MANAGER',
    ] as const)
      expect(canExportCampaignReports(role)).toBe(false)
    expect(canExportCampaignReports(undefined)).toBe(false)
  })
})
