import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { useUiStore } from '@/app/store'
import LearnerTrainingPage from './LearnerTrainingPage'

const IIN = '000000000001'
const OTHER_IIN = '000000000002'
const CHALLENGES = ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb']
const SECRETS = ['a'.repeat(43), 'b'.repeat(43)]
const PASSWORD = 'SyntheticReset123!'
const AUTH_TOKENS = {
  accessToken: 'synthetic-mobile-access',
  refreshToken: 'synthetic-mobile-refresh',
}
const PHARMACIST = {
  id: 'synthetic-pharmacist',
  name: 'Тестовый фармацевт',
  pharmacyName: 'Тестовая аптека',
  city: 'Тестовый город',
}
const BASE = '/api/mobile/auth/password-reset'
type User = ReturnType<typeof userEvent.setup>

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function server(
  options: { expiresAt?: string; verify?: () => Promise<Response>; completeFailure?: boolean } = {},
) {
  let requests = 0
  const calls: { path: string; body: Record<string, unknown>; signal?: AbortSignal | null }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input)
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {}
    calls.push({ path, body, signal: init?.signal })
    if (path === '/api/mobile/auth/activation/status')
      return json({ passwordSet: true, phoneMasked: '+7 (***) ***-**-00' })
    if (path === `${BASE}/request`) {
      return json({
        challengeId: CHALLENGES[requests++],
        phoneMasked: '+7 (***) ***-**-00',
        ttlSeconds: 300,
        retryAfterSeconds: 60,
      })
    }
    if (path === `${BASE}/verify`) {
      if (options.verify) return options.verify()
      return json({
        resetToken: SECRETS[body.challengeId === CHALLENGES[0] ? 0 : 1],
        expiresAt: options.expiresAt ?? new Date(Date.now() + 600_000).toISOString(),
        phoneMasked: '+7 (***) ***-**-00',
      })
    }
    if (path === `${BASE}/complete`) {
      return options.completeFailure
        ? json({ code: 'OTP_INVALID', message: 'Синтетический отказ восстановления' }, 400)
        : json({ tokens: AUTH_TOKENS, pharmacist: PHARMACIST })
    }
    if (path === '/api/mobile/auth/me') return json(PHARMACIST)
    if (path === '/api/mobile/training')
      return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
    if (path === '/api/mobile/me/balance') return json({ balanceKzt: 0, currency: 'KZT' })
    if (path.startsWith('/api/mobile/me/recommendations'))
      return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
    return json({ message: 'Unexpected synthetic request' }, 500)
  })
  return (suffix: string) =>
    calls.filter((call) => call.path === (suffix.startsWith('/') ? suffix : `${BASE}/${suffix}`))
}

function portal() {
  render(
    <MemoryRouter initialEntries={['/learn']}>
      <LearnerTrainingPage />
    </MemoryRouter>,
  )
}

async function identify(user: User, iin = IIN) {
  const input = screen.getByRole('textbox', { name: 'ИИН' })
  await user.clear(input)
  await user.type(input, iin)
  await user.click(screen.getByRole('button', { name: 'Продолжить' }))
  await screen.findByRole('button', { name: 'Забыли пароль?' })
}

async function requestReset(user: User) {
  await user.click(screen.getByRole('button', { name: 'Забыли пароль?' }))
  await screen.findByRole('textbox', { name: 'Код из SMS' })
}

async function verify(user: User) {
  await user.type(screen.getByRole('textbox', { name: 'Код из SMS' }), '1234')
  await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
}

async function newPassword(user: User) {
  await user.type(await screen.findByLabelText('Новый пароль', { exact: true }), PASSWORD)
  await user.type(screen.getByLabelText('Повторите пароль', { exact: true }), PASSWORD)
}

function noPersistedProof(writes: ReturnType<typeof vi.spyOn>) {
  const serialized =
    JSON.stringify(writes.mock.calls) +
    JSON.stringify({ ...localStorage }) +
    JSON.stringify({ ...sessionStorage })
  for (const value of [...CHALLENGES, ...SECRETS, IIN, OTHER_IIN, PASSWORD])
    expect(serialized).not.toContain(value)
}

function noLogin() {
  expect(localStorage.getItem('epharm.learner.tokens')).toBeNull()
  expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
  expect(screen.queryByRole('heading', { name: PHARMACIST.name })).not.toBeInTheDocument()
}

beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  useUiStore.setState({ language: 'ru' })
  vi.stubGlobal('scrollTo', vi.fn())
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('learner password reset proof', () => {
  it('completes only with the exact challenge and returned secret, keeping recovery proof out of persistent storage', async () => {
    const calls = server()
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    portal()
    await identify(user)
    await requestReset(user)
    expect(calls('request').map((call) => call.body)).toEqual([{ iin: IIN }])
    expect(calls('complete')).toHaveLength(0)
    await verify(user)
    await screen.findByLabelText('Новый пароль', { exact: true })
    expect(calls('verify').map((call) => call.body)).toEqual([
      { iin: IIN, challengeId: CHALLENGES[0], code: '1234' },
    ])
    noLogin()
    noPersistedProof(writes)
    await newPassword(user)
    await user.click(screen.getByRole('button', { name: 'Сохранить новый пароль и войти' }))
    expect(await screen.findByRole('heading', { name: PHARMACIST.name })).toBeInTheDocument()
    expect(calls('complete').map((call) => call.body)).toEqual([
      { iin: IIN, resetToken: SECRETS[0], password: PASSWORD },
    ])
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens')!)).toEqual(AUTH_TOKENS)
    noPersistedProof(writes)
  })

  it('clears recovery on back and ignores a late verify response even when the transport ignores abort', async () => {
    let resolve!: (response: Response) => void
    const pending = new Promise<Response>((done) => {
      resolve = done
    })
    const calls = server({ verify: () => pending })
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    portal()
    await identify(user)
    await requestReset(user)
    await verify(user)
    await waitFor(() => expect(calls('verify')).toHaveLength(1))
    await user.click(screen.getByRole('button', { name: 'Вернуться ко входу' }))
    expect(calls('verify')[0].signal?.aborted).toBe(true)
    await act(async () => {
      resolve(
        json({
          resetToken: SECRETS[0],
          expiresAt: new Date(Date.now() + 600_000).toISOString(),
          phoneMasked: '+7 (***) ***-**-00',
        }),
      )
      await pending
    })
    expect(screen.getByRole('heading', { name: 'Введите пароль' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Новый пароль', { exact: true })).not.toBeInTheDocument()
    await requestReset(user)
    expect(screen.getByRole('textbox', { name: 'Код из SMS' })).toHaveValue('')
    expect(calls('request')).toHaveLength(2)
    expect(calls('complete')).toHaveLength(0)
    noLogin()
    noPersistedProof(writes)
  })

  it('discards the previous IIN grant and requires a fresh proof for the newly selected IIN', async () => {
    const calls = server()
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    portal()
    await identify(user)
    await requestReset(user)
    await verify(user)
    await newPassword(user)
    await user.click(screen.getByRole('button', { name: 'Изменить' }))
    await identify(user, OTHER_IIN)
    expect(screen.getByLabelText('Пароль', { exact: true })).toHaveValue('')
    await requestReset(user)
    expect(screen.getByRole('textbox', { name: 'Код из SMS' })).toHaveValue('')
    await verify(user)
    expect(await screen.findByLabelText('Новый пароль', { exact: true })).toHaveValue('')
    await newPassword(user)
    await user.click(screen.getByRole('button', { name: 'Сохранить новый пароль и войти' }))
    await screen.findByRole('heading', { name: PHARMACIST.name })
    expect(calls('verify')[1].body).toEqual({
      iin: OTHER_IIN,
      challengeId: CHALLENGES[1],
      code: '1234',
    })
    expect(calls('complete').map((call) => call.body)).toEqual([
      { iin: OTHER_IIN, resetToken: SECRETS[1], password: PASSWORD },
    ])
    noPersistedProof(writes)
  })

  it('drops an expired grant before sending a complete request and asks for a new SMS confirmation', async () => {
    const expiry = Date.now() + 60_000
    const calls = server({ expiresAt: new Date(expiry).toISOString() })
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    portal()
    await identify(user)
    await requestReset(user)
    await verify(user)
    await newPassword(user)
    vi.spyOn(Date, 'now').mockReturnValue(expiry + 1)
    await user.click(screen.getByRole('button', { name: 'Сохранить новый пароль и войти' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Подтверждение истекло. Запросите новый SMS-код.',
    )
    expect(screen.getByLabelText('Пароль', { exact: true })).toHaveValue('')
    expect(calls('complete')).toHaveLength(0)
    noLogin()
    noPersistedProof(writes)
  })

  it('does not log in after a failed complete and clears both password fields and the reusable recovery screen', async () => {
    const calls = server({ completeFailure: true })
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    portal()
    await identify(user)
    await requestReset(user)
    await verify(user)
    await newPassword(user)
    await user.click(screen.getByRole('button', { name: 'Сохранить новый пароль и войти' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Синтетический отказ восстановления')
    expect(screen.getByRole('heading', { name: 'Введите пароль' })).toBeInTheDocument()
    expect(screen.getByLabelText('Пароль', { exact: true })).toHaveValue('')
    expect(screen.queryByLabelText('Повторите пароль', { exact: true })).not.toBeInTheDocument()
    expect(calls('complete')[0].body).toEqual({
      iin: IIN,
      resetToken: SECRETS[0],
      password: PASSWORD,
    })
    expect(calls('/api/mobile/auth/me')).toHaveLength(0)
    noLogin()
    noPersistedProof(writes)
  })
})
