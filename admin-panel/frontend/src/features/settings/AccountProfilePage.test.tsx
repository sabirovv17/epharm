import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { useUiStore } from '@/app/store'
import { RequireAuth } from '@/app/RequireAuth'
import { mayPersistQuery } from '@/app/queryPersist'
import { api } from '@/lib/api'
import type { UserDto } from '@/lib/api-types'
import AccountProfilePage from './AccountProfilePage'

vi.mock('@/lib/api', async (original) => ({
  ...(await original<typeof import('@/lib/api')>()),
  api: { get: vi.fn(), post: vi.fn() },
}))
const person: UserDto = {
  id: 'training-1',
  name: 'Преподаватель',
  email: 'trainer@example.test',
  role: 'TRAINER',
  company: 'Учебный центр',
}
beforeEach(() => {
  vi.clearAllMocks()
  useUiStore.setState({
    authedUser: person,
    language: 'ru',
    querySessionEpoch: 1,
    passwordChangedNotice: false,
  })
  vi.mocked(api.get).mockResolvedValue({ data: person })
})
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/account']}>
        <Routes>
          <Route element={<RequireAuth />}>
            <Route path="/account" element={<AccountProfilePage />} />
          </Route>
          <Route path="/login" element={<LoginResult />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}
function LoginResult() {
  const changed = useUiStore((s) => s.passwordChangedNotice)
  return <div>{changed ? 'Пароль изменён — войдите заново' : 'Вход в систему'}</div>
}
it('allows an internal training role to view only its own fresh identity', async () => {
  mount()
  expect(await screen.findByDisplayValue(person.email)).toBeDisabled()
  expect(screen.getByDisplayValue(person.name)).toBeDisabled()
  expect(api.get).toHaveBeenCalledTimes(1)
  expect(api.get).toHaveBeenCalledWith(
    '/api/admin/auth/me',
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  )
  expect(mayPersistQuery(['admin-access-self-profile', person.id, 1])).toBe(false)
  expect(screen.queryByText('Управление доступом')).not.toBeInTheDocument()
})
it('does not load a profile without an authenticated account', () => {
  useUiStore.setState({ authedUser: null })
  mount()
  expect(screen.getByText('Вход в систему')).toBeVisible()
  expect(api.get).not.toHaveBeenCalled()
})
it('does not show stored profile as current after a load failure and supports retry', async () => {
  vi.mocked(api.get).mockRejectedValueOnce(new Error('Unavailable'))
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось подтвердить')
  expect(screen.queryByDisplayValue(person.name)).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: 'Повторить' }))
  expect(await screen.findByDisplayValue(person.email)).toBeDisabled()
})
it('rejects a profile belonging to another account', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: { ...person, id: 'other', name: 'Чужой профиль' } })
  mount()
  expect(await screen.findByRole('alert')).toBeVisible()
  expect(screen.queryByDisplayValue('Чужой профиль')).not.toBeInTheDocument()
})
it('does not display a late response from a previous session', async () => {
  let resolveFirst!: (value: { data: UserDto }) => void
  vi.mocked(api.get).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveFirst = resolve
      }),
  )
  mount()
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
  const next = { ...person, id: 'next', name: 'Следующий пользователь', email: 'next@example.test' }
  vi.mocked(api.get).mockResolvedValue({ data: next })
  act(() => useUiStore.setState({ authedUser: next, querySessionEpoch: 2 }))
  expect(await screen.findByDisplayValue(next.email)).toBeDisabled()
  await act(async () => resolveFirst({ data: person }))
  expect(screen.queryByDisplayValue(person.email)).not.toBeInTheDocument()
  expect(screen.getByDisplayValue(next.email)).toBeDisabled()
})

it('preserves successful password-change confirmation across RequireAuth logout', async () => {
  const previousLogout = useUiStore.getState().logout
  const logout = vi.fn(async () => {
    useUiStore.setState({ authedUser: null, querySessionEpoch: 2 })
  })
  useUiStore.setState({ logout })
  try {
    vi.mocked(api.post).mockResolvedValue({ status: 204 })
    mount()
    await screen.findByDisplayValue(person.email)
    fireEvent.change(screen.getByLabelText('Текущий пароль'), {
      target: { value: 'synthetic-old-password' },
    })
    fireEvent.change(screen.getByLabelText('Новый пароль'), {
      target: { value: 'synthetic-new-password' },
    })
    fireEvent.change(screen.getByLabelText('Повторите новый пароль'), {
      target: { value: 'synthetic-new-password' },
    })
    fireEvent.submit(screen.getByRole('form', { name: 'Сменить пароль' }))
    expect(await screen.findByText('Пароль изменён — войдите заново')).toBeVisible()
    expect(logout).toHaveBeenCalledTimes(1)
    expect(useUiStore.getState().authedUser).toBeNull()
  } finally {
    useUiStore.setState({ logout: previousLogout })
  }
})
