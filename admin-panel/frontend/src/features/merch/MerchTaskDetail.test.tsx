import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MerchTaskDetail from './MerchTaskDetail'
import type { MerchTask, TaskGateway } from './tasks-api'
const task: MerchTask = {
  id: 'task-demo',
  branchId: 'branch-demo',
  title: 'Демо-проверка выкладки',
  status: 'submitted',
  dueAt: '2026-11-01T00:00:00Z',
  sourceSystem: 'EPHARM',
  version: 3,
  rewardAmountKzt: 400,
  reward: null,
  itemCount: 1,
  detailsIncluded: true,
  priority: 'normal',
  taskType: 'photo_report',
  items: [
    {
      id: 'item-demo',
      title: 'Проверить полку',
      instructions: 'Сделайте фото выкладки',
      taskType: 'photo_report',
      status: 'submitted',
      required: true,
      requiredPhotos: 1,
      checklist: [],
      version: 7,
      legacyEvidence: [],
      availableFrom: null,
      dueAt: null,
      canStart: false,
      canUpload: false,
      canSubmit: false,
      canReview: true,
      currentAttempt: {
        id: 'attempt-demo',
        number: 2,
        status: 'submitted',
        executorPersonId: 'person-demo',
        pharmacistId: 'ph-demo',
        submittedAt: '2026-10-29T00:00:00Z',
        createdAt: '2026-10-29T00:00:00Z',
        evidence: [],
        review: null,
        comment: 'Демо-комментарий',
        completedChecklistIds: [],
        executorIdentityStatus: 'verified_person',
      },
    },
  ],
}
let gateway: TaskGateway
beforeEach(() => {
  gateway = {
    realm: 'admin',
    get: vi.fn().mockResolvedValue(task),
    start: vi.fn(),
    upload: vi.fn(),
    submit: vi.fn(),
    review: vi.fn().mockResolvedValue(task),
    evidence: vi.fn(),
  }
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('explains exactly what blocks submission without exposing a write before readiness', async () => {
  const draft = structuredClone(task)
  draft.items[0].status = 'in_progress'
  draft.items[0].canSubmit = true
  draft.items[0].canReview = false
  draft.items[0].currentAttempt!.status = 'draft'
  draft.items[0].checklist = [
    { id: 'required-row', title: 'Проверить расположение', required: true },
  ]
  vi.mocked(gateway.get).mockResolvedValue(draft)
  render(<MerchTaskDetail id={task.id} gateway={gateway} />)
  expect(await screen.findByRole('heading', { name: 'Что нужно сделать' })).toBeVisible()
  expect(screen.getByText('Добавьте материалы: 0 из 1.')).toBeVisible()
  expect(screen.getByText('Отметьте обязательные пункты чек-листа.')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Отправить пункт на проверку' })).toBeDisabled()
  await userEvent.click(screen.getByRole('checkbox', { name: /Проверить расположение/ }))
  expect(screen.queryByText('Отметьте обязательные пункты чек-листа.')).not.toBeInTheDocument()
  expect(gateway.submit).not.toHaveBeenCalled()
})
describe('task item review and evidence boundary', () => {
  it('focuses a freshly authorized submitted item and advances after review without granting self-review', async () => {
    const own = { ...task.items[0], id: 'own-item', title: 'Собственная работа', canReview: false }
    const reviewable = { ...task.items[0], id: 'review-item', title: 'Работа фармацевта' }
    const next = { ...task.items[0], id: 'next-item', title: 'Следующая полка' }
    vi.mocked(gateway.get)
      .mockResolvedValueOnce({
        ...task,
        status: 'revision_required',
        itemCount: 3,
        pendingReviewCount: 3,
        nextPendingReviewItemId: own.id,
        items: [own, reviewable, next],
      })
      .mockResolvedValue({
        ...task,
        status: 'revision_required',
        itemCount: 3,
        pendingReviewCount: 2,
        nextPendingReviewItemId: own.id,
        items: [own, { ...reviewable, status: 'revision_required', canReview: false }, next],
      })
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview focusReview />)
    const ownRegion = await screen.findByRole('region', { name: 'Пункт: Собственная работа' })
    const reviewRegion = screen.getByRole('region', { name: 'Пункт: Работа фармацевта' })
    expect(
      within(ownRegion).queryByRole('button', { name: 'Принять пункт', exact: true }),
    ).not.toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toBe(reviewRegion))
    expect(screen.getByText('Следующий пункт: Работа фармацевта')).toBeInTheDocument()
    await userEvent.click(
      within(reviewRegion).getByRole('button', { name: 'Вернуть пункт на доработку' }),
    )
    await userEvent.type(screen.getByLabelText('Причина проверки пункта'), 'Нужен общий план полки')
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить решение' }))
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('region', { name: 'Пункт: Следующая полка' }),
      ),
    )
    expect(gateway.review).toHaveBeenCalledWith(
      'review-item',
      expect.objectContaining({ decision: 'revision', expectedItemVersion: 7 }),
    )
    expect(
      within(screen.getByRole('region', { name: 'Пункт: Собственная работа' })).queryByRole(
        'button',
        { name: 'Принять пункт', exact: true },
      ),
    ).not.toBeInTheDocument()
  })

  it('opens pending evidence without offering a decision when no item is authorized for review', async () => {
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      pendingReviewCount: 1,
      nextPendingReviewItemId: task.items[0].id,
      items: [{ ...task.items[0], canReview: false }],
    })
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview focusReview />)
    expect(
      await screen.findByRole('button', { name: 'Посмотреть непроверенный пункт' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Перейти к проверке следующего пункта' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Принять пункт', exact: true }),
    ).not.toBeInTheDocument()
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('region', { name: 'Пункт: Проверить полку' }),
      ),
    )
    expect(gateway.review).not.toHaveBeenCalled()
  })

  it('shows each immutable staff submission name in the current attempt and history without claiming a verified person', async () => {
    const current = {
      ...task.items[0].currentAttempt!,
      executorPersonId: null,
      pharmacistId: null,
      executorIdentityStatus: 'staff_access_unverified',
      staffSubmittedName: 'Автор второй сдачи',
    }
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      rewardAmountKzt: 0,
      items: [{ ...task.items[0], currentAttempt: current }],
    })
    gateway.history = vi.fn().mockResolvedValue({
      items: [
        {
          ...current,
          id: 'old-attempt',
          number: 1,
          staffSubmittedName: 'Автор первой сдачи',
          status: 'revision_required',
        },
      ],
      hasMore: false,
      limit: 20,
      offset: 0,
    })
    render(<MerchTaskDetail id={task.id} gateway={gateway} />)
    expect(
      await screen.findByText('Подпись при сдаче: Автор второй сдачи. Личность не подтверждена.'),
    ).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'История попыток' }))
    expect(
      await screen.findByText('Подпись при сдаче: Автор первой сдачи. Личность не подтверждена.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Подпись при сдаче: Автор второй сдачи. Личность не подтверждена.'),
    ).toBeInTheDocument()
    expect(gateway.history).toHaveBeenCalledExactlyOnceWith('item-demo', 0, expect.any(AbortSignal))
  })
  it('cancels only the selected item version with a reason and never invokes review or packet cancellation', async () => {
    gateway.cancelItem = vi.fn().mockResolvedValue(task)
    gateway.cancel = vi.fn()
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      items: task.items.map((item) => ({ ...item, canCancel: true })),
    })
    render(<MerchTaskDetail id={task.id} gateway={gateway} canCancel />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Отменить этот пункт' }))
    expect(screen.getByRole('button', { name: 'Подтвердить отмену пункта' })).toBeDisabled()
    await user.type(screen.getByLabelText('Причина отмены пункта'), 'Полка временно недоступна')
    await user.click(screen.getByRole('button', { name: 'Подтвердить отмену пункта' }))
    await waitFor(() =>
      expect(gateway.cancelItem).toHaveBeenCalledWith('item-demo', 7, 'Полка временно недоступна'),
    )
    expect(gateway.cancel).not.toHaveBeenCalled()
    expect(gateway.review).not.toHaveBeenCalled()
  })
  it('reviews only the selected attempt/version with a mandatory reason and explicit confirmation', async () => {
    const user = userEvent.setup()
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview />)
    await user.click(await screen.findByRole('button', { name: 'Вернуть пункт на доработку' }))
    expect(screen.getByRole('button', { name: 'Подтвердить решение' })).toBeDisabled()
    expect(gateway.review).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Причина проверки пункта'), 'Нужен общий план полки')
    await user.click(screen.getByRole('button', { name: 'Подтвердить решение' }))
    await waitFor(() =>
      expect(gateway.review).toHaveBeenCalledExactlyOnceWith('item-demo', {
        attemptId: 'attempt-demo',
        expectedItemVersion: 7,
        decision: 'revision',
        reason: 'Нужен общий план полки',
      }),
    )
    expect(screen.getByText(/Предусмотрено: 400/)).toBeInTheDocument()
    expect(screen.queryByText(/Начислено в кошелёк/)).not.toBeInTheDocument()
  })
  it('does not offer self-review or permission escalation when the server leaf flag is false', async () => {
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      items: [{ ...task.items[0], canReview: false }],
    })
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview />)
    await screen.findByText('Проверить полку')
    expect(screen.queryByRole('button', { name: 'Принять пункт' })).not.toBeInTheDocument()
    expect(gateway.review).not.toHaveBeenCalled()
  })
  it.each(['capability_unverified', 'staff_access_unverified'])(
    'requires explicit HQ acknowledgement for %s; it is never defaulted true',
    async (identityStatus) => {
      vi.mocked(gateway.get).mockResolvedValue({
        ...task,
        rewardAmountKzt: 0,
        items: [
          {
            ...task.items[0],
            currentAttempt: {
              ...task.items[0].currentAttempt!,
              executorPersonId: null,
              pharmacistId: null,
              executorIdentityStatus: identityStatus,
            },
          },
        ],
      })
      const user = userEvent.setup()
      render(<MerchTaskDetail id={task.id} gateway={gateway} canReview />)
      await user.click(await screen.findByRole('button', { name: 'Принять пункт' }))
      await user.type(screen.getByLabelText('Причина проверки пункта'), 'Проверено вручную')
      expect(screen.getByRole('button', { name: 'Подтвердить решение' })).toBeDisabled()
      const acknowledgement = screen.getByRole('checkbox', {
        name: /личность исполнителя не подтверждена/,
      })
      expect(acknowledgement).not.toBeChecked()
      await user.click(acknowledgement)
      await user.click(screen.getByRole('button', { name: 'Подтвердить решение' }))
      await waitFor(() =>
        expect(gateway.review).toHaveBeenCalledWith(
          'item-demo',
          expect.objectContaining({ manualUnverifiedExecutor: true, reason: 'Проверено вручную' }),
        ),
      )
    },
  )
  it.each(['image/png', 'image/webp'])(
    'legacy %s photos preview but cannot satisfy the current attempt minimum',
    async (mimeType) => {
      const evidence = {
        id: 'legacy-photo',
        mimeType,
        byteSize: 10,
        sha256: null,
        originalFilename: null,
        createdAt: '2026-10-29T00:00:00Z',
        verification: 'manual_review_required',
      }
      vi.mocked(gateway.evidence).mockResolvedValue(new Blob(['fixture'], { type: mimeType }))
      const NativeUrl = URL
      vi.stubGlobal(
        'URL',
        class extends NativeUrl {
          static createObjectURL() {
            return 'blob:synthetic-only'
          }
          static revokeObjectURL() {}
        },
      )
      vi.mocked(gateway.get).mockResolvedValue({
        ...task,
        items: [
          {
            ...task.items[0],
            status: 'in_progress',
            canSubmit: true,
            canReview: false,
            legacyEvidence: [evidence],
            currentAttempt: { ...task.items[0].currentAttempt!, status: 'draft', evidence: [] },
          },
        ],
      })
      render(<MerchTaskDetail id={task.id} gateway={gateway} />)
      expect(await screen.findByRole('img')).toHaveAttribute('src', 'blob:synthetic-only')
      expect(
        await screen.findByRole('button', { name: 'Отправить пункт на проверку' }),
      ).toBeDisabled()
      expect(gateway.submit).not.toHaveBeenCalled()
    },
  )
  it('does not adopt an imported executor; explicit HQ source continuation uses its current version and reason', async () => {
    gateway.continueSource = vi.fn().mockResolvedValue({ mode: 'resume' })
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      sourceSystem: 'MERCH_CRM',
      items: [
        {
          ...task.items[0],
          currentAttempt: null,
          canReview: false,
          canResumeSource: true,
          canAdoptSourceReview: false,
        },
      ],
    })
    const user = userEvent.setup()
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview />)
    await user.click(await screen.findByRole('button', { name: 'Запросить новую попытку' }))
    expect(screen.getByRole('button', { name: 'Подтвердить продолжение' })).toBeDisabled()
    expect(gateway.continueSource).not.toHaveBeenCalled()
    await user.type(
      screen.getByLabelText('Основание продолжения исторического задания'),
      'Сотруднику нужно переснять полку',
    )
    await user.click(screen.getByRole('button', { name: 'Подтвердить продолжение' }))
    await waitFor(() =>
      expect(gateway.continueSource).toHaveBeenCalledExactlyOnceWith('item-demo', {
        expectedItemVersion: 7,
        mode: 'resume',
        reason: 'Сотруднику нужно переснять полку',
      }),
    )
    expect(gateway.start).not.toHaveBeenCalled()
  })
  it('requires separate acknowledgement for preserved source reports without using the free-link override', async () => {
    vi.mocked(gateway.get).mockResolvedValue({
      ...task,
      items: [
        {
          ...task.items[0],
          currentAttempt: {
            ...task.items[0].currentAttempt!,
            executorPersonId: null,
            pharmacistId: null,
            executorIdentityStatus: 'source_preserved_unverified',
          },
        },
      ],
    })
    const user = userEvent.setup()
    render(<MerchTaskDetail id={task.id} gateway={gateway} canReview />)
    await user.click(await screen.findByRole('button', { name: 'Принять пункт' }))
    await user.type(screen.getByLabelText('Причина проверки пункта'), 'Проверено историческое фото')
    expect(screen.getByRole('button', { name: 'Подтвердить решение' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: /сохранённый исторический отчёт/ }))
    await user.click(screen.getByRole('button', { name: 'Подтвердить решение' }))
    await waitFor(() =>
      expect(gateway.review).toHaveBeenCalledExactlyOnceWith('item-demo', {
        attemptId: 'attempt-demo',
        expectedItemVersion: 7,
        decision: 'approve',
        reason: 'Проверено историческое фото',
        manualSourcePreserved: true,
      }),
    )
  })
})
