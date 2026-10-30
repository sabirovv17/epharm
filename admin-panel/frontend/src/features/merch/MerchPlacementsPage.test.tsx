import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MerchPlacementsPage from './MerchPlacementsPage'

const access = vi.hoisted(() => ({
  canViewMerchPlacements: true,
  canViewMerchTariffs: true,
  isPending: false,
  isFetching: false,
  isFetchedAfterMount: true,
  isError: false,
  data: { version: 1 },
  refetch: vi.fn(),
}))
vi.mock('@/app/useAdministrativeCapabilities', () => ({
  useAdministrativeCapabilities: () => access,
}))
vi.mock('./PlacementWorkspace', () => ({
  default: () => <input aria-label="Черновик размещения" />,
}))
vi.mock('./ProducerCommercialWorkspace', () => ({
  default: () => <input aria-label="Черновик бюджета" />,
}))
vi.mock('./TaskInstructionPanel', () => ({
  default: () => <input aria-label="Черновик инструктажа" />,
}))
vi.mock('./PricingEditor', () => ({ default: () => <input aria-label="Черновик тарифа" /> }))

beforeEach(() => {
  Object.assign(access, { canViewMerchPlacements: true, canViewMerchTariffs: true, isError: false })
  access.refetch.mockClear()
})
afterEach(cleanup)

describe('placement section navigation', () => {
  it('shows one section and preserves visited drafts without eagerly mounting other workspaces', async () => {
    const user = userEvent.setup()
    render(<MerchPlacementsPage />)
    expect(screen.queryByLabelText('Черновик бюджета')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Черновик размещения'), 'Несохранённое место')
    const budget = screen.getByRole('button', { name: 'Планы на период и бюджет' })
    await user.click(budget)
    expect(budget).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById(budget.getAttribute('aria-controls')!)).toBeVisible()
    expect(screen.getByLabelText('Черновик размещения')).not.toBeVisible()
    await user.type(screen.getByLabelText('Черновик бюджета'), 'Несохранённый бюджет')
    await user.click(screen.getByRole('button', { name: 'Инструктажи по размещениям' }))
    expect(screen.getByLabelText('Черновик бюджета')).not.toBeVisible()
    await user.click(budget)
    expect(screen.getByLabelText('Черновик бюджета')).toHaveValue('Несохранённый бюджет')
    await user.click(screen.getByRole('button', { name: 'Медиапланы' }))
    expect(screen.getByLabelText('Черновик размещения')).toHaveValue('Несохранённое место')
    expect(budget).toHaveAttribute('aria-expanded', 'false')
  })

  it('distinguishes a failed capability check from an explicit denial and keeps workspaces hidden', async () => {
    access.isError = true
    access.canViewMerchPlacements = false
    const user = userEvent.setup()
    const mounted = render(<MerchPlacementsPage />)
    expect(screen.getByRole('alert')).toHaveTextContent('Не удалось проверить доступ')
    expect(screen.queryByLabelText('Черновик размещения')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Повторить проверку' }))
    expect(access.refetch).toHaveBeenCalledOnce()
    access.isError = false
    mounted.rerender(<MerchPlacementsPage />)
    expect(screen.getByRole('heading', { name: 'Нет доступа к размещениям' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Повторить проверку' })).not.toBeInTheDocument()
  })

  it('removes a revoked tariff workspace and returns to the permitted placements section', async () => {
    const user = userEvent.setup()
    const mounted = render(<MerchPlacementsPage />)
    await user.click(screen.getByRole('button', { name: 'Тарифы поставщиков' }))
    await user.type(screen.getByLabelText('Черновик тарифа'), 'Частный тариф')
    access.canViewMerchTariffs = false
    mounted.rerender(<MerchPlacementsPage />)
    expect(screen.queryByLabelText('Черновик тарифа')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Черновик размещения')).toBeVisible()
  })
})
