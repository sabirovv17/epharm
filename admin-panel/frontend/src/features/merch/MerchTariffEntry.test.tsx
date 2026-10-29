import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MerchPlacementsPage from './MerchPlacementsPage'

const access = vi.hoisted(() => ({
  canViewMerchPlacements: true,
  canViewMerchTariffs: false,
  isPending: false,
  isFetching: false,
  isFetchedAfterMount: true,
  data: { version: 1 },
  refetch: vi.fn(),
}))
vi.mock('@/app/useAdministrativeCapabilities', () => ({
  useAdministrativeCapabilities: () => access,
}))
vi.mock('./PlacementWorkspace', () => ({ default: () => <div>Native placements</div> }))
vi.mock('./PricingEditor', () => ({ default: () => <div>Fresh tariff editor</div> }))
afterEach(() => {
  cleanup()
  access.canViewMerchTariffs = false
})

describe('tariff entry in placement workspace', () => {
  it('does not show a financial action to a scoped placement viewer', () => {
    render(<MerchPlacementsPage />)
    expect(screen.queryByRole('button', { name: 'Тарифы поставщиков' })).not.toBeInTheDocument()
  })
  it('opens only on a fresh server capability and removes it when the capability is withdrawn', async () => {
    const user = userEvent.setup()
    access.canViewMerchTariffs = true
    const mounted = render(<MerchPlacementsPage />)
    await user.click(screen.getByRole('button', { name: 'Тарифы поставщиков' }))
    expect(screen.getByText('Fresh tariff editor')).toBeInTheDocument()
    access.canViewMerchTariffs = false
    mounted.rerender(<MerchPlacementsPage />)
    expect(screen.queryByText('Fresh tariff editor')).not.toBeInTheDocument()
  })
})
