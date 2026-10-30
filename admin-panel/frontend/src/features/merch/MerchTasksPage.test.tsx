import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAdministrativeCapabilities } from '@/app/useAdministrativeCapabilities'
import MerchTasksPage from './MerchTasksPage'
import { adminTaskApi, adminTaskGateway, type MerchTask } from './tasks-api'
vi.mock('@/app/useAdministrativeCapabilities', () => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('./tasks-api', () => ({
  adminTaskApi: { list: vi.fn(), branches: vi.fn(), create: vi.fn(), createBulk: vi.fn() },
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
  it('does not mistake a capability request failure for denied access or fetch task data', async () => {
    const refetch = vi.fn()
    vi.mocked(useAdministrativeCapabilities).mockReturnValue({
      isPending: false,
      isError: true,
      isFetching: false,
      error: new Error('Сервис недоступен'),
      canViewMerchTasks: false,
      refetch,
    } as unknown as ReturnType<typeof useAdministrativeCapabilities>)
    render(<MerchTasksPage />)
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Не удалось проверить доступ к заданиям. Сервис недоступен',
    )
    expect(screen.queryByText('Нет доступа к заданиям мерча')).not.toBeInTheDocument()
    expect(adminTaskApi.list).not.toHaveBeenCalled()
    expect(adminTaskApi.branches).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Повторить проверку доступа' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('shows the server pending count for a mixed revision task without inferring personal ownership', async () => {
    const task = await vi.mocked(adminTaskGateway.get)(
      'task-new-demo',
      new AbortController().signal,
    )
    vi.mocked(adminTaskApi.list).mockResolvedValue({
      items: [
        {
          ...task,
          status: 'revision_required',
          itemCount: 4,
          pendingReviewCount: 2,
          nextPendingReviewItemId: 'pending-item',
        },
      ],
      offset: 0,
      limit: 20,
      hasMore: false,
    })
    vi.mocked(adminTaskGateway.get).mockClear()
    render(<MerchTasksPage />)
    expect(await screen.findByText('Пунктов: 2')).toBeInTheDocument()
    expect(screen.getByText('Нужна доработка')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Всего пунктов' })).toBeInTheDocument()
    expect(screen.getByText('Показаны 1–1')).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Мне выполнить' })).not.toBeInTheDocument()
    expect(adminTaskGateway.get).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Открыть отчёт' }))
    await waitFor(() =>
      expect(adminTaskGateway.get).toHaveBeenCalledWith(task.id, expect.any(AbortSignal)),
    )
    expect(
      screen.queryByRole('button', { name: 'Принять пункт', exact: true }),
    ).not.toBeInTheDocument()
  })

  it('does not treat missing review metadata as zero pending or as an executable personal task', async () => {
    const task = await vi.mocked(adminTaskGateway.get)(
      'task-new-demo',
      new AbortController().signal,
    )
    vi.mocked(adminTaskApi.list).mockResolvedValue({
      items: [task],
      offset: 0,
      limit: 20,
      hasMore: false,
    })
    render(<MerchTasksPage />)
    expect(await screen.findByText('Нет данных')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Открыть отчёт' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Открыть задание' })).toBeInTheDocument()
  })

  it('keeps list errors distinct from empty queues and resets applied server filters', async () => {
    vi.mocked(adminTaskApi.list).mockRejectedValueOnce(new Error('Очередь недоступна'))
    render(<MerchTasksPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Очередь недоступна')
    expect(screen.queryByText('В вашей области заданий пока нет.')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Повторить', exact: true }))
    await screen.findByText('В вашей области заданий пока нет.')
    const previousCalls = vi.mocked(adminTaskApi.list).mock.calls.length
    await userEvent.type(screen.getByLabelText('Поиск заданий'), 'Полка 100%')
    expect(adminTaskApi.list).toHaveBeenCalledTimes(previousCalls)
    await userEvent.click(screen.getByRole('button', { name: 'Найти' }))
    expect(await screen.findByText('По этим условиям заданий нет.')).toBeInTheDocument()
    expect(screen.getByText(/Применён поиск: «Полка 100%»/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }))
    await waitFor(() =>
      expect(adminTaskApi.list).toHaveBeenLastCalledWith(0, expect.any(AbortSignal), {
        queue: 'all',
        q: '',
      }),
    )
    expect(screen.getByLabelText('Поиск заданий')).toHaveValue('')
    expect(await screen.findByText('В вашей области заданий пока нет.')).toBeInTheDocument()
  })

  it('distinguishes unavailable pharmacy labels from a task request failure and retries them separately', async () => {
    vi.mocked(adminTaskApi.branches).mockRejectedValueOnce(new Error('Branch lookup failed'))
    render(<MerchTasksPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Не удалось загрузить названия и адреса аптек',
    )
    await screen.findByText('В вашей области заданий пока нет.')
    const previousCalls = vi.mocked(adminTaskApi.list).mock.calls.length
    await userEvent.click(screen.getByRole('button', { name: 'Повторить загрузку аптек' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(adminTaskApi.branches).toHaveBeenCalledTimes(2)
    expect(adminTaskApi.list).toHaveBeenCalledTimes(previousCalls)
  })

  it('shows branch address and task status, type and priority before opening a review', async () => {
    const task = await vi.mocked(adminTaskGateway.get)(
      'task-new-demo',
      new AbortController().signal,
    )
    vi.mocked(adminTaskApi.list).mockResolvedValue({
      items: [{ ...task, status: 'revision_required', priority: 'urgent', itemCount: 3 }],
      offset: 0,
      limit: 20,
      hasMore: false,
    })
    vi.mocked(adminTaskGateway.get).mockClear()
    render(<MerchTasksPage />)
    expect(await screen.findByText('Нужна доработка')).toBeInTheDocument()
    expect(screen.getByText('Фотоотчёт')).toBeInTheDocument()
    expect(screen.getByText('Срочный')).toBeInTheDocument()
    expect(await screen.findByText('Демо · Демо, 1')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Всего пунктов' })).toBeInTheDocument()
    expect(adminTaskGateway.get).not.toHaveBeenCalled()
  })
  it('keeps unknown historical task values and unavailable branch data explicit', async () => {
    const task = await vi.mocked(adminTaskGateway.get)(
      'task-new-demo',
      new AbortController().signal,
    )
    vi.mocked(adminTaskApi.list).mockResolvedValue({
      items: [
        {
          ...task,
          branchId: 'unavailable',
          status: 'legacy-state',
          taskType: '__proto__',
          priority: 'legacy-priority',
        } as MerchTask,
      ],
      offset: 0,
      limit: 20,
      hasMore: false,
    })
    render(<MerchTasksPage />)
    expect(await screen.findByText('Исторический статус')).toBeInTheDocument()
    expect(screen.getByText('Другой тип задания')).toBeInTheDocument()
    expect(screen.getByText('Приоритет не указан')).toBeInTheDocument()
    expect(screen.getByText('Название аптеки недоступно')).toBeInTheDocument()
    expect(screen.queryByText('Демо · Демо, 1')).not.toBeInTheDocument()
  })
  it.each([true, false])(
    'does not advertise retired code operations when task view is %s',
    async (canViewMerchTasks) => {
      vi.mocked(useAdministrativeCapabilities).mockReturnValue({
        isPending: false,
        canViewMerchTasks,
        canViewMerchStaffAccess: true,
      } as ReturnType<typeof useAdministrativeCapabilities>)
      render(<MerchTasksPage />)
      expect(screen.queryByRole('link', { name: 'Коды доступа' })).not.toBeInTheDocument()
      if (canViewMerchTasks) await waitFor(() => expect(adminTaskApi.list).toHaveBeenCalledOnce())
      else expect(adminTaskApi.list).not.toHaveBeenCalled()
    },
  )
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
  it('filters the pending queue and searches literal names on the server', async () => {
    const user = userEvent.setup()
    render(<MerchTasksPage />)
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Рабочая очередь заданий' }),
      'review',
    )
    await user.type(screen.getByLabelText('Поиск заданий'), 'Полка 100%')
    await user.click(screen.getByRole('button', { name: 'Найти' }))
    await waitFor(() =>
      expect(adminTaskApi.list).toHaveBeenLastCalledWith(0, expect.any(AbortSignal), {
        queue: 'review',
        q: 'Полка 100%',
      }),
    )
  })
  it('bulk retry keeps one request ID and never invents money permission', async () => {
    const user = userEvent.setup()
    vi.mocked(adminTaskApi.createBulk)
      .mockRejectedValueOnce(new Error('Connection lost'))
      .mockResolvedValueOnce({ dispatchIds: ['task-new-demo'] })
    render(<MerchTasksPage />)
    await user.click(screen.getByRole('button', { name: 'Новое задание' }))
    await user.click(screen.getByLabelText('Назначить нескольким аптекам'))
    await screen.findByRole('option', { name: /Демо-активная/ })
    await user.selectOptions(screen.getByLabelText('Аптеки массового задания'), 'branch-active')
    await user.type(screen.getByLabelText('Название нового задания'), 'Bulk synthetic')
    const future = new Date(Date.now() + 2 * 86400000)
      .toLocaleString('sv-SE', { timeZone: 'Asia/Almaty' })
      .slice(0, 16)
      .replace(' ', 'T')
    await user.type(screen.getByLabelText('Срок задания Алматы'), future)
    await user.type(screen.getByLabelText('Название пункта 1'), 'Required proof')
    await user.click(screen.getByRole('button', { name: 'Создать задание' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Создать задание' }))
    await waitFor(() => expect(adminTaskApi.createBulk).toHaveBeenCalledTimes(2))
    expect(vi.mocked(adminTaskApi.createBulk).mock.calls[1][0]).toEqual(
      vi.mocked(adminTaskApi.createBulk).mock.calls[0][0],
    )
    expect(vi.mocked(adminTaskApi.createBulk).mock.calls[0][0]).toMatchObject({
      branchIds: ['branch-active'],
      rewardAmountKzt: 0,
    })
    expect(adminTaskApi.create).not.toHaveBeenCalled()
  })
})
