import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import TaskInstructionPanel from './TaskInstructionPanel'
import type { Instruction, InstructionApi } from './task-instructions-api'

const material: Instruction = {
  id: 'material-1',
  producerId: 'producer-1',
  title: 'Новый инструктаж',
  instructions: 'Запишите демонстрационное видео',
  dueAt: '2099-01-01T12:00:00Z',
  status: 'pending',
  revision: 7,
  planIds: ['plan-1'],
  reviewNote: null,
  createdAt: '2026-10-29T12:00:00Z',
  canReview: true,
  completed: 0,
  totalTasks: 0,
}
let api: InstructionApi
beforeEach(() => {
  vi.resetAllMocks()
  api = {
    realm: 'admin',
    list: vi.fn().mockResolvedValue({ items: [material], total: 1, limit: 20, offset: 0 }),
    targets: vi.fn().mockResolvedValue({
      items: [
        {
          id: 'plan-1',
          producerId: 'producer-1',
          producerName: 'Демо',
          month: '2026-10-01',
          label: 'План Демо · октябрь',
          canUpload: true,
        },
      ],
      total: 1,
      limit: 50,
      offset: 0,
    }),
    upload: vi.fn(),
    review: vi.fn().mockResolvedValue({ ...material, status: 'approved', revision: 8 }),
    pdf: vi.fn(),
    results: vi.fn().mockResolvedValue({ items: [], total: 0, limit: 20, offset: 0 }),
    video: vi.fn(),
  }
})
it('authorizes review from the current record and posts its exact CAS version with a reason', async () => {
  render(<TaskInstructionPanel api={api} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Проверить материал' }))
  expect(screen.getByRole('button', { name: 'Сохранить решение' })).toBeDisabled()
  await user.type(
    screen.getByRole('textbox', { name: 'Основание решения' }),
    'Проверено содержание и срок',
  )
  await user.click(screen.getByRole('button', { name: 'Сохранить решение' }))
  await waitFor(() =>
    expect(api.review).toHaveBeenCalledWith(
      'material-1',
      7,
      'approved',
      'Проверено содержание и срок',
    ),
  )
})
it('producer realm never exposes an approval even if a malformed fixture has canReview true', async () => {
  api.realm = 'producer'
  render(<TaskInstructionPanel api={api} />)
  await screen.findByText('Новый инструктаж')
  expect(screen.queryByRole('button', { name: 'Проверить материал' })).not.toBeInTheDocument()
  expect(api.review).not.toHaveBeenCalled()
})
it('a denied target refresh cannot submit a previously selected plan', async () => {
  vi.mocked(api.targets)
    .mockResolvedValueOnce({
      items: [
        {
          id: 'plan-1',
          producerId: 'producer-1',
          producerName: 'Демо',
          month: '2026-10-01',
          label: 'Разрешённый план',
          canUpload: true,
        },
      ],
      total: 100,
      limit: 50,
      offset: 0,
    })
    .mockRejectedValueOnce(new Error('Доступ отозван'))
  render(<TaskInstructionPanel api={api} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Добавить PDF-инструктаж' }))
  await user.click(await screen.findByRole('checkbox', { name: 'Разрешённый план' }))
  await user.upload(
    screen.getByLabelText('PDF до 20 МБ'),
    new File(['%PDF-test'], 'test.pdf', { type: 'application/pdf' }),
  )
  expect(screen.getByRole('button', { name: 'Отправить PDF на согласование' })).toBeEnabled()
  await user.click(screen.getByRole('button', { name: 'Следующие планы' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Доступ отозван')
  expect(screen.getByRole('button', { name: 'Отправить PDF на согласование' })).toBeDisabled()
  expect(api.upload).not.toHaveBeenCalled()
})
it('an upload retry keeps the stable opaque ID and uses only selected server plan records', async () => {
  api.realm = 'producer'
  vi.mocked(api.upload)
    .mockRejectedValueOnce(new Error('Соединение прервано'))
    .mockResolvedValueOnce(material)
  render(<TaskInstructionPanel api={api} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Добавить PDF-инструктаж' }))
  await user.click(await screen.findByRole('checkbox', { name: 'План Демо · октябрь' }))
  await user.type(
    screen.getByRole('textbox', { name: 'Название инструктажа' }),
    'Материал для обучения',
  )
  await user.type(
    screen.getByRole('textbox', { name: 'Задание для видео' }),
    'Покажите выполнение задания',
  )
  fireEvent.change(screen.getByLabelText('Срок выполнения (местное время)'), {
    target: { value: '2099-01-01T12:00' },
  })
  const file = new File(['%PDF-synthetic'], 'instruction.pdf', { type: 'application/pdf' })
  await user.upload(screen.getByLabelText('PDF до 20 МБ'), file)
  const submit = screen.getByRole('button', { name: 'Отправить PDF на согласование' })
  expect(submit).toBeEnabled()
  expect((screen.getByLabelText('PDF до 20 МБ') as HTMLInputElement).files).toHaveLength(1)
  // jsdom does not apply user-event's FileList to native required-file validity.
  fireEvent.submit(submit.closest('form')!)
  expect(await screen.findByRole('alert')).toHaveTextContent('Соединение прервано')
  fireEvent.submit(submit.closest('form')!)
  await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(2))
  const first = vi.mocked(api.upload).mock.calls[0][0]
  const second = vi.mocked(api.upload).mock.calls[1][0]
  expect(first.id).toMatch(/^[0-9a-f-]{36}$/)
  expect(second.id).toBe(first.id)
  expect(first.planIds).toEqual(['plan-1'])
  expect(first).not.toHaveProperty('producerId')
  expect(localStorage.getItem('instruction-draft')).toBeNull()
})
it('a stale review does not silently retry or leave stale approval controls visible', async () => {
  vi.mocked(api.review).mockRejectedValue(new Error('Материал уже изменился'))
  render(<TaskInstructionPanel api={api} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Проверить материал' }))
  await user.type(
    screen.getByRole('textbox', { name: 'Основание решения' }),
    'Проверено содержание',
  )
  await user.click(screen.getByRole('button', { name: 'Сохранить решение' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Материал уже изменился')
  expect(screen.queryByRole('button', { name: 'Проверить материал' })).not.toBeInTheDocument()
  expect(api.review).toHaveBeenCalledTimes(1)
})
it('approved videos are an explicit scoped request and an empty response is honest', async () => {
  vi.mocked(api.list).mockResolvedValue({
    items: [{ ...material, status: 'approved', canReview: false }],
    total: 1,
    limit: 20,
    offset: 0,
  })
  render(<TaskInstructionPanel api={api} />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Подтверждённые видео' }))
  expect(await screen.findByText(/Принятых видеоотчётов пока нет/)).toBeInTheDocument()
  expect(api.results).toHaveBeenCalledWith('material-1', 0, expect.any(AbortSignal))
  expect(api.video).not.toHaveBeenCalled()
})
