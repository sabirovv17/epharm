import { expect, test } from '@playwright/test'

const tokens = { accessToken: 'learner-access', refreshToken: 'learner-refresh' }

for (const target of [
  { name: 'mobile', width: 390, height: 844 },
  { name: 'desktop', width: 1440, height: 1000 },
]) {
  test(`фармацевт открывает персональную ссылку и проходит курс — ${target.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: target.width, height: target.height })
    let stageCompleted = false
    const lesson = {
      id: 'lesson-link',
      title: 'Назначенный урок',
      description: 'Материал доступен только после входа',
      content: 'Изучите памятку и подтвердите прохождение.',
      kind: 'interactive',
      externalUrl: 'https://learn.example.org/module',
      required: true,
      durationMin: 5,
      order: 0,
      progressPct: 0,
      completedAt: null as string | null,
      attachments: [],
    }
    const assignment = {
      id: 'assignment-link',
      programName: 'Персональный курс',
      programShortDescription: 'Назначен конкретному фармацевту',
      pharmacyName: 'Аптека Ауэзова 134',
      city: 'Алматы',
      status: 'in_progress',
      format: 'online',
      progressPct: 0,
      startedAt: '2026-09-01T10:00:00Z',
      stages: [
        {
          id: 'stage-link',
          title: 'Онлайн-курс',
          type: 'online_course',
          status: 'in_progress',
          progressPct: 0,
          course: {
            id: 'course-link',
            title: 'Персональный курс',
            description: 'Назначен конкретному фармацевту',
            durationMin: 5,
            lessons: [lesson],
          },
        },
      ],
    }

    await page.route('**/api/mobile/**', async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const json = (body: unknown, status = 200) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

      if (path === '/api/mobile/auth/activation/status') {
        return json({ passwordSet: true, phoneMasked: '+7 (700) ***-**-01' })
      }
      if (path === '/api/mobile/auth/login') {
        const credentials = request.postDataJSON() as { iin?: string; password?: string }
        if (credentials.iin !== '000101500011' || credentials.password !== 'TestPass123!') {
          return json({ message: 'Invalid test credentials' }, 401)
        }
        return json({
          tokens,
          pharmacist: {
            id: 'ph-link',
            name: 'Айжан Фармацевт',
            phone: '+77070000001',
            pharmacyName: assignment.pharmacyName,
            city: assignment.city,
          },
        })
      }
      if (path === '/api/mobile/auth/me') {
        return json({
          id: 'ph-link',
          name: 'Айжан Фармацевт',
          phone: '+77070000001',
          pharmacyName: assignment.pharmacyName,
          city: assignment.city,
        })
      }
      if (path === '/api/mobile/training/assignments/assignment-link') return json(assignment)
      if (
        path === '/api/mobile/training/assignments/assignment-link/stages/stage-link/lessons/lesson-link' &&
        request.method() === 'PATCH'
      ) {
        stageCompleted = true
        assignment.status = 'completed'
        assignment.progressPct = 100
        assignment.stages[0].status = 'completed'
        assignment.stages[0].progressPct = 100
        lesson.progressPct = 100
        lesson.completedAt = new Date().toISOString()
        return json(assignment)
      }
      if (path === '/api/mobile/training') {
        return json({
          total: 1,
          inProgress: stageCompleted ? 0 : 1,
          completed: stageCompleted ? 1 : 0,
          overdue: 0,
          assignments: [assignment],
        })
      }
      return json({ message: `Unexpected request: ${path}` }, 500)
    })

    await page.goto('/learn/course/assignment-link')
    await page.getByRole('textbox', { name: 'ИИН' }).fill('000101500011')
    await page.getByRole('button', { name: 'Продолжить' }).click()
    await page.getByRole('textbox', { name: /^Пароль/ }).fill('TestPass123!')
    await page.getByRole('button', { name: 'Войти' }).click()

    await expect(page.getByRole('heading', { name: 'Персональный курс' }).first()).toBeVisible()
    await page.getByRole('button', { name: /Назначенный урок/ }).click()
    await expect(page.getByRole('heading', { name: 'Назначенный урок' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Открыть материал' })).toHaveAttribute(
      'href',
      lesson.externalUrl,
    )
    await page.getByRole('button', { name: 'Завершить курс' }).click()

    await expect.poll(() => stageCompleted).toBe(true)
  })
}
