import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import NativeTaskExecutor from './NativeTaskExecutor'
const token = 'synthetic_task_capability_000000000000000000000000'
const context = {
  dispatchId: 'task-demo',
  taskTitle: 'Демо-оплачиваемое задание',
  pharmacyName: 'Демо-аптека',
  amountKzt: 400,
  dueAt: '2099-01-01T00:00:00Z',
  challengeExpiresAt: '2099-01-01T00:00:00Z',
  phoneMasked: '+7 *** 0000',
  bindingId: null,
  currency: 'KZT',
}
afterEach(() => vi.restoreAllMocks())
describe('native paid executor SMS binding', () => {
  it('does not send SMS automatically; request and verification never accept a phone, person or amount body', async () => {
    const request = vi.fn().mockImplementation(async (path, init) => {
      if (String(path).endsWith('/sms/request'))
        return {
          otpChallengeId: 'challenge-demo',
          phoneMasked: context.phoneMasked,
          expiresAt: context.challengeExpiresAt,
          retryAfterSeconds: 60,
        }
      if (String(path).endsWith('/sms/verify'))
        return { bindingId: 'binding-demo', dispatchId: context.dispatchId }
      expect(init.headers).toEqual({ 'X-Merch-Task-Token': token })
      return context
    })
    const verified = vi.fn()
    const user = userEvent.setup()
    render(
      <NativeTaskExecutor
        id={context.dispatchId}
        token={token}
        request={request}
        onVerified={verified}
      />,
    )
    await screen.findByText(/телефон вашего личного аккаунта/)
    expect(screen.getByRole('status')).toHaveTextContent('Шаг 1 из 2')
    expect(request).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('textbox', { name: /Телефон/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Получить код подтверждения' }))
    await user.type(await screen.findByLabelText('SMS-код исполнителя'), '1234')
    expect(screen.getByRole('status')).toHaveTextContent('Шаг 2 из 2')
    await user.click(screen.getByRole('button', { name: 'Подтвердить исполнителя' }))
    await waitFor(() => expect(verified).toHaveBeenCalledOnce())
    const send = request.mock.calls.find(([path]) => String(path).endsWith('/sms/request'))!
    expect(send[1]).not.toHaveProperty('body')
    const verify = request.mock.calls.find(([path]) => String(path).endsWith('/sms/verify'))!
    expect(JSON.parse(verify[1].body)).toEqual({ otpChallengeId: 'challenge-demo', code: '1234' })
    expect(verify[1].headers).toEqual({ 'X-Merch-Task-Token': token })
  })
  it('reuses an existing task binding without another SMS', async () => {
    const request = vi.fn().mockResolvedValue({ ...context, bindingId: 'binding-existing' })
    const verified = vi.fn()
    render(
      <NativeTaskExecutor
        id={context.dispatchId}
        token={token}
        request={request}
        onVerified={verified}
      />,
    )
    await waitFor(() => expect(verified).toHaveBeenCalledOnce())
    expect(request).toHaveBeenCalledTimes(1)
  })
  it('requires a fresh QR when the memory-only capability is absent', () => {
    const request = vi.fn()
    render(
      <NativeTaskExecutor
        id={context.dispatchId}
        token={null}
        request={request}
        onVerified={vi.fn()}
      />,
    )
    expect(screen.getByText(/Откройте исходный QR-код/)).toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })
  it('shows a disabled-provider failure without inventing a binding or retrying SMS', async () => {
    const request = vi.fn().mockImplementation(async (path) => {
      if (String(path).endsWith('/sms/request'))
        throw new Error('Сервис подтверждения временно недоступен')
      return context
    })
    const verified = vi.fn()
    render(
      <NativeTaskExecutor
        id={context.dispatchId}
        token={token}
        request={request}
        onVerified={verified}
      />,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Получить код подтверждения' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('временно недоступен')
    expect(
      request.mock.calls.filter(([path]) => String(path).endsWith('/sms/request')),
    ).toHaveLength(1)
    expect(verified).not.toHaveBeenCalled()
  })
  it('does not accept a success response for a different task binding', async () => {
    const request = vi
      .fn()
      .mockImplementation(async (path) =>
        String(path).endsWith('/sms/request')
          ? { otpChallengeId: 'challenge-demo', retryAfterSeconds: 60 }
          : String(path).endsWith('/sms/verify')
            ? { bindingId: 'binding-other', dispatchId: 'other-task' }
            : context,
      )
    const verified = vi.fn()
    const user = userEvent.setup()
    render(
      <NativeTaskExecutor
        id={context.dispatchId}
        token={token}
        request={request}
        onVerified={verified}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Получить код подтверждения' }))
    await user.type(await screen.findByLabelText('SMS-код исполнителя'), '1234')
    await user.click(screen.getByRole('button', { name: 'Подтвердить исполнителя' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'не подтвердил привязку этого задания',
    )
    expect(verified).not.toHaveBeenCalled()
  })
})
