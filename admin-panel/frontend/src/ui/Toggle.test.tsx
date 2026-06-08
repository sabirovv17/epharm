// Тесты Toggle — поведение + a11y.
//
// Гипотеза 2 (REPRODUCTION):
//   <span role="switch" onClick={...}> не доступен с клавиатуры.
//   Любой пользователь без мыши (включая screen readers) не сможет переключить статус.
//   role="switch" по WAI-ARIA должен быть на focusable элементе (button/input).

import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Toggle } from './Toggle'

describe('Toggle — рендер', () => {
  it('on=true → aria-checked=true', () => {
    render(<Toggle on={true} onChange={vi.fn()} />)
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true')
  })

  it('on=false → aria-checked=false', () => {
    render(<Toggle on={false} onChange={vi.fn()} />)
    expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'false')
  })

  it('label рендерится рядом', () => {
    render(<Toggle on={false} onChange={vi.fn()} label="Активно" />)
    expect(screen.getByText('Активно')).toBeInTheDocument()
  })
})

describe('Toggle — взаимодействие', () => {
  it('click → onChange(!on)', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<Toggle on={false} onChange={onChange} />)
    await user.click(screen.getByRole('switch'))
    expect(onChange).toHaveBeenCalledWith(true)
  })
})

describe('Toggle — a11y', () => {
  it('REPRO: keyboard-фокус — клавиша Tab должна привести курсор на toggle', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(
      <>
        <button>before</button>
        <Toggle on={false} onChange={onChange} />
        <button>after</button>
      </>,
    )
    // Фокус на before
    screen.getByText('before').focus()
    // Tab → должен попасть на toggle (если он focusable)
    await user.tab()
    expect(screen.getByRole('switch')).toHaveFocus()
  })

  it('REPRO: пробел/Enter на сфокусированном toggle — переключает', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<Toggle on={false} onChange={onChange} />)
    const toggle = screen.getByRole('switch')
    toggle.focus()
    await user.keyboard(' ')
    expect(onChange).toHaveBeenCalledWith(true)
  })
})
