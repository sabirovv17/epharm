// Тесты LMSPage — каталог курсов из real API, табы, create-modal.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { CourseDto } from '@/lib/api-types'
import LMSPage from './LMSPage'

const lmsHooks = vi.hoisted(() => ({
  useCourses: vi.fn(),
  useCreateCourse: vi.fn(),
  useUpdateCourse: vi.fn(),
  useDeleteCourse: vi.fn(),
}))
vi.mock('@/lib/queries/lms', () => lmsHooks)

function mkCourse(over: Partial<CourseDto> = {}): CourseDto {
  return {
    id: 'crs_1',
    title: 'Аквалор',
    status: 'published',
    category: 'Назальные',
    lessons: 6,
    durationMin: 45,
    enrolled: 100,
    completed: 40,
    bonus: 1500,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function setCourses(list: CourseDto[] = [], extra: Record<string, unknown> = {}) {
  lmsHooks.useCourses.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setCourses([])
  lmsHooks.useCreateCourse.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useUpdateCourse.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useDeleteCourse.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <LMSPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LMSPage — рендер', () => {
  it('H1 + метрики', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Обучение \/ LMS/i })).toBeInTheDocument()
    expect(screen.getByText('Курсов в каталоге')).toBeInTheDocument()
  })

  it('Empty когда нет опубликованных', () => {
    setCourses([mkCourse({ status: 'draft' })])
    renderPage()
    expect(screen.getByText(/Курсов пока нет/i)).toBeInTheDocument()
  })

  it('показывает опубликованные курсы', () => {
    setCourses([
      mkCourse({ id: 'crs_a', title: 'Аквалор' }),
      mkCourse({ id: 'crs_b', title: 'Пробиотики' }),
    ])
    renderPage()
    expect(screen.getByTestId('lms-courses')).toBeInTheDocument()
    expect(screen.getByTestId('lms-course-crs_a')).toBeInTheDocument()
    expect(screen.getByTestId('lms-course-crs_b')).toBeInTheDocument()
  })

  it('переключение на «Черновики» показывает draft-курсы', async () => {
    setCourses([
      mkCourse({ id: 'crs_pub', status: 'published' }),
      mkCourse({ id: 'crs_dr', status: 'draft', title: 'Черновик-курс' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Черновики/ }))
    expect(screen.getByTestId('lms-course-crs_dr')).toBeInTheDocument()
  })
})

describe('LMSPage — create modal', () => {
  it('кнопка «Новый курс» открывает модалку', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый курс/ })[0])
    expect(screen.getByText(/Уроки и тесты добавите/i)).toBeInTheDocument()
  })

  it('submit без названия — ошибка, mutate не зовётся', async () => {
    const mutate = vi.fn()
    lmsHooks.useCreateCourse.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый курс/ })[0])
    await user.click(screen.getByRole('button', { name: /^Создать$/ }))
    expect(screen.getByText(/Введите название курса/i)).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it('submit с названием зовёт useCreateCourse', async () => {
    const mutate = vi.fn()
    lmsHooks.useCreateCourse.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый курс/ })[0])
    await user.type(screen.getByPlaceholderText(/Линейка Аквалор/i), 'Новый курс X')
    await user.click(screen.getByRole('button', { name: /^Создать$/ }))
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Новый курс X' }),
      expect.any(Object),
    )
  })
})

describe('LMSPage — loading/error', () => {
  it('isLoading', () => {
    setCourses([], { isLoading: true })
    renderPage()
    expect(screen.getByText(/Загружаем курсы…/i)).toBeInTheDocument()
  })

  it('isError + Повторить', async () => {
    const refetch = vi.fn()
    setCourses([], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить курсы/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('LMSPage — delete (Блок 2)', () => {
  it('кнопка удаления с подтверждением → useDeleteCourse', async () => {
    const del = vi.fn()
    lmsHooks.useDeleteCourse.mockReturnValue({ mutate: del, isPending: false })
    setCourses([mkCourse({ id: 'crs_x', title: 'Курс X' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('lms-course-delete-crs_x'))
    expect(del).toHaveBeenCalledWith('crs_x', expect.anything())
  })

  it('confirm=false → delete не зовётся', async () => {
    const del = vi.fn()
    lmsHooks.useDeleteCourse.mockReturnValue({ mutate: del, isPending: false })
    setCourses([mkCourse({ id: 'crs_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('lms-course-delete-crs_x'))
    expect(del).not.toHaveBeenCalled()
  })
})
