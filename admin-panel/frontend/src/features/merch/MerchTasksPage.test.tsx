import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import MerchTasksPage from './MerchTasksPage'
import { adminTaskApi, adminTaskGateway } from './tasks-api'
vi.mock('@/app/useAdministrativeCapabilities', () => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('./tasks-api', () => ({
  adminTaskApi: { list: vi.fn(), branches: vi.fn(), create: vi.fn() },
  adminTaskGateway: { get: vi.fn() },
}))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAdministrativeCapabilities).mockReturnValue({
    isPending: false,
    isError: false,
    canViewMerchTasks: true,
    canCreateMerchTasks: true,
    canExecuteMerchTasks: false,
    canReviewMerchTasks: false,
  } as ReturnType<typeof useAdministrativeCapabilities>)
  vi.mocked(adminTaskApi.list).mockResolvedValue({
    items: [],
    offset: 0,
    limit: 20,
    hasMore: false,
  })
  vi.mocked(adminTaskApi.branches).mockResolvedValue([
    {
      id: 'branch-active',
      name: 'Демо-активная',
      city: 'Демо',
      address: 'Демо, 1',
      active: true,
      createEligible: true,
      canConfigureReward: false,
    },
    {
      id: 'branch-old',
      name: 'Демо-историческая',
      city: 'Демо',
      address: 'Демо, 2',
      active: false,
      createEligible: false,
      canConfigureReward: false,
    },
  ])
  const created = {
    id: 'task-new-demo',
    branchId: 'branch-active',
    title: 'Демо-задание',
    status: 'ready',
    dueAt: null,
    sourceSystem: 'EPHARM',
    version: 1,
    rewardAmountKzt: 0,
    items: [],
    reward: null,
    itemCount: 1,
    detailsIncluded: true,
    priority: 'normal',
    taskType: 'photo_report',
  }
  vi.mocked(adminTaskApi.create).mockResolvedValue(created)
  vi.mocked(adminTaskGateway.get).mockResolvedValue(created)
})
describe('native task creation capability and required fields', () => {
  it('does not fetch tasks when the server denies section view', () => {
    vi.mocked(useAdministrativeCapabilities).mockReturnValue({
      isPending: false,
      canViewMerchTasks: false,
    } as ReturnType<typeof useAdministrativeCapabilities>)
    render(<MerchTasksPage />)
    expect(screen.getByText('Нет доступа к заданиям мерча')).toBeInTheDocument()
    expect(adminTaskApi.list).not.toHaveBeenCalled()
    expect(adminTaskApi.branches).not.toHaveBeenCalled()
  })
  it('creates only in an active authorized branch and cannot assign money without the field permission', async () => {
    const user = userEvent.setup()
    render(<MerchTasksPage />)
    await user.click(screen.getByRole('button', { name: 'Новое задание' }))
    expect(screen.getByRole('button', { name: 'Создать задание' })).toBeDisabled()
    await screen.findByRole('option', { name: /Демо-активная/ })
    expect(screen.queryByRole('option', { name: /Демо-историческая/ })).not.toBeInTheDocument()
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Аптека нового задания' }),
      'branch-active',
    )
    await user.type(screen.getByLabelText('Название нового задания'), 'Демо-задание')
    const future = new Date(Date.now() + 2 * 86400000)
      .toLocaleString('sv-SE', { timeZone: 'Asia/Almaty' })
      .slice(0, 16)
      .replace(' ', 'T')
    await user.type(screen.getByLabelText('Срок задания Алматы'), future)
    await user.type(screen.getByLabelText('Название пункта 1'), 'Демо-проверка')
    expect(screen.getByLabelText('Вознаграждение нового задания')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Создать задание' }))
    await waitFor(() => expect(adminTaskApi.create).toHaveBeenCalledOnce())
    const body = vi.mocked(adminTaskApi.create).mock.calls[0][0]
    expect(body.branchId).toBe('branch-active')
    expect(body.rewardAmountKzt).toBe(0)
    expect(body.items).toEqual([
      {
        title: 'Демо-проверка',
        instructions: '',
        required: true,
        requiredPhotos: 1,
        checklist: [],
      },
    ])
    expect(Date.parse(body.dueAt)).toBe(Date.parse(`${future}+05:00`))
    expect(body).not.toHaveProperty('assignedPharmacistId')
  })
})
