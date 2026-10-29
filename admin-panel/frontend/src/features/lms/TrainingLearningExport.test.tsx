import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { downloadReport, saveReport } from '@/features/reports/ledgerReports'
import TrainingLearningExport from './TrainingLearningExport'
vi.mock('@/features/reports/ledgerReports', () => ({
  downloadReport: vi.fn(),
  saveReport: vi.fn(),
}))
afterEach(() => vi.resetAllMocks())
describe('separate training learning XLSX filters', () => {
  it('sends only the applied assignment filters, with no hidden period or name search', async () => {
    vi.mocked(downloadReport).mockResolvedValue(new Blob(['synthetic-only']))
    render(
      <TrainingLearningExport
        mode="assignments"
        allowed
        filter={{ format: 'online', status: 'in_progress', programId: 'program-demo' }}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Excel: назначения' }))
    await waitFor(() =>
      expect(downloadReport).toHaveBeenCalledExactlyOnceWith(
        '/api/admin/training/exports/assignments.xlsx',
        { format: 'online', status: 'in_progress', programId: 'program-demo' },
        expect.any(AbortSignal),
      ),
    )
    expect(saveReport).toHaveBeenCalledOnce()
  })
  it('blocks unsupported active name search rather than ignoring it', async () => {
    render(<TrainingLearningExport mode="results" allowed nameSearch="Демо Фармацевт" />)
    expect(screen.getByRole('button', { name: 'Excel: результаты' })).toBeDisabled()
    expect(screen.getByText(/Очистите его/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button'))
    expect(downloadReport).not.toHaveBeenCalled()
  })
  it('denies export without its own capability even when other CSV/read rights exist', () => {
    render(<TrainingLearningExport mode="assignments" allowed={false} />)
    expect(screen.getByRole('button')).toBeDisabled()
    expect(downloadReport).not.toHaveBeenCalled()
  })
})
