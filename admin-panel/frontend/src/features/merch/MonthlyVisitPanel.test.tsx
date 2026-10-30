import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import MonthlyVisitPanel from './MonthlyVisitPanel'

it('distinguishes a failed branch read from an empty list and retries only that read', async () => {
  const request = vi
    .fn()
    .mockRejectedValueOnce(new Error('Соединение прервано'))
    .mockResolvedValue([])
  const started = vi.fn()
  render(<MonthlyVisitPanel request={request} onStarted={started} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить аптеки')
  expect(screen.queryByText(/Нет доступных аптек/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Начать или продолжить визит' })).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: 'Повторить загрузку аптек' }))
  expect(await screen.findByText(/Нет доступных аптек/)).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(2)
  for (const [path, init] of request.mock.calls) {
    expect(path).toBe('/api/mobile/merch/tasks/visits/branches')
    expect(init).not.toHaveProperty('method', 'POST')
  }
  expect(started).not.toHaveBeenCalled()
})

it('keeps the visit action unavailable during branch loading and labels the reporting timezone', () => {
  const request = vi.fn().mockImplementation(() => new Promise(() => {}))
  render(<MonthlyVisitPanel request={request} onStarted={vi.fn()} />)
  expect(screen.getByRole('status')).toHaveTextContent('Проверяем доступные аптеки')
  expect(screen.getByRole('combobox', { name: 'Аптека визита' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Начать или продолжить визит' })).toBeDisabled()
  expect(screen.getByText(/Asia\/Almaty/)).toBeVisible()
})
