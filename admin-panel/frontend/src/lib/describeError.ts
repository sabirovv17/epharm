// describeError — превращает AxiosError / ApiErrorResponse в человеческое сообщение
// для UI. Различает network errors / HTTP коды / domain ErrorCode.
//
// Bug R fix: вместо «Backend недоступен или сессия истекла» (на ВСЁ) —
// конкретная причина в каждом случае.

import axios, { type AxiosError } from 'axios'
import type { ApiErrorResponse } from './api-types'

export function describeError(err: unknown): string {
  if (!err) return 'Неизвестная ошибка'

  if (axios.isAxiosError<ApiErrorResponse>(err)) {
    const e = err as AxiosError<ApiErrorResponse>

    // Network-level: соединение не установилось, таймаут, сервер не ответил.
    if (e.code === 'ERR_NETWORK' || e.message.toLowerCase().includes('network')) {
      return 'Сервер недоступен — проверьте, что backend запущен и доступен по сети'
    }
    if (e.code === 'ECONNABORTED' || e.message.toLowerCase().includes('timeout')) {
      return 'Запрос идёт слишком долго — попробуйте позже'
    }

    const status = e.response?.status
    const apiCode = e.response?.data?.code
    const apiMsg = e.response?.data?.message

    // Domain ErrorCode — самый точный
    if (apiCode === 'INVALID_CREDENTIALS') return 'Неверный email или пароль'
    if (apiCode === 'INVALID_REFRESH_TOKEN') return 'Сессия истекла — войдите снова'
    if (apiCode === 'VALIDATION_FAILED') return apiMsg ?? 'Проверьте корректность данных'
    if (apiCode === 'NOT_FOUND') return apiMsg ?? 'Запись не найдена'
    if (apiCode === 'CONFLICT') return apiMsg ?? 'Конфликт состояния — обновите страницу'
    if (apiCode === 'FORBIDDEN' || apiCode === 'UNAUTHORIZED') {
      return 'У вас нет доступа к этому разделу'
    }
    if (apiCode === 'USER_NOT_ACTIVE') return 'Учётная запись заблокирована'

    // HTTP status fallback (если backend не отдал ErrorCode)
    if (status === 401) return 'Сессия истекла — войдите снова'
    if (status === 403) return 'У вас нет доступа к этому разделу'
    if (status === 404) return 'Запись не найдена'
    if (status === 409) return 'Конфликт состояния — обновите страницу'
    if (status === 422) return 'Проверьте корректность данных'
    if (status && status >= 500) return 'На сервере произошла ошибка — мы уже разбираемся'
    if (status && status >= 400) return apiMsg ?? `Ошибка ${status}`

    return apiMsg ?? 'Не удалось выполнить запрос'
  }

  if (err instanceof Error) return err.message

  return 'Неизвестная ошибка'
}
