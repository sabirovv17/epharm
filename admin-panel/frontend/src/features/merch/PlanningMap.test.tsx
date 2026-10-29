import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import PlanningMap from './PlanningMap'
import type { CatalogBranch } from './placements-api'
it('loads no external map until explicitly opened and selects only the provided authorized branch', () => {
  const select = vi.fn()
  const branch = {
    id: 'own',
    name: 'Аптека',
    address: 'Адрес',
    city: 'Алматы',
    latitude: 43.2,
    longitude: 76.9,
    items: [],
  } as unknown as CatalogBranch
  const { container } = render(<PlanningMap branches={[branch]} selected="" onSelect={select} />)
  expect(container.querySelector('img')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Показать карту аптек' }))
  expect(screen.getByRole('link', { name: '© OpenStreetMap contributors' })).toBeInTheDocument()
  const image = container.querySelector('img')!
  expect(image.getAttribute('src')).toMatch(
    /^https:\/\/tile\.openstreetmap\.org\/\d+\/\d+\/\d+\.png$/,
  )
  expect(image.getAttribute('referrerpolicy')).toBe('origin')
  fireEvent.keyDown(screen.getByRole('button', { name: /Выбрать Аптека/ }), { key: 'Enter' })
  expect(select).toHaveBeenCalledWith('own')
  fireEvent.error(image)
  expect(screen.getByRole('status')).toHaveTextContent('недоступна')
  expect(screen.getByRole('button', { name: /Выбрать Аптека/ })).toBeInTheDocument()
})
describe('missing coordinates', () => {
  it('keeps unlocated pharmacies in the accompanying list without inventing positions', () => {
    render(
      <PlanningMap
        branches={[{ id: 'no-geo', latitude: null, longitude: null } as unknown as CatalogBranch]}
        selected=""
        onSelect={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Показать карту аптек' }))
    expect(screen.getByText(/Координаты есть у 0 из 1/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Выбрать/ })).toBeNull()
  })
})

it('clusters overlapping pharmacy markers and lets a user select an exact colocated pharmacy at maximum zoom', () => {
  const select = vi.fn()
  const base = {
    address: 'Общий адрес',
    city: 'Алматы',
    latitude: 43.2,
    longitude: 76.9,
    items: [],
  }
  render(
    <PlanningMap
      branches={
        [
          { ...base, id: 'a', name: 'Аптека A' },
          { ...base, id: 'b', name: 'Аптека B' },
        ] as unknown as CatalogBranch[]
      }
      selected=""
      onSelect={select}
    />,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Показать карту аптек' }))
  for (let index = 0; index < 7; index++)
    fireEvent.click(screen.getByRole('button', { name: 'Приблизить группу из 2 аптек' }))
  fireEvent.click(screen.getByRole('button', { name: 'Выбрать одну из 2 аптек' }))
  fireEvent.click(screen.getByRole('button', { name: 'Аптека B · Общий адрес' }))
  expect(select).toHaveBeenCalledWith('b')
})
