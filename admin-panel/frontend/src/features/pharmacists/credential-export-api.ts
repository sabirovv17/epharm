import axios from 'axios'
import { api } from '@/lib/api'

const base = '/api/admin/learner-credentials/exports'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const credentialWorkbookMime =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
export interface PreparedCredentials {
  id: string
  expiresAt: string
  rowCount: number
}

function safeFailure(error: unknown): Error {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  if (axios.isAxiosError(error) && error.config) error.config.data = undefined
  return new Error(
    status === 401 || status === 403
      ? 'Проверьте текущий пароль администратора и право на выбранных фармацевтов.'
      : status === 409
        ? 'Учётные данные или права изменились. Обновите реестр и повторите подтверждение.'
        : status === 410
          ? 'Срок выгрузки истёк. Подтвердите новую выгрузку.'
          : status === 429
            ? 'Достигнут лимит попыток. Повторите позже.'
            : status === 503
              ? 'Защищённая выгрузка пока недоступна. Обратитесь к администратору.'
              : 'Не удалось скачать учётные данные. Проверьте поля и повторите позже.',
  )
}

export const credentialExportApi = {
  async download(
    pharmacistIds: string[],
    purpose: string,
    currentPassword: string,
    signal: AbortSignal,
  ): Promise<Blob> {
    let prepared: PreparedCredentials | undefined
    try {
      prepared = (
        await api.post<PreparedCredentials>(
          base,
          { pharmacistIds, purpose, currentPassword },
          { signal },
        )
      ).data
      signal.throwIfAborted()
      if (
        !uuid.test(prepared.id) ||
        prepared.rowCount !== pharmacistIds.length ||
        !Number.isFinite(Date.parse(prepared.expiresAt))
      )
        throw new Error('Invalid credential report metadata')
      const response = await api.post<Blob>(
        `${base}/${encodeURIComponent(prepared.id)}/download`,
        { currentPassword },
        { signal, responseType: 'blob' },
      )
      signal.throwIfAborted()
      if (
        !(response.data instanceof Blob) ||
        response.data.type.split(';')[0].toLowerCase() !== credentialWorkbookMime ||
        response.data.size === 0 ||
        response.data.size > 2 * 1024 * 1024
      )
        throw new Error('Invalid credential report format')
      return response.data
    } catch (error) {
      if (prepared && uuid.test(prepared.id)) {
        void api
          .delete(`${base}/${encodeURIComponent(prepared.id)}`, { timeout: 5000 })
          .catch(() => {})
      }
      if (signal.aborted) throw new DOMException('Выгрузка отменена', 'AbortError')
      throw safeFailure(error)
    }
  },
}
