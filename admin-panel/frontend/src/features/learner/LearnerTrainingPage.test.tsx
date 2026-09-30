import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import LearnerTrainingPage from './LearnerTrainingPage'
import { useUiStore } from '@/app/store'

const tokens = { accessToken: 'access-token', refreshToken: 'refresh-token' }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderPortal(path = '/learn') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/learn" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId/lesson/:lessonId" element={<LearnerTrainingPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.removeItem('epharm.lang')
  useUiStore.setState({ language: 'ru' })
  vi.restoreAllMocks()
})

afterEach(() => {
  document.body.classList.remove('learner-portal')
  vi.unstubAllGlobals()
})

describe('learner training portal', () => {
  it('switches the pharmacist interface to Kazakh and remembers the language', async () => {
    const user = userEvent.setup()
    renderPortal()

    await user.click(screen.getByRole('button', { name: 'Қаз' }))

    expect(screen.getByRole('heading', { name: 'Фармацевтке кіру' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'ЖСН' })).toBeInTheDocument()
    expect(localStorage.getItem('epharm.lang')).toBe('kk')
    expect(document.documentElement.lang).toBe('kk')
  })

  it('enters the pharmacist portal with IIN and password', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: true, phoneMasked: '+7 (***) ***-00-00' })
      }
      if (path === '/api/mobile/auth/login') {
        return json({
          tokens,
          pharmacist: { id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    const iinInput = screen.getByRole('textbox', { name: 'ИИН' })
    await user.type(iinInput, '000101500011')
    expect(iinInput).toHaveValue('000101 500011')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    await user.type(screen.getByLabelText('Пароль'), 'Farm123!')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/auth/login',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ iin: '000101500011', password: 'Farm123!' }),
        }),
      )
    })
    expect(await screen.findByRole('heading', { name: 'Здравствуйте, Айжан' })).toBeInTheDocument()
  })

  it('lets a pharmacist create a password on the first login after SMS verification', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: false, phoneMasked: '+7 (700) ***-**-12' })
      }
      if (path === '/api/mobile/auth/activation/sms/request') {
        return json({ sent: true, phoneMasked: '+7 (700) ***-**-12', ttlSeconds: 300 })
      }
      if (path === '/api/mobile/auth/activation/sms/verify') {
        return json({ verified: true, phoneMasked: '+7 (700) ***-**-12' })
      }
      if (path === '/api/mobile/auth/activation/password') {
        return json({
          tokens,
          pharmacist: { id: 'ph-1', name: 'Василий', phone: '+77000000012', pharmacyName: 'Аптека', city: 'Рудный' },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Василий', phone: '+77000000012', pharmacyName: 'Аптека', city: 'Рудный' })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), '000101500011')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    const phoneInput = await screen.findByRole('textbox', { name: 'Номер телефона' })
    await user.type(phoneInput, '7000000012')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '5445')
    await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
    await user.type(await screen.findByLabelText('Новый пароль'), 'NewFarm123!')
    await user.type(screen.getByLabelText('Повторите пароль'), 'NewFarm123!')
    await user.click(screen.getByRole('button', { name: 'Создать пароль и войти' }))

    expect(await screen.findByRole('heading', { name: 'Здравствуйте, Василий' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/activation/password',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ iin: '000101500011', phone: '+77000000012', password: 'NewFarm123!' }),
      }),
    )
  })

  it('saves the final lesson through the server progress endpoint', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 50,
      startedAt: '2026-09-01T10:00:00Z',
      stages: [
        {
          id: 'stage-1',
          title: 'Основы',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 50,
          course: {
            id: 'course-1',
            title: 'Основы',
            lessons: [
              {
                id: 'lesson-1',
                title: 'Первый',
                order: 0,
                progressPct: 100,
                completedAt: '2026-09-01T10:05:00Z',
                attachments: [],
              },
              { id: 'lesson-2', title: 'Второй', order: 1, progressPct: 0, attachments: [] },
            ],
          },
        },
      ],
    }
    const completedAssignment = {
      ...assignment,
      progressPct: 100,
      stages: [{
        ...assignment.stages[0],
        status: 'completed',
        progressPct: 100,
        course: {
          ...assignment.stages[0].course,
          lessons: [
            assignment.stages[0].course.lessons[0],
            {
              ...assignment.stages[0].course.lessons[1],
              progressPct: 100,
              completedAt: '2026-09-01T10:10:00Z',
            },
          ],
        },
      }],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training' && (!init?.method || init.method === 'GET')) {
        return json({ total: 1, inProgress: 1, completed: 0, overdue: 0, assignments: [assignment] })
      }
      if (path.endsWith('/stages/stage-1/lessons/lesson-2') && init?.method === 'PATCH') {
        return json(completedAssignment)
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1/lesson/lesson-2')
    expect(await screen.findByRole('heading', { name: 'Второй' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Завершить курс' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/stages/stage-1/lessons/lesson-2',
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ progressPct: 100, positionSeconds: 0 }),
        }),
      )
    })
  })

  it('submits a scored lesson test and completes it only after a passing result', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const quizLesson = {
      id: 'quiz-1',
      title: 'Итоговый тест',
      kind: 'quiz',
      order: 0,
      required: true,
      progressPct: 0,
      quizPassingScore: 80,
      quizQuestions: [
        { id: 'q-1', prompt: 'Когда принимать препарат?', options: ['До еды', 'После еды'] },
        { id: 'q-2', prompt: 'Сколько таблеток?', options: ['Одну', 'Две'] },
      ],
      attachments: [],
    }
    const assignment = {
      id: 'assignment-quiz', programName: 'Тестирование', pharmacyName: 'Ауэзова 134', city: 'Алматы',
      status: 'in_progress', format: 'online', progressPct: 0, startedAt: '2026-09-01T10:00:00Z',
      stages: [{
        id: 'stage-quiz', title: 'Проверка знаний', type: 'online_course', status: 'in_progress', progressPct: 0,
        course: { id: 'course-quiz', title: 'Проверка знаний', lessons: [quizLesson] },
      }],
    }
    const completedAssignment = {
      ...assignment,
      progressPct: 100,
      stages: [{
        ...assignment.stages[0],
        status: 'completed',
        progressPct: 100,
        course: {
          ...assignment.stages[0].course,
          lessons: [{ ...quizLesson, progressPct: 100, quizScore: 100, quizAttempts: 1, completedAt: '2026-09-01T10:10:00Z' }],
        },
      }],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training/assignments/assignment-quiz') return json(assignment)
      if (path.endsWith('/stages/stage-quiz/lessons/quiz-1/quiz') && init?.method === 'POST') {
        return json({
          score: 100,
          passed: true,
          correctAnswers: 2,
          totalQuestions: 2,
          attempt: 1,
          questions: [
            { questionId: 'q-1', correct: true, correctOption: 0, explanation: '' },
            { questionId: 'q-2', correct: true, correctOption: 1, explanation: '' },
          ],
          assignment: completedAssignment,
        })
      }
      if (path === '/api/mobile/training') {
        return json({ total: 1, inProgress: 0, completed: 1, overdue: 0, assignments: [completedAssignment] })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-quiz/lesson/quiz-1')
    expect(await screen.findByRole('heading', { name: 'Итоговый тест' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: 'До еды' }))
    await userEvent.click(screen.getByRole('radio', { name: 'Две' }))
    await userEvent.click(screen.getByRole('button', { name: 'Завершить тест' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-quiz/stages/stage-quiz/lessons/quiz-1/quiz',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ answers: { 'q-1': 0, 'q-2': 1 } }),
        }),
      )
    })
    expect(await screen.findByText('Тест пройден')).toBeInTheDocument()
    expect(screen.getByText(/Результат: 100%/)).toBeInTheDocument()
  })

  it('keeps all lessons of a completed legacy course accessible without lesson progress rows', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'completed',
      format: 'online',
      progressPct: 100,
      stages: [{
        id: 'stage-1',
        title: 'Основы',
        type: 'online_course',
        status: 'completed',
        progressPct: 100,
        course: {
          id: 'course-1',
          title: 'Основы',
          lessons: [
            { id: 'lesson-1', title: 'Первый', order: 0, attachments: [] },
            { id: 'lesson-2', title: 'Второй', order: 1, attachments: [] },
          ],
        },
      }],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training') {
        return json({ total: 1, inProgress: 0, completed: 1, overdue: 0, assignments: [assignment] })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    const secondLesson = await screen.findByRole('button', { name: /Второй/ })
    expect(secondLesson).toBeEnabled()
    await userEvent.click(secondLesson)
    expect(await screen.findByRole('heading', { name: 'Второй' })).toBeInTheDocument()
    expect(screen.queryByText('Урок пока недоступен')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.every(([, init]) => init?.method !== 'PATCH')).toBe(true)
  })

  it('lets a content-URL-only online course complete through the stage endpoint', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-1',
      programName: 'Безопасная работа',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'waiting_online',
      format: 'online',
      progressPct: 0,
      startedAt: '2026-09-01T10:00:00Z',
      stages: [{
        id: 'stage-1',
        title: 'Внешний онлайн-курс',
        type: 'online_course',
        status: 'available',
        progressPct: 0,
        contentUrl: 'https://example.org/course',
        course: null,
      }],
    }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training/assignments/assignment-1') return json(assignment)
      if (path === '/api/mobile/training') {
        return json({ total: 1, inProgress: 1, completed: 0, overdue: 0, assignments: [assignment] })
      }
      if (path === '/api/mobile/training/assignments/assignment-1/stages/stage-1' && init?.method === 'PATCH') {
        return json({ ...assignment, stages: [{ ...assignment.stages[0], status: 'completed', progressPct: 100 }] })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    expect(await screen.findByRole('link', { name: 'Открыть материал' })).toHaveAttribute('href', 'https://example.org/course')
    await userEvent.click(screen.getByRole('button', { name: 'Завершить этап' }))
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/stages/stage-1',
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ progressPct: 100 }),
        }),
      )
    })
  })
  it('shows notifications, events, filters, certificates and the QR attendance flow from the app', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const active = {
      id: 'assignment-active',
      programName: 'Активный курс',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'hybrid',
      progressPct: 40,
      startedAt: '2026-09-23T10:00:00Z',
      stages: [],
    }
    const completed = {
      ...active,
      id: 'assignment-completed',
      programName: 'Завершённый курс',
      status: 'completed',
      progressPct: 100,
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы', balance: 1500 })
      }
      if (path === '/api/mobile/training') {
        return json({
          total: 2,
          inProgress: 1,
          completed: 1,
          overdue: 0,
          defaultFormat: 'hybrid',
          assignments: [active, completed],
          upcomingEvents: [{ id: 'event-1', title: 'Практикум', startsAt: '2026-09-24T11:00:00Z', city: 'Алматы', address: 'Учебный центр', capacity: 20, occupied: 12, status: 'scheduled' }],
          notifications: [{ id: 'notification-1', eventType: 'reminder', title: 'Напоминание', message: 'Курс ждёт завершения', read: false, scheduledAt: '2026-09-23T11:00:00Z' }],
          certificates: [{ id: 'certificate-1', number: 'EPH-1', assignmentId: 'assignment-completed', programName: 'Завершённый курс', format: 'online', issuedAt: '2026-09-23T11:00:00Z', status: 'valid', pdfUrl: '/certificate.pdf' }],
        })
      }
      if (path === '/api/mobile/promotions') {
        return json([{
          id: 'promo-1',
          productId: 'product-1',
          name: 'Ренни апельсин жев таб №24',
          brand: 'Bayer',
          mnn: 'Кальция карбонат + Магния карбонат',
          rxOtc: 'OTC',
          category: 'Лекарственное средство',
          barcode: '4250369504480',
          tiers: [{ minQty: 1, price: 1740, bonus: 100 }],
        }])
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Уведомления' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ближайшие события' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Препараты и бонусы' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Сертификаты' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Активный курс/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Завершённый курс/ })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Завершённые' }))
    expect(screen.getByRole('button', { name: /Завершённый курс/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Отметить посещение по QR' }))
    expect(screen.getByRole('dialog', { name: 'Отметить посещение' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Сканировать QR камерой' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Браузер не поддерживает доступ к камере')

    await userEvent.click(screen.getByRole('button', { name: 'Отмена' }))
    await userEvent.click(screen.getByRole('button', { name: 'Открыть препарат Ренни апельсин жев таб №24' }))
    expect(screen.getByRole('dialog', { name: 'Ренни апельсин жев таб №24' })).toBeInTheDocument()
    expect(screen.getAllByText('Бонус 100 ₸')).toHaveLength(2)
  })

  it('selects an offline event through the same mobile training endpoint', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const trainingEvent = {
      id: 'event-1', title: 'Практикум', startsAt: '2026-09-24T11:00:00Z',
      city: 'Алматы', address: 'Учебный центр', capacity: 20, occupied: 12, status: 'scheduled',
    }
    const assignment = {
      id: 'assignment-1', programName: 'Гибридный курс', pharmacyName: 'Ауэзова 134', city: 'Алматы',
      status: 'waiting_event_selection', format: 'hybrid', progressPct: 0, startedAt: '2026-09-23T10:00:00Z', event: null, stages: [],
    }
    const selectedAssignment = { ...assignment, status: 'in_progress', event: trainingEvent }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      if (path === '/api/mobile/training/assignments/assignment-1' && (!init?.method || init.method === 'GET')) return json(assignment)
      if (path === '/api/mobile/training/assignments/assignment-1/events' && (!init?.method || init.method === 'GET')) return json([trainingEvent])
      if (path === '/api/mobile/training/assignments/assignment-1/events/event-1' && init?.method === 'POST') return json(selectedAssignment)
      if (path === '/api/mobile/training') return json({ total: 1, inProgress: 1, completed: 0, overdue: 0, assignments: [selectedAssignment] })
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-1')
    await userEvent.click(await screen.findByRole('button', { name: 'Выбрать мероприятие' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/training/assignments/assignment-1/events/event-1',
        expect.objectContaining({ method: 'POST' }),
      )
    })
    expect(await screen.findByText('Отметить посещение по QR')).toBeInTheDocument()
  })

  it('refreshes an expired access token once for concurrent portal requests', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const refreshedTokens = { accessToken: 'fresh-access-token', refreshToken: 'fresh-refresh-token' }
    let refreshCalls = 0
    const protectedCalls: Array<{ path: string; authorization: string | null }> = []
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const path = String(input)
      const authorization = new Headers(init?.headers).get('Authorization')

      if (path === '/api/mobile/auth/refresh') {
        refreshCalls += 1
        expect(init?.method).toBe('POST')
        expect(JSON.parse(String(init?.body))).toEqual({ refreshToken: tokens.refreshToken })
        return json({ tokens: refreshedTokens })
      }

      if (path === '/api/mobile/auth/me' || path === '/api/mobile/training') {
        protectedCalls.push({ path, authorization })
        if (authorization === `Bearer ${tokens.accessToken}`) {
          return json({ message: 'Токен истёк' }, 401)
        }
        expect(authorization).toBe(`Bearer ${refreshedTokens.accessToken}`)
        if (path === '/api/mobile/auth/me') {
          return json({
            id: 'ph-1',
            name: 'Айжан',
            phone: '+77070000000',
            pharmacyName: 'Ауэзова 134',
            city: 'Алматы',
          })
        }
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      }

      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Здравствуйте, Айжан' })).toBeInTheDocument()
    expect(refreshCalls).toBe(1)
    expect(JSON.parse(sessionStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(refreshedTokens)
    expect(protectedCalls).toEqual(expect.arrayContaining([
      { path: '/api/mobile/auth/me', authorization: `Bearer ${tokens.accessToken}` },
      { path: '/api/mobile/training', authorization: `Bearer ${tokens.accessToken}` },
      { path: '/api/mobile/auth/me', authorization: `Bearer ${refreshedTokens.accessToken}` },
      { path: '/api/mobile/training', authorization: `Bearer ${refreshedTokens.accessToken}` },
    ]))
    expect(fetchMock).toHaveBeenCalledTimes(6)
  })
})
