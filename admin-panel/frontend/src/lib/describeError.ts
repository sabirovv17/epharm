// describeError — превращает AxiosError / ApiErrorResponse в человеческое сообщение
// для UI. Различает network errors / HTTP коды / domain ErrorCode.
//
// Bug R fix: вместо «Backend недоступен или сессия истекла» (на ВСЁ) —
// конкретная причина в каждом случае.
//
// i18n-fix: сообщения берутся из словаря (err.* ключи) на ТЕКУЩЕМ языке. Язык
// читаем из стора напрямую (getState) — функция вызывается в колбэках (не хук),
// поэтому в режиме «Қазақша» ошибки тоже на казахском, а не на русском.

import axios, { type AxiosError } from 'axios'
import type { ApiErrorResponse } from './api-types'
import { translate } from '@/i18n'
import { useUiStore } from '@/app/store'

export function describeError(err: unknown): string {
  const lang = useUiStore.getState().language
  const tr = (key: string, vars?: Record<string, string | number>) => translate(lang, key, vars)

  if (!err) return tr('err.unknown')

  if (axios.isAxiosError<ApiErrorResponse>(err)) {
    const e = err as AxiosError<ApiErrorResponse>

    // Network-level: соединение не установилось, таймаут, сервер не ответил.
    if (e.code === 'ERR_NETWORK' || e.message.toLowerCase().includes('network')) {
      return tr('err.network')
    }
    if (e.code === 'ECONNABORTED' || e.message.toLowerCase().includes('timeout')) {
      return tr('err.timeout')
    }

    const status = e.response?.status
    const apiCode = e.response?.data?.code
    const apiMsg = e.response?.data?.message

    // Domain ErrorCode — самый точный
    if (apiCode === 'INVALID_CREDENTIALS') return tr('err.invalidCreds')
    if (apiCode === 'INVALID_REFRESH_TOKEN') return tr('err.sessionExpired')
    if (apiCode === 'VALIDATION_FAILED') return apiMsg ?? tr('err.validation')
    if (apiCode === 'NOT_FOUND') return apiMsg ?? tr('err.notFound')
    if (apiCode === 'CONFLICT') return apiMsg ?? tr('err.conflict')
    if (apiCode === 'FORBIDDEN' || apiCode === 'UNAUTHORIZED') {
      return tr('err.forbidden')
    }
    if (apiCode === 'USER_NOT_ACTIVE') return tr('err.blocked')

    // HTTP status fallback (если backend не отдал ErrorCode)
    if (status === 401) return tr('err.sessionExpired')
    if (status === 403) return tr('err.forbidden')
    if (status === 404) return tr('err.notFound')
    if (status === 409) return tr('err.conflict')
    if (status === 422) return tr('err.validation')
    if (status && status >= 500) return tr('err.server')
    if (status && status >= 400) return apiMsg ?? tr('err.http', { status })

    return apiMsg ?? tr('err.generic')
  }

  if (err instanceof Error) return err.message

  return tr('err.unknown')
}
