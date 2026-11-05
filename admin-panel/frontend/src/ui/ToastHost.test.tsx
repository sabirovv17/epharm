import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ToastHost, useToast } from './ToastHost'

function Producer() {
  const toast = useToast()
  return <button onClick={() => toast.push('Saved', { duration: 1000 })}>Notify</button>
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

it('expires notifications and releases every pending timer on unmount', () => {
  const view = render(
    <ToastHost>
      <Producer />
    </ToastHost>,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Notify' }))
  expect(screen.getByText('Saved')).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(1000))
  expect(screen.queryByText('Saved')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Notify' }))
  fireEvent.click(screen.getByRole('button', { name: 'Notify' }))
  expect(vi.getTimerCount()).toBe(2)
  view.unmount()
  expect(vi.getTimerCount()).toBe(0)
})
