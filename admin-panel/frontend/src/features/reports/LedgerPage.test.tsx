import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import { readLedger } from './ledgerReports'
import LedgerPage from './LedgerPage'
vi.mock('@/app/useAdministrativeCapabilities', () => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('./ledgerReports', async (original) => ({
  ...(await original<typeof import('./ledgerReports')>()),
  readLedger: vi.fn(),
}))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAdministrativeCapabilities).mockReturnValue({
    isPending: false,
    isError: false,
    canViewLedger: true,
    canExportLedger: false,
  } as ReturnType<typeof useAdministrativeCapabilities>)
  vi.mocked(readLedger).mockResolvedValue({
    from: '2026-10-01',
    to: '2026-10-29',
    timezone: 'Asia/Almaty',
    generatedAt: '2026-10-29T12:00:00Z',
    asOfExclusive: '2026-10-29T12:00:00Z',
    sourceFilter: null,
    pharmacistFilter: null,
    scopePharmacyIds: ['demo-pharmacy'],
    entries: [
      {
        id: 'demo',
        pharmacistId: 'demo',
        pharmacistName: 'Демо-сотрудник',
        source: 'training_bonus',
        sourceId: 'demo-assignment',
        amountKzt: 1000,
        earnedDeltaKzt: 1000,
        currency: 'KZT',
        contextKey: 'not-rendered',
        balanceAfterKzt: null,
        createdAt: '2026-10-29T11:00:00Z',
        historicalPharmacyId: null,
        historicalPharmacyName: null,
        historicalPharmacyStatus: 'not_recorded_in_source',
        basisStatus: null,
        state: 'posted',
      },
    ],
    totals: [],
    unresolvedHistoricalRows: null,
    historicalScopeNotice: 'История не подменяется текущей аптекой',
    coverage: 'Только проводки',
  })
})
describe('ledger financial projection', () => {
  it('does not fetch ledger data without the fresh server view permission', () => {
    vi.mocked(useAdministrativeCapabilities).mockReturnValue({
      isPending: false,
      canViewLedger: false,
    } as ReturnType<typeof useAdministrativeCapabilities>)
    render(<LedgerPage />)
    expect(screen.getByText('Нет доступа к начислениям')).toBeInTheDocument()
    expect(readLedger).not.toHaveBeenCalled()
  })
  it('shows withheld global balance and unknown historical lineage honestly, with a separate disabled export', async () => {
    render(<LedgerPage />)
    expect(await screen.findByText('Не записана в источнике')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '—' })).toBeInTheDocument()
    expect(screen.getByText(/не раскрывается в вашей области/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Excel: начисления' })).toBeDisabled()
    expect(screen.queryByText('not-rendered')).not.toBeInTheDocument()
    expect(screen.getByText(/не подтверждают банковский перевод/)).toBeInTheDocument()
  })
})
