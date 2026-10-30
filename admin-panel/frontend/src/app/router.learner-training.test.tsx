import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { AppRouter } from './router'
import { useUiStore } from './store'

const tokens = { accessToken: 'training-route-access', refreshToken: 'training-route-refresh' }
const makeAssignment = (
  id: string,
  programName: string,
  status: string,
  dueAt: string,
  progressPct = 0,
) => ({
  id,
  programName,
  status,
  dueAt,
  progressPct,
  priority: 'normal',
  format: 'online',
  pharmacyName: 'Тестовая аптека',
  city: 'Алматы',
  stages: [],
})
const current = makeAssignment(
  'current',
  'Первый текущий курс',
  'in_progress',
  '2026-11-02T10:00:00Z',
  30,
)
const later = makeAssignment(
  'later',
  'Другой текущий курс',
  'in_progress',
  '2026-11-10T10:00:00Z',
  10,
)
const assigned = makeAssignment(
  'assigned',
  'Назначенный курс',
  'not_started',
  '2026-10-30T10:00:00Z',
)
const completed = makeAssignment(
  'completed',
  'Завершённый доступный курс',
  'completed',
  '2026-10-01T10:00:00Z',
  100,
)
const cancelled = makeAssignment(
  'cancelled',
  'Отменённый курс',
  'cancelled',
  '2026-10-01T10:00:00Z',
)
let assignments: ReturnType<typeof makeAssignment>[]
let unexpected: string[]

function CurrentPath() {
  return <output data-testid="training-path">{useLocation().pathname}</output>
}

function renderRouter(path = '/learn') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRouter />
      <CurrentPath />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
  useUiStore.setState({ authedUser: null, tokens: null, language: 'ru' })
  assignments = [assigned, later, completed, cancelled, current]
  unexpected = []
  vi.stubGlobal('scrollTo', vi.fn())
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const path = String(input)
    const authorization = new Headers(init?.headers).get('Authorization')
    const body =
      path === '/api/mobile/auth/me'
        ? {
            id: 'training-pharmacist',
            name: 'Фармацевт',
            phone: '+77000000000',
            pharmacyName: 'Тестовая аптека',
            city: 'Алматы',
            status: 'active',
          }
        : path === '/api/mobile/training'
          ? {
              total: assignments.length,
              inProgress: 2,
              completed: 1,
              overdue: 0,
              assignments,
              certificates: [],
              notifications: [],
            }
          : path === '/api/mobile/me/balance'
            ? { balanceKzt: 750, currency: 'KZT' }
            : path.startsWith('/api/mobile/me/recommendations?')
              ? { page: 0, size: 20, total: 0, currency: 'KZT', items: [] }
              : undefined
    if (
      authorization !== `Bearer ${tokens.accessToken}` ||
      body === undefined ||
      (init?.method && init.method !== 'GET')
    ) {
      unexpected.push(`${init?.method ?? 'GET'} ${path}`)
      throw new Error(`Unexpected learner request: ${path}`)
    }
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
})

afterEach(() => {
  expect(unexpected).toEqual([])
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  document.body.classList.remove('learner-portal')
})

describe('real learner training navigation and payment placeholder', () => {
  it('does not restore a delayed course detail after navigation back to the separate training route', async () => {
    let resolveDetail!: (response: Response) => void
    const detail = new Promise<Response>((resolve) => {
      resolveDetail = resolve
    })
    const previous = vi.mocked(globalThis.fetch).getMockImplementation()!
    vi.mocked(globalThis.fetch).mockImplementation((input, init) =>
      String(input) === '/api/mobile/training/assignments/current' ? detail : previous(input, init),
    )
    renderRouter('/learn/course/current')
    await screen.findByRole('button', { name: 'Обучение', exact: true })
    await waitFor(() =>
      expect(
        vi
          .mocked(globalThis.fetch)
          .mock.calls.some(
            ([input]) => String(input) === '/api/mobile/training/assignments/current',
          ),
      ).toBe(true),
    )
    await userEvent.setup().click(screen.getByRole('button', { name: 'Обучение', exact: true }))
    const assignmentCall = vi
      .mocked(globalThis.fetch)
      .mock.calls.find(([input]) => String(input) === '/api/mobile/training/assignments/current')!
    expect(assignmentCall[1]?.signal?.aborted).toBe(true)
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    await act(async () => {
      resolveDetail(
        new Response(JSON.stringify(current), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      await detail
    })
    expect(screen.getByTestId('training-path')).toHaveTextContent('/learn/training')
    expect(
      screen.getByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
  })
  it('shows one ongoing course at home and separates active courses from history through the sidebar', async () => {
    renderRouter()
    expect(
      await screen.findByRole('heading', { name: 'Текущий курс', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: current.programName, exact: true }),
    ).toBeInTheDocument()
    for (const assignment of [assigned, later, completed, cancelled]) {
      expect(
        screen.queryByRole('heading', { name: assignment.programName, exact: true }),
      ).not.toBeInTheDocument()
    }
    await userEvent.setup().click(screen.getByRole('button', { name: 'Обучение', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('training-path')).toHaveTextContent('/learn/training')
    for (const assignment of [current, later, assigned]) {
      expect(
        screen.getByRole('heading', { name: assignment.programName, exact: true }),
      ).toBeInTheDocument()
    }
    expect(
      screen.queryByRole('heading', { name: cancelled.programName, exact: true }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'История обучения 1', exact: true }))
    expect(screen.getByRole('button', { name: 'История обучения 1', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(
      screen.getByRole('heading', { name: completed.programName, exact: true }),
    ).toBeInTheDocument()
    for (const assignment of [current, later, assigned, cancelled]) {
      expect(
        screen.queryByRole('heading', { name: assignment.programName, exact: true }),
      ).not.toBeInTheDocument()
    }
    await userEvent.click(screen.getByRole('button', { name: 'Мои курсы 3', exact: true }))
    expect(screen.getByRole('button', { name: 'Мои курсы 3', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    for (const assignment of [current, later, assigned]) {
      expect(
        screen.getByRole('heading', { name: assignment.programName, exact: true }),
      ).toBeInTheDocument()
    }
    expect(screen.queryByRole('textbox', { name: 'Email', exact: true })).not.toBeInTheDocument()
    expect(useUiStore.getState().authedUser).toBeNull()
  })

  it('opens active courses directly and lets history reopen after reload with a persisted pharmacist session', async () => {
    const router = renderRouter('/learn/training')
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('training-path')).toHaveTextContent('/learn/training')
    expect(screen.getByRole('button', { name: 'Обучение', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(
      screen.getByRole('heading', { name: current.programName, exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'История обучения 1', exact: true }))
    expect(
      screen.getByRole('heading', { name: completed.programName, exact: true }),
    ).toBeInTheDocument()
    router.unmount()
    renderRouter('/learn/training')
    expect(
      await screen.findByRole('heading', { name: current.programName, exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Мои курсы 3', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'История обучения 1', exact: true }))
    expect(
      screen.getByRole('heading', { name: completed.programName, exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: cancelled.programName, exact: true }),
    ).not.toBeInTheDocument()
  })

  it('does not replace the current course with paused or future training and keeps their details accessible', async () => {
    const paused = {
      ...makeAssignment(
        'paused',
        'Приостановленный важный курс',
        'paused',
        '2026-10-30T10:00:00Z',
        80,
      ),
      priority: 'critical',
      startedAt: '2026-10-01T10:00:00Z',
    }
    const scheduled = {
      ...makeAssignment(
        'scheduled',
        'Запланированный важный курс',
        'scheduled',
        '2099-12-31T10:00:00Z',
        70,
      ),
      priority: 'critical',
      startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    }
    const futureStarted = {
      ...makeAssignment(
        'future-started',
        'Курс с будущей датой начала',
        'in_progress',
        '2099-12-31T10:00:00Z',
        90,
      ),
      priority: 'critical',
      startsAt: new Date(Date.now() + 86_400_000).toISOString(),
    }
    assignments = [paused, scheduled, futureStarted, current]
    renderRouter()
    expect(
      await screen.findByRole('heading', { name: 'Текущий курс', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: current.programName, exact: true }),
    ).toBeInTheDocument()
    for (const assignment of [paused, scheduled, futureStarted]) {
      expect(
        screen.queryByRole('heading', { name: assignment.programName, exact: true }),
      ).not.toBeInTheDocument()
    }
    await userEvent.setup().click(screen.getByRole('button', { name: 'Обучение', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    for (const [assignment, status] of [
      [paused, 'Приостановлено'],
      [scheduled, 'Запланировано'],
      [futureStarted, 'В процессе'],
    ] as const) {
      const card = screen
        .getByRole('heading', { name: assignment.programName, exact: true })
        .closest('article')
      expect(card).not.toBeNull()
      expect(within(card!).getByText(status, { exact: true })).toBeInTheDocument()
      expect(
        within(card!).getByRole('button', { name: 'Открыть курс', exact: true }),
      ).toBeInTheDocument()
      expect(
        within(card!).queryByRole('button', { name: 'Начать курс', exact: true }),
      ).not.toBeInTheDocument()
      expect(
        within(card!).queryByRole('button', { name: 'Продолжить обучение', exact: true }),
      ).not.toBeInTheDocument()
    }
  })

  it('offers a scheduled course after its start date but not a scheduled course without a date', async () => {
    const elapsedScheduled = {
      ...makeAssignment(
        'elapsed-scheduled',
        'Курс уже доступен по дате',
        'scheduled',
        '2099-12-31T10:00:00Z',
      ),
      startsAt: new Date(Date.now() - 86_400_000).toISOString(),
    }
    const undatedScheduled = {
      ...makeAssignment(
        'undated-scheduled',
        'Курс пока без даты старта',
        'scheduled',
        '2026-10-30T10:00:00Z',
        80,
      ),
      priority: 'critical',
    }
    assignments = [undatedScheduled, completed, cancelled, elapsedScheduled]
    renderRouter()
    expect(
      await screen.findByRole('heading', { name: 'Текущий курс', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: elapsedScheduled.programName, exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Начать курс', exact: true })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: undatedScheduled.programName, exact: true }),
    ).not.toBeInTheDocument()
  })

  it('links to all courses from home without presenting a completed course as current', async () => {
    assignments = [completed, cancelled]
    renderRouter()
    expect(
      await screen.findByRole('heading', { name: 'Текущий курс', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Все курсы', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByText('Новых курсов пока нет')).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: completed.programName, exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'История обучения 1', exact: true }))
    expect(
      screen.getByRole('heading', { name: completed.programName, exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Повторить материалы', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: cancelled.programName, exact: true }),
    ).not.toBeInTheDocument()
  })

  it('keeps an empty all-courses page usable without manufacturing course data', async () => {
    assignments = []
    renderRouter('/learn/training')
    expect(
      await screen.findByRole('heading', { name: 'Все доступные курсы', exact: true }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Начать курс', exact: true }),
    ).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Главная', exact: true }))
    expect(
      await screen.findByRole('heading', { name: 'Текущий курс', exact: true }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('training-path')).toHaveTextContent('/learn')
  })

  it('explains the card placeholder without accepting, sending or persisting payment details', async () => {
    renderRouter('/learn/profile')
    const card = await screen.findByRole('region', { name: 'Банковская карта', exact: true })
    expect(within(card).getByText('Не подключена', { exact: true })).toBeInTheDocument()
    await screen.findByText('750 ₸')
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledTimes(4))
    const requestCount = vi.mocked(globalThis.fetch).mock.calls.length
    const storageWrites = vi.spyOn(Storage.prototype, 'setItem')
    const user = userEvent.setup()
    await user.click(
      within(card).getByRole('button', { name: 'Как подключить карту', exact: true }),
    )
    expect(within(card).getByRole('status')).toHaveTextContent(
      'Привязка карты появится позже. Сейчас реквизиты не запрашиваются, выплаты на карту недоступны.',
    )
    expect(document.querySelector('input, textarea, select, iframe')).toBeNull()
    expect(screen.queryByText('Карта привязана', { exact: true })).not.toBeInTheDocument()
    expect(globalThis.fetch).toHaveBeenCalledTimes(requestCount)
    expect(storageWrites).not.toHaveBeenCalled()
    await user.click(within(card).getByRole('button', { name: 'Скрыть информацию', exact: true }))
    expect(within(card).queryByRole('status')).not.toBeInTheDocument()
    expect(within(card).getByText('Не подключена', { exact: true })).toBeInTheDocument()
    expect(globalThis.fetch).toHaveBeenCalledTimes(requestCount)
    expect(storageWrites).not.toHaveBeenCalled()
  })
})
