import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import TaskConversation from './TaskConversation'
import MonthlyVisitPanel from './MonthlyVisitPanel'
import TaskTemplateDialog from './TaskTemplateDialog'
import { adminTaskApi, type TaskGateway } from './tasks-api'
vi.mock('./tasks-api', () => ({
  adminTaskApi: { branches: vi.fn(), templates: vi.fn(), templated: vi.fn() },
}))
const branch = {
  id: 'demo-branch',
  name: 'Демо-аптека',
  city: 'Демо',
  address: 'Демо, 1',
  active: true,
  createEligible: true,
  canConfigureReward: false,
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(adminTaskApi.branches).mockResolvedValue([branch])
  vi.mocked(adminTaskApi.templates).mockResolvedValue([
    {
      id: 'maintenance',
      title: 'Реструктуризация полки',
      instructions: 'Снимите до и после',
      shelfMode: 'occupied',
      requiredPhotos: 2,
      checklist: ['Фото до', 'Фото после'],
    },
  ])
  vi.mocked(adminTaskApi.templated).mockResolvedValue({ dispatchIds: ['created-task'] })
})
it('separate conversation retries its same intent ID and never submits a task attempt', async () => {
  const comment = vi
    .fn()
    .mockRejectedValueOnce(new Error('Сеть недоступна'))
    .mockResolvedValueOnce({ id: 'comment-1' })
  const submit = vi.fn()
  const gateway = {
    realm: 'pharmacist',
    comments: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
    comment,
    submit,
  } as unknown as TaskGateway
  render(<TaskConversation itemId="item-1" gateway={gateway} canWrite />)
  const user = userEvent.setup()
  await screen.findByText('Комментариев пока нет.')
  await user.type(screen.getByLabelText('Новый комментарий'), 'Нужна помощь с выкладкой')
  await user.click(screen.getByRole('button', { name: 'Отправить комментарий' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Сеть недоступна')
  await user.click(screen.getByRole('button', { name: 'Отправить комментарий' }))
  await waitFor(() => expect(comment).toHaveBeenCalledTimes(2))
  expect(comment.mock.calls[0][1]).toBe(comment.mock.calls[1][1])
  expect(submit).not.toHaveBeenCalled()
})
it('read-only capability conversation exposes no comment writer even when the gateway has one', async () => {
  const gateway = {
    realm: 'capability',
    comments: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
    comment: vi.fn(),
  } as unknown as TaskGateway
  render(<TaskConversation itemId="item-1" gateway={gateway} canWrite={false} />)
  await screen.findByText('Комментариев пока нет.')
  expect(screen.queryByLabelText('Новый комментарий')).not.toBeInTheDocument()
  expect(gateway.comment).not.toHaveBeenCalled()
})
it('monthly self-service submits only a server branch and first-day month then opens its existing task', async () => {
  const request = vi.fn(
    async <T,>(path: string, _init?: RequestInit): Promise<T> =>
      path.endsWith('/branches')
        ? ([branch] as T)
        : ({ dispatchId: 'existing-visit', resumed: true } as T),
  )
  const started = vi.fn()
  render(<MonthlyVisitPanel request={request} onStarted={started} />)
  const user = userEvent.setup()
  await screen.findByRole('option', { name: 'Демо-аптека · Демо · Демо, 1' })
  await user.selectOptions(screen.getByRole('combobox', { name: 'Аптека визита' }), 'demo-branch')
  fireEvent.change(screen.getByLabelText('Месяц визита'), { target: { value: '2026-11' } })
  await user.click(screen.getByRole('button', { name: 'Начать или продолжить визит' }))
  await waitFor(() => expect(started).toHaveBeenCalledWith('existing-visit'))
  const call = request.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({
    branchId: 'demo-branch',
    month: '2026-11-01',
  })
})
it('template uses server defaults occupied snapshot and explicit names without inventing money or catalog IDs', async () => {
  const created = vi.fn()
  render(<TaskTemplateDialog onClose={vi.fn()} onCreated={created} />)
  const user = userEvent.setup()
  await screen.findByRole('option', { name: 'Реструктуризация полки' })
  await user.selectOptions(screen.getByRole('combobox', { name: 'Шаблон задания' }), 'maintenance')
  await user.click(screen.getByRole('checkbox', { name: 'Демо-аптека · Демо · Демо, 1' }))
  expect(screen.getByRole('combobox', { name: 'Режим полок' })).toHaveValue('occupied')
  fireEvent.change(screen.getByLabelText('Месяц размещения'), { target: { value: '2099-01' } })
  fireEvent.change(screen.getByLabelText('Срок шаблонного задания'), {
    target: { value: '2099-01-31T12:00' },
  })
  await user.type(screen.getByLabelText('Товары шаблона'), 'Товар из инструкции')
  await user.click(screen.getByRole('button', { name: 'Создать задания по шаблону' }))
  await waitFor(() => expect(created).toHaveBeenCalledWith('created-task'))
  expect(adminTaskApi.templated).toHaveBeenCalledWith(
    expect.objectContaining({
      branchIds: ['demo-branch'],
      templateType: 'maintenance',
      shelfMode: 'occupied',
      month: '2099-01-01',
      rewardAmountKzt: 0,
      stockProducts: [{ key: '0', name: 'Товар из инструкции' }],
    }),
  )
})
