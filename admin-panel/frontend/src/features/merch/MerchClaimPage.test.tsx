import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MerchClaimPage from './MerchClaimPage'
import { captureMerchClaimNavigation } from './claimNavigation'

const challenge = 'v1.signed-challenge-payload.signature'
const tokens = { accessToken: 'epharm-access-token', refreshToken: 'epharm-refresh-token' }
const grant = 'opaque_grant_that_is_at_least_32_characters'
const base = '/api/mobile/merch/executor-grants'
const returnUrl = `https://crm.inkar.kz/staff#claim=signed_nonce_123456789&grant=${grant}`
const future = () => new Date(Date.now() + 300_000).toISOString()
const context = () => ({ dispatchId: 'dispatch-1', taskTitle: 'Фото полки', pharmacyName: 'Аптека Алматы', amountKzt: 1500, currency: 'KZT', challengeExpiresAt: future(), phoneMasked: '+7 (***) ***-12-34' })
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  vi.restoreAllMocks()
  window.history.replaceState({}, '', `/learn/merch/claim#challenge=${challenge}`)
  captureMerchClaimNavigation()
})
afterEach(() => { vi.restoreAllMocks() })

describe('merch executor claim', () => {
  it('does not call APIs or request SMS without a valid QR challenge', () => {
    window.history.replaceState({}, '', '/learn/merch/claim')
    captureMerchClaimNavigation()
    const fetch = vi.spyOn(globalThis, 'fetch')
    render(<MerchClaimPage />)
    expect(screen.getByRole('alert')).toHaveTextContent('Отсканируйте QR-код')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('uses pharmacist login, then waits for an explicit SMS request', async () => {
    const user = userEvent.setup()
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === '/api/mobile/auth/login') return json({ tokens })
      if (String(input) === `${base}/context`) return json(context())
      return json({ message: 'Unexpected request' }, 500)
    })
    render(<MerchClaimPage />)
    await user.type(screen.getByLabelText('ИИН'), '000101500011')
    await user.type(screen.getByLabelText('Пароль'), 'Farm123!')
    await user.click(screen.getByRole('button', { name: 'Войти', exact: true }))
    expect(await screen.findByText('Фото полки')).toBeInTheDocument()
    expect(screen.getByText(/\+7 \(\*\*\*\)/)).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/mobile/auth/login', expect.objectContaining({ body: JSON.stringify({ iin: '000101500011', password: 'Farm123!' }) }))
    expect(fetch.mock.calls.some(([path]) => String(path).includes('/otp/'))).toBe(false)
    expect(screen.queryByRole('textbox', { name: /телефон/i })).not.toBeInTheDocument()
  })

  it('binds fresh OTP to the challenge, then offers only the opaque CRM handoff', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === `${base}/context`) return json(context())
      if (String(input) === `${base}/otp/request`) return json({ otpChallengeId: 'otp-1', phoneMasked: '+7 (***) ***-12-34', expiresAt: future(), retryAfterSeconds: 60 })
      if (String(input) === base) return json({ grant, expiresAt: future(), redirectUrl: returnUrl })
      return json({ message: 'Unexpected request' }, 500)
    })
    render(<MerchClaimPage />)
    await user.click(await screen.findByRole('button', { name: 'Получить код из SMS' }))
    expect(screen.getByRole('button', { name: /Новый код через/ })).toBeDisabled()
    await user.type(screen.getByLabelText('Код из SMS'), '123456')
    await user.click(screen.getByRole('button', { name: 'Подтвердить и продолжить' }))
    const link = await screen.findByRole('link', { name: 'Вернуться к заданию' })
    expect(link).toHaveAttribute('href', returnUrl)
    expect(link).toHaveAttribute('rel', 'noreferrer')
    expect(link.getAttribute('href')).not.toContain(tokens.accessToken)
    expect(fetch).toHaveBeenCalledWith(`${base}/otp/request`, expect.objectContaining({ body: JSON.stringify({ crmChallenge: challenge }) }))
    expect(fetch).toHaveBeenCalledWith(base, expect.objectContaining({ body: JSON.stringify({ crmChallenge: challenge, otpChallengeId: 'otp-1', code: '123456' }), headers: expect.objectContaining({ Authorization: `Bearer ${tokens.accessToken}` }) }))
    expect(fetch.mock.calls.every(([path]) => String(path).startsWith('/api/mobile/'))).toBe(true)
    expect(sessionStorage.getItem('epharm.learner.tokens')).not.toContain(grant)
  })

  it('does not send tokens to CRM or issue SMS when the pharmacy does not match', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ message: 'Задание относится к другой аптеке' }, 403))
    render(<MerchClaimPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('другой аптеке')
    expect(screen.queryByRole('button', { name: 'Получить код из SMS' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.every(([path]) => String(path) === `${base}/context`)).toBe(true)
    expect(screen.getByRole('button', { name: 'Войти под другой учётной записью' })).toBeInTheDocument()
  })

  it('refreshes an expired pharmacist token only against the same origin', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fresh = { accessToken: 'renewed-access-token', refreshToken: 'renewed-refresh-token' }
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (String(input) === '/api/mobile/auth/refresh') return json({ tokens: fresh })
      if (new Headers(init?.headers).get('Authorization') === `Bearer ${tokens.accessToken}`) return json({ message: 'Expired' }, 401)
      return json(context())
    })
    render(<MerchClaimPage />)
    expect(await screen.findByText('Фото полки')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/mobile/auth/refresh', expect.objectContaining({ body: JSON.stringify({ refreshToken: tokens.refreshToken }), credentials: 'omit' }))
    expect(JSON.parse(sessionStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(fresh)
  })

  it('rejects a foreign return address after OTP verification', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === `${base}/context`) return json(context())
      if (String(input) === `${base}/otp/request`) return json({ otpChallengeId: 'otp-1', phoneMasked: '+7 (***) ***-12-34', expiresAt: future(), retryAfterSeconds: 60 })
      return json({ grant, expiresAt: future(), redirectUrl: returnUrl.replace('crm.inkar.kz', 'evil.example') })
    })
    render(<MerchClaimPage />)
    await user.click(await screen.findByRole('button', { name: 'Получить код из SMS' }))
    await user.type(screen.getByLabelText('Код из SMS'), '123456')
    await user.click(screen.getByRole('button', { name: 'Подтвердить и продолжить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('безопасно')
    expect(screen.queryByRole('link', { name: 'Вернуться к заданию' })).not.toBeInTheDocument()
  })

  it('keeps an expired task read-only without sending a new SMS', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ ...context(), challengeExpiresAt: new Date(Date.now() - 1000).toISOString() }))
    render(<MerchClaimPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('устарела')
    expect(screen.queryByRole('button', { name: 'Получить код из SMS' })).not.toBeInTheDocument()
    expect(fetch.mock.calls).toHaveLength(1)
  })

  it('allows retrying a failed context request without losing the challenge', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(json({ message: 'Временная ошибка' }, 503)).mockResolvedValue(json(context()))
    render(<MerchClaimPage />)
    await user.click(await screen.findByRole('button', { name: 'Повторить проверку' }))
    expect(await screen.findByText('Фото полки')).toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  })

  it('does not display unsupported money data as a KZT reward', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ ...context(), currency: 'USD' }))
    render(<MerchClaimPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('неполные данные')
    expect(screen.queryByRole('button', { name: 'Получить код из SMS' })).not.toBeInTheDocument()
  })

  it('clears a rejected refresh session and requests pharmacist login again', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => json({ message: 'Войдите снова' }, 401))
    render(<MerchClaimPage />)
    expect(await screen.findByLabelText('ИИН')).toBeInTheDocument()
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
  })

  it('ignores a delayed context response after switching accounts', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    let resolve!: (response: Response) => void
    const pending = new Promise<Response>((complete) => { resolve = complete })
    const fetch = vi.spyOn(globalThis, 'fetch').mockReturnValue(pending)
    render(<MerchClaimPage />)
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    await user.click(screen.getByRole('button', { name: 'Войти под другой учётной записью' }))
    resolve(json(context()))
    expect(await screen.findByLabelText('ИИН')).toBeInTheDocument()
    expect(screen.queryByText('Фото полки')).not.toBeInTheDocument()
  })

  it('keeps an invalid OTP retryable without exposing a claim link', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input) === `${base}/context`) return json(context())
      if (String(input) === `${base}/otp/request`) return json({ otpChallengeId: 'otp-1', phoneMasked: '+7 (***) ***-12-34', expiresAt: future(), retryAfterSeconds: 60 })
      return json({ code: 'OTP_INVALID', message: 'Неверный код подтверждения' }, 400)
    })
    render(<MerchClaimPage />)
    await user.click(await screen.findByRole('button', { name: 'Получить код из SMS' }))
    await user.type(screen.getByLabelText('Код из SMS'), '000000')
    await user.click(screen.getByRole('button', { name: 'Подтвердить и продолжить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Неверный код')
    expect(screen.getByLabelText('Код из SMS')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Вернуться к заданию' })).not.toBeInTheDocument()
  })

  it('does not reflect upstream secrets or arbitrary error messages', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ message: `Failed challenge=${challenge} bearer=${tokens.accessToken} grant=${grant}` }, 503))
    render(<MerchClaimPage />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Не удалось выполнить запрос')
    expect(alert).not.toHaveTextContent(challenge)
    expect(alert).not.toHaveTextContent(tokens.accessToken)
    expect(alert).not.toHaveTextContent(grant)
  })

  it('does not accept a malformed OTP challenge', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => String(input) === `${base}/context`
      ? json(context()) : json({ otpChallengeId: 'otp-1', expiresAt: 'invalid', retryAfterSeconds: -1 }))
    render(<MerchClaimPage />)
    await user.click(await screen.findByRole('button', { name: 'Получить код из SMS' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось выполнить запрос')
    expect(screen.queryByLabelText('Код из SMS')).not.toBeInTheDocument()
  })
})
