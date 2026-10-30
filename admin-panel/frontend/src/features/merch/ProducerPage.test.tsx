import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ProducerPage from './ProducerPage'

vi.mock('./useProducerSession', () => ({
  useProducerSession: () => ({ session: { producerId: 'synthetic' }, epoch: 1 }),
}))
vi.mock('./usePlacementRead', () => ({
  usePlacementRead: () => ({
    data: { id: 'synthetic', name: 'Синтетическая компания', profileComplete: true },
    pending: false,
    error: null,
    reload: vi.fn(),
  }),
}))
vi.mock('./PlacementWorkspace', () => ({
  default: () => <input aria-label="Черновик своих размещений" />,
}))
vi.mock('./ProducerCommercialWorkspace', () => ({
  default: () => <input aria-label="Черновик собственного бюджета" />,
}))
vi.mock('./TaskInstructionPanel', () => ({ default: () => <p>Собственные инструктажи</p> }))
afterEach(cleanup)

describe('producer section navigation', () => {
  it('keeps producer identity and unsaved drafts when moving between exclusive sections', async () => {
    const user = userEvent.setup()
    render(<ProducerPage />)
    expect(screen.getByText('Синтетическая компания')).toBeVisible()
    expect(screen.queryByLabelText('Черновик собственного бюджета')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Черновик своих размещений'), 'Свой черновик')
    const budget = screen.getByRole('button', { name: 'Бюджет, период и команда' })
    await user.click(budget)
    expect(budget).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById(budget.getAttribute('aria-controls')!)).toBeVisible()
    expect(screen.getByLabelText('Черновик своих размещений')).not.toBeVisible()
    await user.type(screen.getByLabelText('Черновик собственного бюджета'), 'Бюджет компании')
    await user.click(screen.getByRole('button', { name: 'Мои размещения' }))
    expect(screen.getByLabelText('Черновик своих размещений')).toHaveValue('Свой черновик')
    await user.click(budget)
    expect(screen.getByLabelText('Черновик собственного бюджета')).toHaveValue('Бюджет компании')
    expect(screen.queryByRole('button', { name: 'Тарифы поставщиков' })).not.toBeInTheDocument()
  })
})
