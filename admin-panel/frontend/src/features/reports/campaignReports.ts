import axios from 'axios'
import { api } from '@/lib/api'
import type { AdminRole } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'

export function canExportCampaignReports(role: AdminRole | undefined): boolean {
  return role === 'HQ_HEAD' || role === 'SYSTEM_ADMIN'
}

export function almatyToday(): string {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Almaty', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function reportPeriodError(from: string, to: string): string | undefined {
  const valid = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    value.slice(0, 4) !== '0000' && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) &&
    new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
  if (!valid(from) || !valid(to)) return 'Укажите обе даты отчёта.'
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000
  if (days < 0) return 'Окончание периода не может быть раньше начала.'
  if (days >= 366) return 'Выберите период не более 366 дней включительно.'
  return undefined
}

export async function downloadCampaignReport(id: string, from: string, to: string, signal?: AbortSignal) {
  try {
    const response = await api.get<Blob>(`/api/admin/reports/campaigns/${encodeURIComponent(id)}/pdf`, {
      params: { from, to }, responseType: 'blob', signal, timeout: 30_000,
    })
    if (!String(response.headers['content-type']).toLowerCase().startsWith('application/pdf')) {
      throw new Error('Сервер вернул не PDF. Повторите выгрузку; если ошибка сохраняется, обратитесь в поддержку.')
    }
    return response.data
  } catch (error) {
    // Axios blob responses include JSON error bodies too. Decode only the error contract,
    // never save an HTML/JSON error page with a misleading .pdf extension.
    if (axios.isAxiosError(error) && error.response?.data instanceof Blob) {
      try {
        const body: unknown = JSON.parse(await error.response.data.text())
        if (body && typeof body === 'object' && !Array.isArray(body)) error.response.data = body
      } catch { /* describeError still handles HTTP status and timeouts */ }
    }
    throw error
  }
}

export function saveCampaignPdf(blob: Blob, from: string, to: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `campaign-report-${from}-${to}.pdf`
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
}

export { describeError as campaignReportError }
