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
        <Route path="/learn/training" element={<LearnerTrainingPage />} />
        <Route path="/learn/certificates" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId" element={<LearnerTrainingPage />} />
        <Route path="/learn/course/:assignmentId/lesson/:lessonId" element={<LearnerTrainingPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
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
    await user.type(iinInput, '061007500024')
    expect(iinInput).toHaveValue('061007 500024')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    await user.type(screen.getByLabelText('Пароль'), 'Farm123!')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/mobile/auth/login',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ iin: '061007500024', password: 'Farm123!' }),
        }),
      )
    })
    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(tokens)
  })

  it('migrates an existing tab session to persistent browser storage', async () => {
    sessionStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      }
      if (path === '/api/mobile/training') {
        return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(tokens)
    expect(sessionStorage.getItem('epharm.learner.tokens')).toBeNull()
  })

  it('shows only simple course cards on the pharmacist home page', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-live',
      programName: 'Современная фармация',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 25,
      stages: [{
        id: 'stage-live',
        title: 'Онлайн-модуль',
        type: 'online_course',
        status: 'in_progress',
        progressPct: 25,
        course: {
          id: 'course-live',
          title: 'Онлайн-модуль',
          lessons: [
            { id: 'lesson-video', title: 'Вводный видеоурок', kind: 'video', durationMin: 12, progressPct: 50, attachments: [] },
            { id: 'lesson-live', title: 'Конференция с экспертом', kind: 'live', liveStatus: 'scheduled', liveStartAt: '2026-10-20T09:00:00Z', progressPct: 0, attachments: [] },
          ],
        },
      }],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      if (path === '/api/mobile/training') return json({ total: 1, inProgress: 1, completed: 0, overdue: 0, assignments: [assignment] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/training')

    expect(await screen.findByRole('heading', { name: 'Обучение' })).toBeInTheDocument()
    expect(screen.getByText('Курсы к обучению')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Современная фармация' })).toBeInTheDocument()
    expect(screen.getByText('Что вы получите')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Начать курс' })).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Каталог' })).not.toBeInTheDocument()
  })

  it('loads personal balance and active recommendations with the pharmacist token', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') return json({ balanceKzt: 42000, currency: 'KZT' })
      if (path === '/api/mobile/me/recommendations?page=0&size=20') {
        return json({
          items: [{
            campaign: { id: 'promo-1', title: 'Пример кампании', dateStart: null, dateEnd: null },
            type: 'substitution',
            trigger: { kind: 'acc_group', value: 'group-1', label: 'Название группы' },
            triggerProducts: [],
            offers: [{
              ruleId: 'rule-1',
              product: {
                id: 'product-1',
                name: 'Товар-пример',
                brand: 'Производитель',
                mnn: 'МНН',
                volume: '20 таблеток',
                barcodes: ['0123456789012'],
                ipartId: null,
                catalogPriceKzt: 2500,
                catalogUpdatedAt: '2026-10-21T00:00:00Z',
              },
              bonusKzt: 500,
              script: 'Подсказка фармацевту',
              advantages: ['Преимущество'],
            }],
          }],
          page: 0,
          size: 20,
          total: 1,
          currency: 'KZT',
        })
      }
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('42 000 ₸')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Активные рекомендации' })).toBeInTheDocument()
    expect(screen.getByText('Пример кампании')).toBeInTheDocument()
    expect(screen.getByText(/Название группы/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Товар-пример' })).toBeInTheDocument()
    expect(screen.getByText('Бонус: 500 ₸')).toBeInTheDocument()

    const balanceCall = fetchMock.mock.calls.find(([input]) => String(input) === '/api/mobile/me/balance')
    const recommendationsCall = fetchMock.mock.calls.find(([input]) => String(input) === '/api/mobile/me/recommendations?page=0&size=20')
    expect(new Headers(balanceCall?.[1]?.headers).get('Authorization')).toBe(`Bearer ${tokens.accessToken}`)
    expect(new Headers(balanceCall?.[1]?.headers).get('Accept')).toBe('application/json')
    expect(balanceCall?.[1]?.cache).toBe('no-store')
    expect(new Headers(recommendationsCall?.[1]?.headers).get('Authorization')).toBe(`Bearer ${tokens.accessToken}`)
  })

  it('does not disguise a personal API error as a zero balance or empty feed', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') return json({ code: 'SERVICE_UNAVAILABLE', message: 'Ошибка' }, 503)
      if (path === '/api/mobile/me/recommendations?page=0&size=20') return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('Баланс временно недоступен')).toBeInTheDocument()
    expect(screen.getByText('Активных рекомендаций сейчас нет.')).toBeInTheDocument()
    expect(screen.queryByText('0 ₸')).not.toBeInTheDocument()
  })

  it('uses the compatible profile balance while the dedicated balance endpoint is unavailable', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-1',
          name: 'Айжан',
          phone: '+77070000000',
          pharmacyName: 'Ауэзова 134',
          city: 'Алматы',
          balance: 17_500,
        })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/me/balance') return json({ code: 'NOT_FOUND', message: 'Не найдено' }, 404)
      if (path === '/api/mobile/me/recommendations?page=0&size=20') return json({ items: [], page: 0, size: 20, total: 0, currency: 'KZT' })
      return json({ code: 'NOT_FOUND', message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal()

    expect(await screen.findByText('17 500 ₸')).toBeInTheDocument()
    expect(screen.queryByText('Баланс временно недоступен')).not.toBeInTheDocument()
  })

  it('shows the fire-safety video as the first lesson block', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
    const assignment = {
      id: 'assignment-fire',
      programName: 'Курс обучения «Пожарная безопасность»',
      pharmacyName: 'Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 0,
      stages: [{
        id: 'stage-fire',
        title: 'Пожарная безопасность',
        type: 'online_course',
        status: 'in_progress',
        progressPct: 0,
        course: {
          id: 'course-fire',
          title: 'Пожарная безопасность в аптеке',
          lessons: [{
            id: 'lesson-fire-1',
            title: 'Основы пожарной безопасности',
            description: 'Вводный урок',
            content: 'Текст учебного материала',
            kind: 'text',
            order: 0,
            durationMin: 5,
            progressPct: 0,
            attachments: [],
          }],
        },
      }],
    }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/me') return json({ id: 'ph-1', name: 'Айжан', phone: '+77070000000', pharmacyName: 'Ауэзова 134', city: 'Алматы' })
      if (path === '/api/mobile/training') return json({ total: 1, inProgress: 1, completed: 0, overdue: 0, assignments: [assignment] })
      if (path === '/api/mobile/training/assignments/assignment-fire') return json(assignment)
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    renderPortal('/learn/course/assignment-fire/lesson/lesson-fire-1')

    const video = await screen.findByLabelText('Видео урока')
    const material = screen.getByText('Текст учебного материала')
    expect(video).toHaveAttribute('src', '/training/fire-safety-training-video.mp4')
    expect(video.compareDocumentPosition(material) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('lets a pharmacist create a password on the first login after SMS verification', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: false, phoneMasked: '+7 (747) ***-**-53' })
      }
      if (path === '/api/mobile/auth/activation/sms/request') {
        return json({ sent: true, phoneMasked: '+7 (747) ***-**-53', ttlSeconds: 300 })
      }
      if (path === '/api/mobile/auth/activation/sms/verify') {
        return json({ verified: true, phoneMasked: '+7 (747) ***-**-53' })
      }
      if (path === '/api/mobile/auth/activation/password') {
        return json({
          tokens,
          pharmacist: { id: 'ph-1', name: 'Василий', phone: '+77470799353', pharmacyName: 'Аптека', city: 'Рудный' },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Василий', phone: '+77470799353', pharmacyName: 'Аптека', city: 'Рудный' })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), '061007500024')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    const phoneInput = await screen.findByRole('textbox', { name: 'Номер телефона' })
    await user.type(phoneInput, '7470799353')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '5445')
    await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
    await user.type(await screen.findByLabelText('Новый пароль'), 'NewFarm123!')
    await user.type(screen.getByLabelText('Повторите пароль'), 'NewFarm123!')
    await user.click(screen.getByRole('button', { name: 'Создать пароль и войти' }))

    expect(await screen.findByRole('heading', { name: 'Василий' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/activation/password',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ iin: '061007500024', phone: '+77470799353', password: 'NewFarm123!' }),
      }),
    )
  })

  it('registers a previously unknown IIN after phone verification', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: false, phoneMasked: '', registrationRequired: true })
      }
      if (path === '/api/mobile/auth/activation/sms/request') {
        return json({ sent: true, phoneMasked: '+7 (777) ***-**-22', ttlSeconds: 300 })
      }
      if (path === '/api/mobile/auth/activation/sms/verify') {
        return json({ verified: true, phoneMasked: '+7 (777) ***-**-22' })
      }
      if (path === '/api/mobile/auth/activation/password') {
        return json({
          tokens,
          pharmacist: { id: 'ph-new', name: 'Новый Фармацевт', phone: '+77770001122', pharmacyName: null, city: '' },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-new', name: 'Новый Фармацевт', phone: '+77770001122', pharmacyName: null, city: '' })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), '910228400016')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    expect(await screen.findByText(/Новый ИИН/)).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: 'Номер телефона' }), '7770001122')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))
    await user.type(await screen.findByRole('textbox', { name: 'Код из SMS' }), '5445')
    await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
    await user.type(await screen.findByRole('textbox', { name: 'ФИО' }), 'Новый Фармацевт')
    await user.type(screen.getByLabelText('Новый пароль'), 'NewFarm123!')
    await user.type(screen.getByLabelText('Повторите пароль'), 'NewFarm123!')
    await user.click(screen.getByRole('button', { name: 'Создать пароль и войти' }))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/activation/password',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          iin: '910228400016',
          phone: '+77770001122',
          password: 'NewFarm123!',
          name: 'Новый Фармацевт',
        }),
      }),
    )
    expect(await screen.findByRole('heading', { name: 'Новый Фармацевт' })).toBeInTheDocument()
  })

  it('returns to password login when a stale activation screen meets an already created password', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: false, phoneMasked: '+7 (747) ***-**-53' })
      }
      if (path === '/api/mobile/auth/activation/sms/request') {
        return json({ code: 'CONFLICT', message: 'Пароль уже создан' }, 409)
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), '061007500024')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    await user.type(await screen.findByRole('textbox', { name: 'Номер телефона' }), '7470799353')
    await user.click(screen.getByRole('button', { name: 'Получить код' }))

    expect(await screen.findByLabelText('Пароль')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Пароль для этого ИИН уже создан')
    expect(screen.queryByLabelText('Новый пароль')).not.toBeInTheDocument()
  })

  it('resets a forgotten password through the phone already bound to the IIN', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: true, phoneMasked: '+7 (747) ***-**-53' })
      }
      if (path === '/api/mobile/auth/password-reset/request') {
        return json({ sent: true, phoneMasked: '+7 (747) ***-**-53', ttlSeconds: 300 })
      }
      if (path === '/api/mobile/auth/password-reset/verify') {
        return json({ verified: true, phoneMasked: '+7 (747) ***-**-53' })
      }
      if (path === '/api/mobile/auth/password-reset/complete') {
        return json({
          tokens,
          pharmacist: { id: 'ph-1', name: 'Василий', phone: '+77470799353', pharmacyName: 'Аптека', city: 'Рудный' },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({ id: 'ph-1', name: 'Василий', phone: '+77470799353', pharmacyName: 'Аптека', city: 'Рудный' })
      }
      if (path === '/api/mobile/training') return json({ total: 0, inProgress: 0, completed: 0, overdue: 0, assignments: [] })
      if (path === '/api/mobile/promotions') return json([])
      return json({ message: `Unexpected request: ${path}` }, 500)
    })
    renderPortal()

    await user.type(screen.getByRole('textbox', { name: 'ИИН' }), '061007500024')
    await user.click(screen.getByRole('button', { name: 'Продолжить' }))
    await user.click(await screen.findByRole('button', { name: 'Забыли пароль?' }))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/password-reset/request',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ iin: '061007500024' }) }),
    )
    expect(await screen.findByText(/Код отправлен на привязанный номер/)).toHaveTextContent('+7 (747) ***-**-53')

    await user.type(screen.getByRole('textbox', { name: 'Код из SMS' }), '5445')
    await user.click(screen.getByRole('button', { name: 'Подтвердить код' }))
    await user.type(await screen.findByLabelText('Новый пароль'), 'ResetFarm123!')
    await user.type(screen.getByLabelText('Повторите пароль'), 'ResetFarm123!')
    await user.click(screen.getByRole('button', { name: 'Сохранить новый пароль и войти' }))

    expect(await screen.findByRole('heading', { name: 'Василий' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/auth/password-reset/complete',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ iin: '061007500024', password: 'ResetFarm123!' }),
      }),
    )
  })

  it('saves the final lesson through the server progress endpoint', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
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
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
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

    await userEvent.click(screen.getByRole('button', { name: 'Завершить тест' }))
    await waitFor(() => expect(document.activeElement).toHaveAttribute('id', 'quiz-question-card-q-1'))
    await userEvent.click(screen.getByRole('radio', { name: 'До еды' }))

    await userEvent.click(screen.getByRole('button', { name: 'Завершить тест' }))
    await waitFor(() => expect(document.activeElement).toHaveAttribute('id', 'quiz-question-card-q-2'))
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

  it('keeps the home page focused on active training and puts certificates in a separate tab', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
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

    expect(await screen.findByRole('heading', { name: 'Обучение' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Активный курс' })).toBeInTheDocument()
    expect(screen.queryByText('Завершённый курс')).not.toBeInTheDocument()
    expect(screen.queryByText('Уведомления')).not.toBeInTheDocument()
    expect(screen.queryByText('Ближайшие события')).not.toBeInTheDocument()
    expect(screen.queryByText('Препараты и бонусы')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Каталог' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Сертификаты' }))
    expect(await screen.findByRole('link', { name: /Завершённый курс/ })).toHaveAttribute('href', '/certificate.pdf')
  })

  it('selects an offline event through the same mobile training endpoint', async () => {
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
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
    localStorage.setItem('epharm.learner.tokens', JSON.stringify(tokens))
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

    expect(await screen.findByRole('heading', { name: 'Айжан' })).toBeInTheDocument()
    expect(refreshCalls).toBe(1)
    expect(JSON.parse(localStorage.getItem('epharm.learner.tokens') || '{}')).toEqual(refreshedTokens)
    expect(protectedCalls).toEqual(expect.arrayContaining([
      { path: '/api/mobile/auth/me', authorization: `Bearer ${tokens.accessToken}` },
      { path: '/api/mobile/training', authorization: `Bearer ${tokens.accessToken}` },
      { path: '/api/mobile/auth/me', authorization: `Bearer ${refreshedTokens.accessToken}` },
      { path: '/api/mobile/training', authorization: `Bearer ${refreshedTokens.accessToken}` },
    ]))
    expect(fetchMock).toHaveBeenCalledTimes(7)
  })
})
