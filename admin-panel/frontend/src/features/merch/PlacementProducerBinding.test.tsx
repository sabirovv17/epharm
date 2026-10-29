import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { api } from '@/lib/api'
import PlacementProducerBinding from './PlacementProducerBinding'
import type { Placement } from './placements-api'
const placement = {
  id: 'plan-demo',
  producerId: null,
  producerBindingStatus: 'unresolved',
  canBindProducer: true,
  revision: 7,
} as Placement
const producers = [
  { id: 'producer-demo', name: 'Демо Компания', active: true, profileComplete: true },
  { id: 'incomplete-demo', name: null, active: true, profileComplete: false },
]
afterEach(() => vi.restoreAllMocks())
describe('HQ explicit source plan producer binding', () => {
  it('does not offer binding when the server forbids it or ownership already exists', () => {
    const { rerender } = render(
      <PlacementProducerBinding
        placement={{ ...placement, canBindProducer: false }}
        producers={producers}
        onChanged={vi.fn()}
      />,
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    rerender(
      <PlacementProducerBinding
        placement={{ ...placement, producerId: 'owner-demo', producerBindingStatus: 'verified' }}
        producers={producers}
        onChanged={vi.fn()}
      />,
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
  it('requires a selected complete company, private reason, acknowledgement and fresh password; only binding endpoint receives the reason', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: {} })
    const changed = vi.fn()
    const user = userEvent.setup()
    render(
      <PlacementProducerBinding placement={placement} producers={producers} onChanged={changed} />,
    )
    await user.click(screen.getByRole('button', { name: 'Подтвердить производителя медиаплана' }))
    expect(screen.getByRole('button', { name: 'Подтвердить связь' })).toBeDisabled()
    expect(screen.queryByRole('option', { name: /incomplete/ })).not.toBeInTheDocument()
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Компания для связи медиаплана' }),
      'producer-demo',
    )
    await user.type(
      screen.getByLabelText('Внутреннее основание связи компании'),
      'DEMO-private-reference',
    )
    await user.type(
      screen.getByLabelText('Пароль подтверждения связи медиаплана'),
      'synthetic-proof',
    )
    await user.click(screen.getByRole('checkbox', { name: /Я проверил связь/ }))
    await user.click(screen.getByRole('button', { name: 'Подтвердить связь' }))
    await waitFor(() =>
      expect(post).toHaveBeenCalledExactlyOnceWith(
        '/api/admin/merch/placements/plan-demo/producer-binding',
        {
          expectedRevision: 7,
          producerId: 'producer-demo',
          reason: 'DEMO-private-reference',
          currentPassword: 'synthetic-proof',
        },
      ),
    )
    expect(changed).toHaveBeenCalledOnce()
    expect(screen.queryByLabelText('Пароль подтверждения связи медиаплана')).not.toBeInTheDocument()
  })
})
