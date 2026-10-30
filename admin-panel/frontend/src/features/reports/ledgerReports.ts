import axios from 'axios'
import { api } from '@/lib/api'
export interface LedgerEntry {
  id: string
  pharmacistId: string
  pharmacistName: string
  source: string
  sourceId: string
  amountKzt: number
  earnedDeltaKzt: number
  currency: string
  contextKey: string
  balanceAfterKzt: number | null
  createdAt: string
  historicalPharmacyId: string | null
  historicalPharmacyName: string | null
  historicalPharmacyStatus: string
  basisStatus: string | null
  state: string
}
export interface LedgerReport {
  from: string
  to: string
  timezone: string
  generatedAt: string
  asOfExclusive: string
  sourceFilter: string | null
  pharmacistFilter: string | null
  scopePharmacyIds: string[] | null
  entries: LedgerEntry[]
  totals: { source: string; entries: number; amountKzt: number; earnedDeltaKzt: number }[]
  unresolvedHistoricalRows: number | null
  historicalScopeNotice: string
  coverage: string
}
export interface LedgerFilter {
  from: string
  to: string
  source?: string
  pharmacistId?: string
}
export const ledgerSources = {
  receipt_bonus: 'Рекомендации',
  training_bonus: 'Обучение',
  merch_task: 'Задания мерча',
  admin_adjustment: 'Ручные корректировки',
  payout_reservation: 'Резервирование выплаты',
}
export async function readLedger(filter: LedgerFilter, signal: AbortSignal) {
  const response = await api.get<LedgerReport>('/api/admin/reports/ledger', {
    params: filter,
    signal,
  })
  const values = [
    ...response.data.entries.flatMap((row) => [
      row.amountKzt,
      row.earnedDeltaKzt,
      ...(row.balanceAfterKzt === null ? [] : [row.balanceAfterKzt]),
    ]),
    ...response.data.totals.flatMap((row) => [row.amountKzt, row.earnedDeltaKzt]),
  ]
  if (values.some((value) => !Number.isSafeInteger(value)))
    throw new Error(
      'Сумма выходит за точный диапазон отображения. Используйте серверную выгрузку Excel.',
    )
  return response.data
}
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
export async function downloadReport(
  path: string,
  params: Record<string, string | undefined>,
  signal: AbortSignal,
) {
  try {
    const response = await api.get<Blob>(path, {
      params,
      responseType: 'blob',
      signal,
      timeout: 30_000,
    })
    if (!String(response.headers['content-type']).toLowerCase().startsWith(XLSX))
      throw new Error('Сервер не вернул Excel. Файл не сохранён.')
    return response.data
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 413)
      throw new Error('В выгрузке слишком много данных. Сузьте фильтры и повторите запрос.', {
        cause: error,
      })
    if (axios.isAxiosError(error) && error.response?.status === 429)
      throw new Error('Слишком много запросов на выгрузку. Повторите позже.', { cause: error })
    if (axios.isAxiosError(error) && error.response?.data instanceof Blob) {
      try {
        error.response.data = JSON.parse(await error.response.data.text())
      } catch {
        /* preserve HTTP error */
      }
    }
    throw error
  }
}
export function saveReport(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(URL.revokeObjectURL.bind(URL, url), 1000)
}
