import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import StaffVisitReport from './StaffVisitReport'
import type { StaffClient, StaffVisit } from './staff-access-api'
const visit: StaffVisit = {
  id: 'visit',
  branchId: 'branch',
  month: '2099-01-01',
  employeeName: 'Демо-автор',
  notes: 'Сохранённый комментарий',
  submittedAt: null,
  reason: null,
  identityStatus: 'staff_access_unverified',
  canEdit: true,
  deadlineAt: null,
  version: 23,
  dispatch: {
    id: 'dispatch',
    branchId: 'branch',
    title: 'Общий месячный фотоотчёт',
    status: 'in_progress',
    version: 23,
    dueAt: null,
    sourceSystem: 'EPHARM',
    rewardAmountKzt: 0,
    reward: null,
    itemCount: 1,
    detailsIncluded: true,
    priority: 'normal',
    taskType: 'staff_monthly_visit',
    items: [
      {
        id: 'item',
        title: 'Полка',
        instructions: 'Сфотографируйте полку',
        status: 'in_progress',
        taskType: 'staff_monthly_visit',
        required: true,
        requiredPhotos: 1,
        checklist: [],
        version: 4,
        legacyEvidence: [],
        dueAt: null,
        availableFrom: null,
        canUpload: true,
        canStart: false,
        canSubmit: true,
        currentAttempt: {
          id: 'attempt',
          number: 1,
          status: 'in_progress',
          executorPersonId: null,
          pharmacistId: null,
          createdAt: '2099-01-01T00:00:00Z',
          submittedAt: null,
          evidence: [
            {
              id: 'photo',
              mimeType: 'image/png',
              byteSize: 10,
              sha256: 'hash',
              originalFilename: null,
              createdAt: '2099-01-01T00:00:00Z',
              verification: 'unverified',
            },
          ],
          review: null,
          executorIdentityStatus: 'staff_access_unverified',
        },
      },
    ],
  },
  places: [
    {
      taskItemId: 'item',
      placementItemId: 'placement',
      shelfId: 'shelf',
      shelfCode: 'A-1',
      portion: 'left',
      category: 'A',
      width: 1,
      advertiserName: 'Демо-производитель',
      referenceAvailable: true,
    },
  ],
}
let client: StaffClient
beforeEach(() => {
  client = {
    visit: vi.fn().mockResolvedValue(visit),
    open: vi.fn().mockResolvedValue(visit),
    upload: vi.fn(),
    reference: vi.fn(),
    evidence: vi.fn(),
    submit: vi.fn().mockResolvedValue(visit),
  } as unknown as StaffClient
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
it('submits one whole report with current server CAS and author label, never individual item requests', async () => {
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly={false}
      onBack={vi.fn()}
    />,
  )
  const user = userEvent.setup()
  const submit = await screen.findByRole('button', { name: 'Отправить весь отчёт на проверку' })
  expect(screen.getByText('A · 1 м · Демо-производитель')).toBeInTheDocument()
  await user.click(submit)
  await waitFor(() =>
    expect(client.submit).toHaveBeenCalledExactlyOnceWith(
      'visit',
      23,
      'Демо-автор',
      'Сохранённый комментарий',
    ),
  )
  expect(client.upload).not.toHaveBeenCalled()
  expect(screen.getByText(/Личность общего доступа не подтверждена/)).toBeInTheDocument()
})
it('returned old photos remain readable but cannot satisfy current server readiness', async () => {
  vi.mocked(client.visit).mockResolvedValue({
    ...visit,
    dispatch: {
      ...visit.dispatch,
      items: [{ ...visit.dispatch.items[0], status: 'revision_required', canSubmit: false }],
    },
  })
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly={false}
      onBack={vi.fn()}
    />,
  )
  expect(await screen.findByText(/Снимки возвращённой попытки сохранены/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Отправить весь отчёт на проверку' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Снимок 1' })).toBeInTheDocument()
  expect(client.submit).not.toHaveBeenCalled()
})
it('one retaken item can be resubmitted without requiring a second photo for an already submitted sibling', async () => {
  vi.mocked(client.visit).mockResolvedValue({
    ...visit,
    dispatch: {
      ...visit.dispatch,
      items: [
        visit.dispatch.items[0],
        {
          ...visit.dispatch.items[0],
          id: 'already-submitted',
          status: 'submitted',
          canSubmit: false,
          canUpload: false,
          canStart: false,
        },
      ],
    },
  })
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly={false}
      onBack={vi.fn()}
    />,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Отправить весь отчёт на проверку' }))
  await waitFor(() =>
    expect(client.submit).toHaveBeenCalledExactlyOnceWith(
      'visit',
      23,
      'Демо-автор',
      'Сохранённый комментарий',
    ),
  )
  expect(client.upload).not.toHaveBeenCalled()
})
it('readonly deadline removes camera and whole-submit writers despite stale item flags', async () => {
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly
      onBack={vi.fn()}
    />,
  )
  expect(await screen.findByText(/только для просмотра/)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Сделать новый снимок' })).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: 'Отправить весь отчёт на проверку' }),
  ).not.toBeInTheDocument()
})
it('opens camera only on explicit user request and keeps a permission failure unsubmitted', async () => {
  const camera = vi.fn().mockRejectedValue(new Error('denied'))
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: camera },
  })
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly={false}
      onBack={vi.fn()}
    />,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Сделать новый снимок' }))
  expect(camera).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Включить камеру' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось открыть камеру')
  expect(client.upload).not.toHaveBeenCalled()
})
it('a camera photo has a real preview, stops its stream and retries the same upload intent after network failure', async () => {
  const stop = vi.fn()
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop }] }) },
  })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  vi.spyOn(HTMLVideoElement.prototype, 'videoWidth', 'get').mockReturnValue(640)
  vi.spyOn(HTMLVideoElement.prototype, 'videoHeight', 'get').mockReturnValue(480)
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((done) =>
    done(new Blob(['synthetic-camera-bytes'], { type: 'image/jpeg' })),
  )
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:synthetic-camera')
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.mocked(client.upload)
    .mockRejectedValueOnce(new Error('Сеть недоступна'))
    .mockResolvedValueOnce({ id: 'photo' } as never)
  render(
    <StaffVisitReport
      client={client}
      id="visit"
      initialEmployeeName="Демо-автор"
      readOnly={false}
      onBack={vi.fn()}
    />,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Сделать новый снимок' }))
  await user.click(screen.getByRole('button', { name: 'Включить камеру' }))
  await user.click(await screen.findByRole('button', { name: 'Сделать снимок' }))
  expect(await screen.findByRole('img', { name: 'Предпросмотр снимка' })).toHaveAttribute(
    'src',
    'blob:synthetic-camera',
  )
  expect(stop).toHaveBeenCalledOnce()
  await user.click(screen.getByRole('button', { name: 'Сохранить снимок' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Сеть недоступна')
  await user.click(screen.getByRole('button', { name: 'Сохранить снимок' }))
  await waitFor(() => expect(client.upload).toHaveBeenCalledTimes(2))
  expect(vi.mocked(client.upload).mock.calls[0][2]).toBe(vi.mocked(client.upload).mock.calls[1][2])
  expect(vi.mocked(client.upload).mock.calls[0][3]).toBe(vi.mocked(client.upload).mock.calls[1][3])
  expect(vi.mocked(client.upload).mock.calls[0][4]).toEqual(
    expect.objectContaining({ captureSource: 'web_camera', status: 'unavailable' }),
  )
  await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:synthetic-camera'))
})
