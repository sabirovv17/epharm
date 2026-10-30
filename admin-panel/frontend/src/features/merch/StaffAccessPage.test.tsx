import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import StaffAccessPage from './StaffAccessPage'
import { captureStaffEntryNavigation, clearStaffEntry } from './staffEntryNavigation'
import { STAFF_SESSION_KEY } from './staff-access-api'
const token = 'synthetic_limited_staff_session_000000000000000000'
const scope = {
  sessionId: 'session',
  codeId: 'code',
  organizationId: 'org',
  placementMonth: '2099-01-01',
  branchIds: ['branch'],
  deadlineAt: null,
  sessionExpiresAt: '2099-02-01T00:00:00Z',
  readOnly: false,
}
const workspace = {
  month: '2099-01-01',
  organization: { id: 'org', name: 'Демо-сеть' },
  deadlineAt: null,
  readOnly: false,
  branches: [
    {
      id: 'branch',
      code: '1',
      name: 'Демо-аптека',
      city: 'Город',
      address: 'Адрес',
      requiredCount: 1,
      photoCount: 0,
      visitId: null,
      status: null,
      reason: null,
      submittedAt: null,
      canStart: true,
    },
  ],
  tasks: [],
  limit: 50,
  offset: 0,
  hasMore: false,
}
const reply = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  clearStaffEntry()
  window.history.replaceState({}, '', '/staff')
  captureStaffEntryNavigation()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
it('one-time code opens a locked-month workspace and does not call learner/admin/task queue APIs', async () => {
  const fetcher = vi.fn(async (path: string) =>
    path.endsWith('/code')
      ? reply({ token, expiresAt: scope.sessionExpiresAt, scope })
      : reply(workspace),
  )
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await screen.findByLabelText('Код доступа')
  fireEvent.change(screen.getByLabelText('Код доступа'), { target: { value: '123456' } })
  await user.click(screen.getByRole('button', { name: 'Войти по коду' }))
  expect(await screen.findByText('Демо-сеть')).toBeInTheDocument()
  expect(screen.getByText(/Месяц доступа: 2099-01/)).toBeInTheDocument()
  expect(screen.queryByLabelText('Месяц визита')).not.toBeInTheDocument()
  expect(fetcher.mock.calls.every(([path]) => path.startsWith('/api/staff/'))).toBe(true)
  expect(fetcher.mock.calls[0][1]).toEqual(
    expect.objectContaining({ body: JSON.stringify({ code: '123456' }) }),
  )
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).toBe(token)
})
it('reload validates the persisted session; deadline readonly hides writers but retains report history', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  const fetcher = vi.fn(async (path: string) =>
    path.endsWith('/me')
      ? reply({ ...scope, readOnly: true })
      : reply({
          ...workspace,
          readOnly: true,
          branches: [
            {
              ...workspace.branches[0],
              visitId: 'report',
              status: 'submitted',
              photoCount: 1,
              canStart: false,
            },
          ],
        }),
  )
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  expect(await screen.findByText(/Срок сдачи истёк/)).toBeInTheDocument()
  expect(await screen.findByRole('button', { name: 'Открыть отчёт' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Начать фотоотчёт' })).not.toBeInTheDocument()
  expect(fetcher.mock.calls[0][0]).toBe('/api/staff/auth/me')
})
it('revoked bootstrap shows a closed notice and never loads the old workspace', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  const fetcher = vi.fn().mockResolvedValue(reply({}, 401))
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(/Доступ завершён|Сеанс завершён/)
  expect(fetcher).toHaveBeenCalledOnce()
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).toBeNull()
})
it('a stale form denied at the deadline becomes readonly and retains the consumed-code session', async () => {
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  let deadlinePassed = false
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith('/me')) return reply({ ...scope, readOnly: deadlinePassed })
      if (path.includes('/workspace?'))
        return reply({
          ...workspace,
          readOnly: deadlinePassed,
          branches: [{ ...workspace.branches[0], canStart: !deadlinePassed }],
        })
      if (path === '/api/staff/visits' && init?.method === 'POST') {
        deadlinePassed = true
        return reply({}, 403)
      }
      throw new Error('Unexpected synthetic route')
    }),
  )
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Начать фотоотчёт' }))
  await user.type(screen.getByLabelText('Имя автора фотоотчёта'), 'Synthetic author')
  await user.click(screen.getByRole('button', { name: 'Открыть и продолжить' }))
  expect(await screen.findByText(/Срок сдачи истёк/)).toBeInTheDocument()
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).toBe(token)
  expect(screen.queryByRole('button', { name: 'Начать фотоотчёт' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText('Код доступа')).not.toBeInTheDocument()
})
it('the known session expiry clears this realm even when subsequent validation cannot reach the server', async () => {
  vi.useFakeTimers()
  sessionStorage.setItem(STAFF_SESSION_KEY, token)
  sessionStorage.setItem('unrelated', 'keep')
  const expiresAt = new Date(Date.now() + 61_000).toISOString()
  const fetcher = vi.fn(async (path: string) =>
    path.endsWith('/me') ? reply({ ...scope, sessionExpiresAt: expiresAt }) : reply(workspace),
  )
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  await act(() => vi.advanceTimersByTimeAsync(0))
  expect(screen.getByText('Демо-сеть')).toBeInTheDocument()
  fetcher.mockRejectedValue(new Error('network unavailable'))
  await act(() => vi.advanceTimersByTimeAsync(60_000))
  expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/me'))).toHaveLength(2)
  await act(() => vi.advanceTimersByTimeAsync(1_001))
  expect(screen.getByRole('alert')).toHaveTextContent('Срок сеанса истёк')
  expect(screen.queryByText('Демо-сеть')).not.toBeInTheDocument()
  expect(sessionStorage.getItem(STAFF_SESSION_KEY)).toBeNull()
  expect(sessionStorage.getItem('unrelated')).toBe('keep')
})
it('old drained claim is an explicit expired notice, with no redemption or automatic session request', async () => {
  window.history.replaceState(
    {},
    '',
    '/staff#claim=synthetic_claim_000000&grant=synthetic_grant_0000000000000000000000000',
  )
  captureStaffEntryNavigation()
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  render(
    <MemoryRouter>
      <StaffAccessPage />
    </MemoryRouter>,
  )
  expect(await screen.findByText('Подтверждение задания устарело')).toBeInTheDocument()
  await waitFor(() => expect(fetcher).not.toHaveBeenCalled())
  expect(window.location.hash).toBe('')
})
