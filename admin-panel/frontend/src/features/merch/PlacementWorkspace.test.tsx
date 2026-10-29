import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PlacementWorkspace from './PlacementWorkspace'
import type { Placement, PlacementGateway } from './placements-api'

const placement: Placement = {
  id: 'demo-plan',
  producerId: 'producer-01',
  producerName: 'Демо-производитель',
  advertiserName: 'Демо',
  placementMonth: '2026-11-01',
  status: 'draft',
  origin: 'request',
  revision: 4,
  totalAmount: null,
  heldUntil: null,
  reservationState: 'closed',
  reservationBusinessDays: 3,
  reservationTimezone: 'Asia/Almaty',
  reviewNote: null,
  note: null,
  items: [
    {
      id: 'row-1',
      shelfId: 'shelf-01',
      branchId: 'branch-01',
      shelfCode: 'S-01',
      placementMonth: '2026-11-01',
      portion: 'whole',
      status: 'draft',
      monthlyPrice: null,
    },
  ],
  canEdit: true,
  canSubmit: true,
  canApprove: false,
  canReject: false,
  canCancel: true,
  createdAt: '2026-10-29T00:00:00Z',
  updatedAt: '2026-10-29T00:00:00Z',
  producerBindingStatus: 'verified',
}
let gateway: PlacementGateway
beforeEach(() => {
  gateway = {
    realm: 'admin',
    producers: vi
      .fn()
      .mockResolvedValue([{ id: 'producer-01', name: 'Демо-производитель', active: true }]),
    list: vi.fn().mockResolvedValue({ items: [placement], total: 1, limit: 50, offset: 0 }),
    view: vi.fn().mockResolvedValue(placement),
    create: vi.fn().mockResolvedValue(placement),
    save: vi.fn().mockResolvedValue(placement),
    transition: vi.fn().mockResolvedValue(placement),
    catalog: vi.fn().mockResolvedValue({
      producerId: 'producer-01',
      producerName: 'Демо-производитель',
      month: '2026-11-01',
      total: 1,
      limit: 50,
      offset: 0,
      canCreate: true,
      canAssign: false,
      branches: [
        {
          id: 'branch-01',
          name: 'Демо-аптека',
          address: 'Демо, 1',
          city: 'Демо',
          canCreate: true,
          canAssign: false,
          canViewPrices: false,
          items: [
            {
              shelfId: 'shelf-01',
              code: 'S-01',
              saleMode: 'halves',
              whole: { price: null, available: true },
              left: { price: null, available: true },
              right: { price: null, available: false },
            },
          ],
        },
      ],
    }),
  }
})
describe('native placement workflow', () => {
  it('creates a non-binding draft with server-selected places and no invented price', async () => {
    const user = userEvent.setup()
    render(<PlacementWorkspace gateway={gateway} />)
    await user.click(await screen.findByRole('button', { name: 'Новый медиаплан' }))
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Производитель медиаплана' }),
      'producer-01',
    )
    await screen.findByRole('option', { name: /Демо-аптека/ })
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Аптека для размещения' }),
      'branch-01',
    )
    await user.selectOptions(screen.getByRole('combobox', { name: 'Часть места S-01' }), 'whole')
    expect(screen.queryByRole('option', { name: /Правая половина/ })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Служебное размещение/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Сохранить черновик' }))
    await waitFor(() =>
      expect(gateway.create).toHaveBeenCalledWith(
        {
          producerId: 'producer-01',
          placementMonth: expect.stringMatching(/^\d{4}-\d{2}-01$/),
          items: [{ shelfId: 'shelf-01', portion: 'whole' }],
          note: null,
        },
        false,
      ),
    )
    expect(gateway.transition).not.toHaveBeenCalled()
    expect(await screen.findByText('Сумма по данным сервера: —')).toBeInTheDocument()
  })
  it('sends the fetched CAS revision only after explicit confirmation and never retries a conflict', async () => {
    vi.mocked(gateway.transition).mockRejectedValue({ status: 409 })
    const user = userEvent.setup()
    render(<PlacementWorkspace gateway={gateway} />)
    await user.click(await screen.findByRole('button', { name: 'Открыть' }))
    await user.click(await screen.findByRole('button', { name: 'Отправить на подтверждение' }))
    expect(gateway.transition).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Данные или цены изменились')
    expect(gateway.transition).toHaveBeenCalledExactlyOnceWith('demo-plan', 'submit', 4, null)
    expect(screen.getByRole('button', { name: 'Отправить на подтверждение' })).toBeDisabled()
    expect(gateway.view).toHaveBeenCalledTimes(1)
  })
  it('external realm never shows approval or customer selectors even if an invalid DTO claims HQ actions', async () => {
    gateway.realm = 'producer'
    vi.mocked(gateway.view).mockResolvedValue({ ...placement, canApprove: true, canReject: true })
    const user = userEvent.setup()
    render(<PlacementWorkspace gateway={gateway} customerName="Моя компания" />)
    expect(screen.queryByRole('combobox', { name: 'Фильтр производителя' })).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Открыть' }))
    await screen.findByText('Сумма по данным сервера: —')
    expect(screen.queryByRole('button', { name: 'Подтвердить размещение' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Отклонить' })).not.toBeInTheDocument()
  })
  it('retains cancelled item history and leaves an unresolved legacy producer uneditable', async () => {
    vi.mocked(gateway.view).mockResolvedValue({
      ...placement,
      producerId: null,
      producerName: null,
      producerBindingStatus: 'unresolved',
      canEdit: true,
      canSubmit: true,
      canCancel: false,
      items: [{ ...placement.items[0], status: 'cancelled' }],
    })
    const user = userEvent.setup()
    render(<PlacementWorkspace gateway={gateway} />)
    await user.click(await screen.findByRole('button', { name: 'Открыть' }))
    expect(await screen.findByText(/У исторического медиаплана не подтверждён/)).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Отменено' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Изменить черновик' })).not.toBeInTheDocument()
  })
})
