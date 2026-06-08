// Тесты describeError — конкретные сообщения по AxiosError/HTTP code/ErrorCode.

import { describe, expect, it } from 'vitest'
import axios from 'axios'
import { describeError } from './describeError'

function mkAxiosError(over: {
  code?: string
  message?: string
  status?: number
  data?: { code?: string; message?: string }
}) {
  const e = new axios.AxiosError(
    over.message ?? '',
    over.code,
    undefined,
    undefined,
    over.status
      ? ({
          status: over.status,
          data: over.data ?? {},
          headers: {},
          config: {} as never,
          statusText: '',
        } as unknown as import('axios').AxiosResponse)
      : undefined,
  )
  return e
}

describe('describeError', () => {
  it('null/undefined → «Неизвестная ошибка»', () => {
    expect(describeError(undefined)).toBe('Неизвестная ошибка')
    expect(describeError(null)).toBe('Неизвестная ошибка')
  })

  it('non-axios Error → message', () => {
    expect(describeError(new Error('Boom'))).toBe('Boom')
  })

  it('ERR_NETWORK → backend unavailable message', () => {
    const err = mkAxiosError({ code: 'ERR_NETWORK', message: 'Network Error' })
    expect(describeError(err)).toMatch(/Сервер недоступен/i)
  })

  it('timeout (ECONNABORTED) → понятное сообщение', () => {
    const err = mkAxiosError({ code: 'ECONNABORTED', message: 'timeout' })
    expect(describeError(err)).toMatch(/долго/i)
  })

  it('ApiErrorCode=INVALID_CREDENTIALS → точная фраза', () => {
    const err = mkAxiosError({ status: 401, data: { code: 'INVALID_CREDENTIALS' } })
    expect(describeError(err)).toBe('Неверный email или пароль')
  })

  it('ApiErrorCode=INVALID_REFRESH_TOKEN → «Сессия истекла»', () => {
    const err = mkAxiosError({ status: 401, data: { code: 'INVALID_REFRESH_TOKEN' } })
    expect(describeError(err)).toMatch(/Сессия истекла/i)
  })

  it('ApiErrorCode=VALIDATION_FAILED → backend message', () => {
    const err = mkAxiosError({
      status: 400,
      data: { code: 'VALIDATION_FAILED', message: 'title is required' },
    })
    expect(describeError(err)).toBe('title is required')
  })

  it('ApiErrorCode=NOT_FOUND → backend message', () => {
    const err = mkAxiosError({
      status: 404,
      data: { code: 'NOT_FOUND', message: 'Promo not found' },
    })
    expect(describeError(err)).toBe('Promo not found')
  })

  it('ApiErrorCode=CONFLICT → backend message', () => {
    const err = mkAxiosError({
      status: 409,
      data: { code: 'CONFLICT', message: 'Archived cannot be edited' },
    })
    expect(describeError(err)).toBe('Archived cannot be edited')
  })

  it('HTTP 403 без ErrorCode → «нет доступа»', () => {
    const err = mkAxiosError({ status: 403 })
    expect(describeError(err)).toMatch(/нет доступа/i)
  })

  it('HTTP 500+ → «На сервере произошла ошибка»', () => {
    const err = mkAxiosError({ status: 500 })
    expect(describeError(err)).toMatch(/На сервере/i)
  })

  it('USER_NOT_ACTIVE → «заблокирована»', () => {
    const err = mkAxiosError({ status: 403, data: { code: 'USER_NOT_ACTIVE' } })
    expect(describeError(err)).toMatch(/заблокирована/i)
  })
})
