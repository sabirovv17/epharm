import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import MonthlyVisitPanel from './MonthlyVisitPanel'
import { useUiStore } from '@/app/store'

afterEach(() => useUiStore.setState({ language: 'ru' }))

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

it('shows the monthly visit entirely in Kazakh and explains a missing verified pharmacy link', async () => {
  useUiStore.setState({ language: 'kk' })
  const request = vi.fn().mockResolvedValue([])
  render(<MonthlyVisitPanel request={request} onStarted={vi.fn()} />)

  expect(await screen.findByRole('heading', { name: 'Айлық сапар' })).toBeVisible()
  expect(screen.getByText('Айлық сапар үшін қолжетімді дәріхана жоқ.')).toBeVisible()
  expect(screen.getByText(/расталған түрде байланыстырылған/)).toBeVisible()
  expect(screen.getByRole('combobox', { name: 'Сапар дәріханасы' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Сапарды бастау немесе жалғастыру' })).toBeDisabled()
})
