import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { USERS } from '@/mocks/fixtures'
import { CommandPalette } from './CommandPalette'

const pharmacyHooks = vi.hoisted(() => ({
  usePharmacies: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

describe('CommandPalette — глобальный поиск', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pharmacyHooks.usePharmacies.mockReturnValue({
      data: [
        {
          id: 'ph-gagarina-137',
          name: 'Аспект-траст г.Алматы',
          chainId: 'chain-1',
          chainName: 'Аспект-траст',
          city: 'Алматы',
          district: 'Бостандыкский',
          addr: 'Гагарина 137',
          group: 'pilot',
          pharmacists: 0,
          receipts30d: 0,
          gmv30d: null,
          liftPct: 0,
          rulesAccepted: 0,
          active: true,
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:00Z',
        },
      ],
      isFetching: false,
      isError: false,
    })
  })

  it('находит аптеку по адресу и открывает её карточку', async () => {
    const onOpenPharmacy = vi.fn()
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(
      <CommandPalette
        user={USERS.bauyrzhan}
        onNav={vi.fn()}
        onClose={onClose}
        onOpenPharmacy={onOpenPharmacy}
      />,
    )

    await user.type(screen.getByPlaceholderText('Раздел или аптека…'), 'Гагарина')

    expect(pharmacyHooks.usePharmacies).toHaveBeenLastCalledWith({}, true)
    expect(screen.getByText('Гагарина 137', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Аптека')).toBeInTheDocument()

    await user.click(screen.getByText('Аспект-траст г.Алматы'))
    expect(onOpenPharmacy).toHaveBeenCalledWith('ph-gagarina-137')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('не запрашивает аптеки до двух знаков', async () => {
    const user = userEvent.setup()
    render(
      <CommandPalette user={USERS.bauyrzhan} onNav={vi.fn()} onClose={vi.fn()} />,
    )

    await user.type(screen.getByPlaceholderText('Раздел или аптека…'), 'Г')
    expect(pharmacyHooks.usePharmacies).toHaveBeenLastCalledWith({}, false)
  })

  it('does not present an API failure as an empty search result', async () => {
    pharmacyHooks.usePharmacies.mockReturnValue({ data: [], isFetching: false, isError: true })
    const user = userEvent.setup()
    render(<CommandPalette user={USERS.bauyrzhan} onNav={vi.fn()} onClose={vi.fn()} />)

    await user.type(screen.getByPlaceholderText('Раздел или аптека…'), 'Гагарина')
    expect(screen.getByRole('alert')).toHaveTextContent('Не удалось загрузить аптеки')
    expect(screen.queryByText('Ничего не найдено')).not.toBeInTheDocument()
  })
})
