import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ProducerCommercialWorkspace from './ProducerCommercialWorkspace'
import type {
  CommercialGateway,
  CommercialPlan,
  CommercialWorkspace,
} from './producer-commercial-api'
import type { PlacementGateway } from './placements-api'

const original: CommercialPlan = {
  id: 'original-plan',
  producerId: 'customer-a',
  revision: 7,
  plan: {
    title: 'Сохранённый исходный бюджет',
    startMonth: '2027-01-01',
    endMonth: '2027-02-01',
    budgetLimit: null,
    status: 'draft',
    notes: 'Сохранённый комментарий',
    planningMode: 'products',
    productBriefs: [],
    shelfSelection: [],
    extraActivities: [],
    items: [],
  },
  requestIds: [],
  monthlyRequests: [],
  copiedFrom: null,
  estimatedTotal: null,
  estimateState: 'hidden',
  canEdit: false,
  canSubmit: false,
  canExport: false,
  updatedAt: '2026-10-29T10:00:00Z',
  paymentStatus: 'not_connected',
}
function fixtures(realm: 'producer' | 'admin' = 'producer', plans: CommercialPlan[] = []) {
  const workspace: CommercialWorkspace = {
    settings: {
      revision: 2,
      supplierCategory: null,
      sixMonthBudget: null,
      primaryContactRole: null,
    },
    pricing: null,
    members: [
      {
        id: 'member-a',
        revision: 3,
        fullName: 'Синтетический сотрудник',
        email: 'synthetic@example.test',
        position: 'Планирование',
        accessRole: 'planner',
        active: true,
      },
    ],
    plans: { items: plans, total: plans.length, limit: 50, offset: 0 },
  }
  const gateway = {
    realm,
    workspace: vi.fn().mockResolvedValue(workspace),
    list: vi.fn().mockResolvedValue(workspace.plans),
    view: vi.fn().mockResolvedValue(original),
    save: vi.fn(),
    submit: vi.fn(),
    copy: vi.fn(),
    preview: vi.fn(),
    settings: vi.fn(),
    member: vi.fn().mockResolvedValue({}),
    categorySettings: vi
      .fn()
      .mockResolvedValue({ settings: workspace.settings, canConfigureCategory: false }),
    pricing: vi.fn().mockResolvedValue([]),
    setCategory: vi.fn(),
    download: vi.fn(),
  } satisfies CommercialGateway
  const placements = {
    realm,
    producers: vi
      .fn()
      .mockResolvedValue([{ id: 'customer-a', name: 'Синтетическая компания', active: true }]),
    catalog: vi.fn().mockResolvedValue({ branches: [], total: 0, limit: 50, offset: 0 }),
  } as unknown as PlacementGateway
  return { gateway, placements }
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('producer commercial workspace', () => {
  it('preserves unsaved company settings and team member fields across section navigation', async () => {
    const { gateway, placements } = fixtures()
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    const company = screen.getByRole('button', { name: 'Команда и условия' })
    await user.click(company)
    await user.type(
      await screen.findByRole('textbox', { name: 'Роль основного контакта' }),
      'Несохранённая роль',
    )
    await user.type(screen.getByRole('textbox', { name: 'Имя участника' }), 'Новый участник')
    await user.click(screen.getByRole('button', { name: 'Планы и бюджет' }))
    expect(company).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByLabelText('Имя участника')).not.toBeVisible()
    await user.click(company)
    expect(screen.getByRole('textbox', { name: 'Роль основного контакта' })).toHaveValue(
      'Несохранённая роль',
    )
    expect(screen.getByRole('textbox', { name: 'Имя участника' })).toHaveValue('Новый участник')
    expect(gateway.member).not.toHaveBeenCalled()
    expect(gateway.settings).not.toHaveBeenCalled()
  })

  it('does not request a supplier team or expose its private contact metadata in the administrator realm', async () => {
    const { gateway, placements } = fixtures('admin')
    render(<ProducerCommercialWorkspace gateway={gateway} placements={placements} />)
    await screen.findByText('Сохранённых планов нет.')
    expect(gateway.workspace).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Команда и условия' })).not.toBeInTheDocument()
    expect(screen.queryByText('synthetic@example.test')).not.toBeInTheDocument()
    expect(gateway.pricing).not.toHaveBeenCalled()
  })

  it('retains the same draft ID and unsaved input after a CAS failure, without storing the business draft', async () => {
    const { gateway, placements } = fixtures()
    gateway.save.mockRejectedValue({
      status: 409,
      message: 'План изменился: загрузите актуальную версию',
    })
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Новый план на период' }))
    await user.type(
      screen.getByRole('textbox', { name: 'Название плана' }),
      'Непотерянный синтетический черновик',
    )
    await user.clear(screen.getByRole('textbox', { name: 'Лимит бюджета, ₸' }))
    await user.type(screen.getByRole('textbox', { name: 'Лимит бюджета, ₸' }), '9007199254740993')
    await user.click(screen.getByRole('button', { name: 'Сохранить черновик плана' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('План изменился')
    expect(screen.getByRole('textbox', { name: 'Название плана' })).toHaveValue(
      'Непотерянный синтетический черновик',
    )
    await user.click(screen.getByRole('button', { name: 'Сохранить черновик плана' }))
    await waitFor(() => expect(gateway.save).toHaveBeenCalledTimes(2))
    expect(gateway.save.mock.calls[0][0]).toBe(gateway.save.mock.calls[1][0])
    expect(gateway.save.mock.calls[0][2]).toBe(0)
    expect(gateway.save.mock.calls[0][3].budgetLimit).toBe('9007199254740993')
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('Непотерянный')
  })

  it('uses the server capabilities and keeps unknown budget and estimate values explicit', async () => {
    const { gateway, placements } = fixtures('producer', [original])
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Открыть план' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText(
      'Предварительная оценка: Не подтверждено. Бюджет: Не подтверждено.',
    )
    expect(
      within(dialog).queryByRole('button', { name: 'Продолжить черновик' }),
    ).not.toBeInTheDocument()
    expect(
      within(dialog).queryByRole('button', { name: 'Отправить весь период на подтверждение' }),
    ).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Медиакит Excel' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Создать отдельную копию' })).toBeDisabled()
    expect(gateway.submit).not.toHaveBeenCalled()
    expect(gateway.download).not.toHaveBeenCalled()
  })

  it('shows every CSV validation error and cannot transfer or save partially invalid rows', async () => {
    const { gateway, placements } = fixtures()
    gateway.preview.mockResolvedValue({
      valid: false,
      rows: [
        { line: 2, branchCode: 'SYNTH', shelfCode: 'S1', month: '2027-01-01', error: null },
        {
          line: 3,
          branchCode: 'FOREIGN',
          shelfCode: 'S2',
          month: null,
          error: 'Аптека или место не найдены либо недоступны',
        },
      ],
      placements: [],
    })
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Новый план на период' }))
    await user.click(screen.getByText('Импорт мест из CSV с проверкой строк'))
    await user.type(
      screen.getByRole('textbox', { name: 'CSV мест' }),
      'branch_code;shelf_code;month\nSYNTH;S1;2027-01\nFOREIGN;S2;bad',
    )
    await user.click(screen.getByRole('button', { name: 'Проверить CSV' }))
    expect(await screen.findByText(/Строка 3: FOREIGN/)).toHaveTextContent('недоступны')
    expect(
      screen.getByRole('button', { name: 'Перенести проверенные строки в черновик' }),
    ).toBeDisabled()
    expect(gateway.save).not.toHaveBeenCalled()
    expect(gateway.submit).not.toHaveBeenCalled()
  })

  it('shows unresolved historical references without making a missing request look paid or available to resubmit', async () => {
    const legacy = {
      ...original,
      requestIds: ['preserved-missing-id'],
      unresolvedRequestIds: ['preserved-missing-id'],
    }
    const { gateway, placements } = fixtures('producer', [legacy])
    gateway.view.mockResolvedValue(legacy)
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    expect(await screen.findByText('Не подтверждено связей: 1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Открыть план' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('status')).toHaveTextContent(
      'повторная отправка запрещена',
    )
    expect(
      within(dialog).queryByRole('button', { name: 'Отправить весь период на подтверждение' }),
    ).not.toBeInTheDocument()
    expect(within(dialog).queryByText('Оплачено')).not.toBeInTheDocument()
  })

  it('archives a team record as metadata with its original ID and revision, without creating a login or person', async () => {
    const { gateway, placements } = fixtures()
    const user = userEvent.setup()
    render(
      <ProducerCommercialWorkspace
        gateway={gateway}
        placements={placements}
        ownProducerId="customer-a"
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Команда и условия' }))
    await user.click(await screen.findByRole('button', { name: 'Изменить участника' }))
    expect(screen.getByText(/не создаёт отдельный вход/)).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /Участник активен/ }))
    await user.click(screen.getByRole('button', { name: 'Сохранить участника' }))
    await waitFor(() =>
      expect(gateway.member).toHaveBeenCalledWith(
        expect.objectContaining({
          id: 'member-a',
          revision: 3,
          active: false,
          email: 'synthetic@example.test',
        }),
      ),
    )
    expect(gateway.settings).not.toHaveBeenCalled()
    expect(gateway.setCategory).not.toHaveBeenCalled()
    expect(gateway.save).not.toHaveBeenCalled()
  })
})
