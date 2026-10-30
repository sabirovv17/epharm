import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import ProducerLoginPage from './ProducerLoginPage'
import { producerClient } from './producer-client'

vi.mock('./useProducerSession', () => ({ useProducerSession: () => ({ session: null }) }))
vi.mock('./producer-client', () => ({
  producerClient: { requestCode: vi.fn(), verify: vi.fn() },
  producerError: (error: unknown) => (error instanceof Error ? error.message : 'Запрос недоступен'),
}))
beforeEach(() => vi.resetAllMocks())
const mount = () =>
  render(
    <MemoryRouter>
      <ProducerLoginPage />
    </MemoryRouter>,
  )

it('shows two explicit login steps and does not request a code on entry', async () => {
  vi.mocked(producerClient.requestCode).mockResolvedValue({
    challengeId: 'synthetic',
    phoneMasked: '+7 *** 0000',
    ttlSeconds: 300,
    retryAfterSeconds: 60,
    message: 'Код отправлен',
  })
  mount()
  expect(screen.getByRole('status')).toHaveTextContent('Шаг 1 из 2')
  expect(producerClient.requestCode).not.toHaveBeenCalled()
  await userEvent.type(screen.getByLabelText('Телефон производителя'), '+77000000000')
  await userEvent.click(screen.getByRole('button', { name: 'Получить SMS-код' }))
  expect(await screen.findByLabelText('SMS-код производителя')).toBeVisible()
  expect(screen.getByRole('status')).toHaveTextContent('Шаг 2 из 2')
  expect(screen.getByLabelText('Телефон производителя')).toBeDisabled()
  expect(producerClient.requestCode).toHaveBeenCalledExactlyOnceWith('+77000000000')
  expect(producerClient.verify).not.toHaveBeenCalled()
})

it('keeps an unavailable SMS request on the phone step without inventing success', async () => {
  vi.mocked(producerClient.requestCode).mockRejectedValue(new Error('SMS временно недоступны'))
  mount()
  await userEvent.type(screen.getByLabelText('Телефон производителя'), '+77000000000')
  await userEvent.click(screen.getByRole('button', { name: 'Получить SMS-код' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('SMS временно недоступны')
  expect(screen.getByRole('status')).toHaveTextContent('Шаг 1 из 2')
  expect(screen.queryByLabelText('SMS-код производителя')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Получить SMS-код' })).toBeEnabled()
  expect(producerClient.requestCode).toHaveBeenCalledTimes(1)
  expect(producerClient.verify).not.toHaveBeenCalled()
})
