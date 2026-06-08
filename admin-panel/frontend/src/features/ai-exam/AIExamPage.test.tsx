// Тесты AIExamPage — банк вопросов из real API, табы, create-modal.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ExamQuestionDto } from '@/lib/api-types'
import AIExamPage from './AIExamPage'

const examHooks = vi.hoisted(() => ({
  useExamQuestions: vi.fn(),
  useCreateExamQuestion: vi.fn(),
  useUpdateExamQuestion: vi.fn(),
  useDeleteExamQuestion: vi.fn(),
}))
vi.mock('@/lib/queries/aiExam', () => examHooks)

function mkQ(over: Partial<ExamQuestionDto> = {}): ExamQuestionDto {
  return {
    id: 'q_1',
    prompt: 'В каких случаях?',
    kind: 'factual',
    category: 'Назальные',
    keywords: ['промывание', 'гигиена'],
    difficulty: 1,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function setQuestions(list: ExamQuestionDto[] = [], extra: Record<string, unknown> = {}) {
  examHooks.useExamQuestions.mockReturnValue({
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
  setQuestions([])
  examHooks.useCreateExamQuestion.mockReturnValue({ mutate: vi.fn(), isPending: false })
  examHooks.useUpdateExamQuestion.mockReturnValue({ mutate: vi.fn(), isPending: false })
  examHooks.useDeleteExamQuestion.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <AIExamPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AIExamPage — рендер', () => {
  it('H1 + Empty банка', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /AI-Экзаменация/i })).toBeInTheDocument()
    expect(screen.getByText(/Банк вопросов пуст/i)).toBeInTheDocument()
  })

  it('показывает вопросы из API', () => {
    setQuestions([mkQ({ id: 'q_a' }), mkQ({ id: 'q_b', kind: 'scenario' })])
    renderPage()
    expect(screen.getByTestId('exam-questions')).toBeInTheDocument()
    expect(screen.getByTestId('exam-question-q_a')).toBeInTheDocument()
    expect(screen.getByTestId('exam-question-q_b')).toBeInTheDocument()
  })

  it('таб «Результаты» — Empty (Этап 4)', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Результаты/ }))
    expect(screen.getByText(/Результатов пока нет/i)).toBeInTheDocument()
  })
})

describe('AIExamPage — create modal', () => {
  it('кнопка открывает модалку', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый вопрос/ })[0])
    expect(screen.getByText(/используются для авто-оценки/i)).toBeInTheDocument()
  })

  it('submit без текста — ошибка, mutate не зовётся', async () => {
    const mutate = vi.fn()
    examHooks.useCreateExamQuestion.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый вопрос/ })[0])
    await user.click(screen.getByRole('button', { name: /^Добавить$/ }))
    expect(screen.getByText(/Введите текст вопроса/i)).toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it('submit с текстом + ключевыми словами зовёт useCreateExamQuestion', async () => {
    const mutate = vi.fn()
    examHooks.useCreateExamQuestion.mockReturnValue({ mutate, isPending: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getAllByRole('button', { name: /Новый вопрос/ })[0])
    await user.type(screen.getByPlaceholderText(/В каких случаях рекомендуете/i), 'Вопрос Z?')
    await user.type(screen.getByPlaceholderText(/промывание, гигиена/i), 'alpha, beta')
    await user.click(screen.getByRole('button', { name: /^Добавить$/ }))
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({ prompt: 'Вопрос Z?', keywords: ['alpha', 'beta'] }),
      expect.any(Object),
    )
  })
})

describe('AIExamPage — loading/error', () => {
  it('isError + Повторить', async () => {
    const refetch = vi.fn()
    setQuestions([], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить банк вопросов/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('AIExamPage — delete (Блок 2)', () => {
  it('кнопка удаления вопроса с подтверждением → useDeleteExamQuestion', async () => {
    const del = vi.fn()
    examHooks.useDeleteExamQuestion.mockReturnValue({ mutate: del, isPending: false })
    setQuestions([mkQ({ id: 'q_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('exam-question-delete-q_x'))
    expect(del).toHaveBeenCalledWith('q_x', expect.anything())
  })
})
