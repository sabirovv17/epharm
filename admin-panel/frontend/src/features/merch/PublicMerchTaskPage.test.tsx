import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import PublicMerchTaskPage from './PublicMerchTaskPage'
import { captureTaskNavigation } from './taskNavigation'
const token = 'synthetic_task_capability_000000000000000000000000'
const metadata = {
  dispatchId: 'dispatch-demo',
  branchId: 'branch-demo',
  expiresAt: '2099-01-01T00:00:00Z',
  readOnly: false,
  rewardAmountKzt: 0,
  identityRequired: false,
}
const json = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', `/tasks/staff?task=1#task=${token}`)
  captureTaskNavigation()
})
afterEach(() => vi.unstubAllGlobals())
describe('public free-task boundary', () => {
  it('fetches free task data only with its header capability, without admin/mobile credentials or token persistence', async () => {
    localStorage.setItem('epharm.auth.tokens', 'unchanged-admin')
    localStorage.setItem('epharm.learner.tokens', 'unchanged-learner')
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (path) =>
      String(path).endsWith('/session')
        ? json(metadata)
        : json({
            id: metadata.dispatchId,
            branchId: metadata.branchId,
            title: 'Демо-задание без оплаты',
            status: 'ready',
            dueAt: null,
            sourceSystem: 'EPHARM',
            version: 1,
            rewardAmountKzt: 0,
            reward: null,
            items: [],
            itemCount: 0,
            detailsIncluded: true,
            priority: 'normal',
            taskType: 'photo_report',
          }),
    )
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Демо-задание без оплаты')).toBeInTheDocument()
    expect(screen.getByText('Загрузка фото сама по себе не отправляет отчёт.')).not.toBeVisible()
    await userEvent.click(screen.getByText('Как выполнить задание', { selector: 'summary' }))
    expect(screen.getByRole('list', { name: 'Как выполнить задание' })).toBeVisible()
    expect(screen.getByText('Загрузка фото сама по себе не отправляет отчёт.')).toBeVisible()
    expect(fetcher).toHaveBeenCalledTimes(2)
    for (const [path, init] of fetcher.mock.calls) {
      expect(String(path)).toMatch(/^\/api\/public\/merch\/tasks/)
      expect(String(path)).not.toContain(token)
      expect(new Headers(init?.headers).get('X-Merch-Task-Token')).toBe(token)
      expect(new Headers(init?.headers).get('Authorization')).toBeNull()
      expect(init?.credentials).toBe('omit')
      expect(init?.referrerPolicy).toBe('no-referrer')
    }
    expect(localStorage.getItem('epharm.auth.tokens')).toBe('unchanged-admin')
    expect(localStorage.getItem('epharm.learner.tokens')).toBe('unchanged-learner')
    expect(
      JSON.stringify([...Object.values(localStorage), ...Object.values(sessionStorage)]),
    ).not.toContain(token)
  })
  it('paid metadata requires personal login and never fetches the full public task', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ ...metadata, rewardAmountKzt: 400, identityRequired: true }))
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(await screen.findByRole('link', { name: 'Войти и открыть задание' })).toHaveAttribute(
      'href',
      '/learn/merch/tasks/dispatch-demo',
    )
    expect(screen.getByRole('heading', { name: 'Задание с вознаграждением' })).toBeInTheDocument()
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1))
    expect(fetcher.mock.calls[0][0]).toBe('/api/public/merch/tasks/session')
  })
  it('a zero reward monthly report requests its existing personal login without claiming a reward or opening an anonymous form', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(json({ ...metadata, identityRequired: true }))
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(
      await screen.findByRole('heading', { name: 'Подтвердите исполнителя' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Задание с вознаграждением')).not.toBeInTheDocument()
    expect(
      screen.queryByText(
        'Задание без вознаграждения. Личный вход для его выполнения не требуется.',
      ),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Войти и открыть задание' })).toHaveAttribute(
      'href',
      '/learn/merch/tasks/dispatch-demo',
    )
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1))
    expect(fetcher.mock.calls[0][0]).toBe('/api/public/merch/tasks/session')
  })
  it('a query-only token does not cause an API request or fall back to another realm', () => {
    window.history.replaceState({}, '', `/tasks/staff?task=${token}`)
    captureTaskNavigation()
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(screen.getByText('Откройте задание по QR-коду')).toBeInTheDocument()
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('shows explicit link verification while metadata is pending without fetching task detail', () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {}))
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Проверяем ссылку задания')
    expect(screen.queryByRole('list', { name: 'Как выполнить задание' })).not.toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('read-only free task retains details without presenting execution steps', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (path) =>
      String(path).endsWith('/session')
        ? json({ ...metadata, readOnly: true })
        : json({
            id: metadata.dispatchId,
            title: 'Архивный отчёт',
            status: 'completed',
            sourceSystem: 'EPHARM',
            version: 1,
            rewardAmountKzt: 0,
            reward: null,
            items: [],
            itemCount: 0,
            detailsIncluded: true,
            taskType: 'photo_report',
            priority: 'normal',
          }),
    )
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <PublicMerchTaskPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Только просмотр')).toBeVisible()
    expect(await screen.findByText('Архивный отчёт')).toBeVisible()
    expect(screen.queryByRole('list', { name: 'Как выполнить задание' })).not.toBeInTheDocument()
    expect(fetcher.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(
      true,
    )
  })
})
