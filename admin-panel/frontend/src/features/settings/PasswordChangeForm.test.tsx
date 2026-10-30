import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { useUiStore } from '@/app/store'
import { api } from '@/lib/api'
import { PasswordChangeForm } from './PasswordChangeForm'

vi.mock('@/lib/api', async (original) => ({
  ...(await original<typeof import('@/lib/api')>()),
  api: { post: vi.fn() },
}))
const logout = vi.fn()
beforeEach(() => {
  vi.clearAllMocks()
  useUiStore.setState({
    authedUser: {
      id: 'self',
      name: 'Сотрудник',
      email: 'self@example.test',
      role: 'TRAINER',
      company: '',
    },
    querySessionEpoch: 5,
    language: 'ru',
    passwordChangedNotice: false,
    logout,
  })
  vi.mocked(api.post).mockResolvedValue({ status: 204 })
})
function LoginResult() {
  const changed = useUiStore((s) => s.passwordChangedNotice)
  return <div>{changed ? 'Пароль изменён' : 'Вход'}</div>
}
function mount() {
  return render(
    <MemoryRouter initialEntries={['/account']}>
      <Routes>
        <Route path="/account" element={<PasswordChangeForm userId="self" sessionEpoch={5} />} />
        <Route path="/login" element={<LoginResult />} />
      </Routes>
    </MemoryRouter>,
  )
}
function fill(next = 'synthetic-new-pass', confirmation = next, current = 'legacy') {
  fireEvent.change(screen.getByLabelText('Текущий пароль'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('Новый пароль'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Повторите новый пароль'), {
    target: { value: confirmation },
  })
}
function submit() {
  fireEvent.submit(screen.getByRole('form', { name: 'Сменить пароль' }))
}
it.each(['short', 'я'.repeat(37)])(
  'rejects too short or excessive UTF-8 password without request: %s',
  (password) => {
    mount()
    fill(password)
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent('не более 72 байт')
    expect(api.post).not.toHaveBeenCalled()
  },
)
it('requires matching confirmation and a different password', () => {
  mount()
  fill('synthetic-new-pass', 'different-pass')
  submit()
  expect(screen.getByRole('alert')).toHaveTextContent('не совпадают')
  fill('synthetic-new-pass', 'synthetic-new-pass', 'synthetic-new-pass')
  submit()
  expect(screen.getByRole('alert')).toHaveTextContent('отличаться')
  expect(api.post).not.toHaveBeenCalled()
})
it('sends only current and new password, clears session and returns to login', async () => {
  mount()
  fill()
  submit()
  expect(await screen.findByText('Пароль изменён')).toBeVisible()
  expect(api.post).toHaveBeenCalledWith(
    '/api/admin/auth/password',
    { currentPassword: 'legacy', newPassword: 'synthetic-new-pass' },
    { signal: expect.any(AbortSignal) },
  )
  expect(logout).toHaveBeenCalledTimes(1)
  expect(screen.queryByLabelText('Новый пароль')).not.toBeInTheDocument()
})
it('locks fields and prevents duplicate submission while saving', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(api.post).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  mount()
  fill()
  submit()
  submit()
  expect(screen.getByLabelText('Новый пароль')).toBeDisabled()
  expect(api.post).toHaveBeenCalledTimes(1)
  await act(async () => resolve({ status: 204 }))
})
it('clears all secrets after server rejection without logging out or retrying', async () => {
  vi.mocked(api.post).mockRejectedValue({ isAxiosError: true, response: { status: 400 } })
  mount()
  fill()
  submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('Проверьте текущий пароль')
  for (const label of ['Текущий пароль', 'Новый пароль', 'Повторите новый пароль'])
    expect(screen.getByLabelText(label)).toHaveValue('')
  expect(logout).not.toHaveBeenCalled()
  expect(api.post).toHaveBeenCalledTimes(1)
})
it('explains uncertainty on network failure and does not retry a password change', async () => {
  vi.mocked(api.post).mockRejectedValue(new Error('network'))
  mount()
  fill()
  submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('попробуйте войти с новым паролем')
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(logout).not.toHaveBeenCalled()
})
it('does not log out another account after a late success', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(api.post).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  mount()
  fill()
  submit()
  act(() => useUiStore.setState({ querySessionEpoch: 6 }))
  await act(async () => resolve({ status: 204 }))
  expect(logout).not.toHaveBeenCalled()
  expect(screen.queryByText('Пароль изменён')).not.toBeInTheDocument()
})
it('aborts an in-flight request on unmount', async () => {
  vi.mocked(api.post).mockImplementation(() => new Promise(() => {}))
  const view = mount()
  fill()
  submit()
  await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1))
  const signal = vi.mocked(api.post).mock.calls[0][2]?.signal
  view.unmount()
  expect(signal?.aborted).toBe(true)
})

it('shows a finite wait on throttling without retrying or logging out', async () => {
  vi.mocked(api.post).mockRejectedValue({ isAxiosError: true, response: { status: 429 } })
  mount()
  fill()
  submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('Подождите 15 минут')
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(logout).not.toHaveBeenCalled()
})
