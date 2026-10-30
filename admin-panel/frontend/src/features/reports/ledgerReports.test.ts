import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/lib/api'
import { AxiosError } from 'axios'
import { downloadReport, readLedger, type LedgerReport } from './ledgerReports'
const report: LedgerReport = {
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
      id: 'entry-demo',
      pharmacistId: 'ph-demo',
      pharmacistName: 'Демо-сотрудник',
      source: 'training_bonus',
      sourceId: 'demo-assignment',
      amountKzt: 1000,
      earnedDeltaKzt: 1000,
      currency: 'KZT',
      contextKey: 'synthetic',
      balanceAfterKzt: null,
      createdAt: '2026-10-29T11:00:00Z',
      historicalPharmacyId: null,
      historicalPharmacyName: null,
      historicalPharmacyStatus: 'not_recorded_in_source',
      basisStatus: null,
      state: 'posted',
    },
  ],
  totals: [{ source: 'training_bonus', entries: 1, amountKzt: 1000, earnedDeltaKzt: 1000 }],
  unresolvedHistoricalRows: null,
  historicalScopeNotice: 'История с неизвестной аптекой не раскрывается',
  coverage: 'Только проводки кошелька',
}
afterEach(() => vi.restoreAllMocks())
describe('financial report transport', () => {
  it('keeps unknown historical pharmacy/global balance null and sends the exact source/date filter', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: report })
    const signal = new AbortController().signal
    const result = await readLedger(
      { from: report.from, to: report.to, source: 'training_bonus' },
      signal,
    )
    expect(result.entries[0].historicalPharmacyId).toBeNull()
    expect(result.entries[0].balanceAfterKzt).toBeNull()
    expect(result.unresolvedHistoricalRows).toBeNull()
    expect(get).toHaveBeenCalledWith('/api/admin/reports/ledger', {
      params: { from: report.from, to: report.to, source: 'training_bonus' },
      signal,
    })
  })
  it('refuses an inaccurate large monetary number instead of rounding it for display', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: {
        ...report,
        entries: [{ ...report.entries[0], amountKzt: Number.MAX_SAFE_INTEGER + 1 }],
      },
    })
    await expect(
      readLedger({ from: report.from, to: report.to }, new AbortController().signal),
    ).rejects.toThrow('точный диапазон')
  })
  it('never saves an HTML/JSON response as an Excel workbook', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: new Blob(['<html>error</html>']),
      headers: { 'content-type': 'text/html' },
    })
    await expect(
      downloadReport(
        '/api/admin/reports/ledger.xlsx',
        { from: report.from, to: report.to },
        new AbortController().signal,
      ),
    ).rejects.toThrow('не вернул Excel')
  })
  it.each([
    [413, 'Сузьте фильтры'],
    [429, 'Повторите позже'],
  ] as const)('does not truncate or retry a rejected export (%s)', async (status, message) => {
    const get = vi.spyOn(api, 'get').mockRejectedValue(
      new AxiosError('Synthetic rejection', undefined, undefined, undefined, {
        status,
        data: {},
        config: {} as never,
        headers: {},
        statusText: '',
      }),
    )
    await expect(
      downloadReport('/api/admin/merch/exports/tasks.xlsx', {}, new AbortController().signal),
    ).rejects.toThrow(message)
    expect(get).toHaveBeenCalledOnce()
  })
})
