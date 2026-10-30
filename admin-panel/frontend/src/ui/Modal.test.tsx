import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { Modal } from './Modal'

describe('Modal keyboard access', () => {
  it('names close, traps keyboard focus and restores the opening control', async () => {
    function Example() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>Открыть форму</button>
          <Modal open={open} title="Форма" onClose={() => setOpen(false)}>
            <input aria-label="Название" />
            <button>Сохранить</button>
          </Modal>
        </>
      )
    }
    const user = userEvent.setup()
    render(<Example />)
    const opener = screen.getByRole('button', { name: 'Открыть форму' })
    await user.click(opener)
    expect(screen.getByRole('dialog')).toHaveFocus()
    await user.tab({ shift: true })
    expect(screen.getByRole('button', { name: 'Сохранить' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Закрыть окно' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('Escape closes only the top dialog, never the underlying confirmation', async () => {
    const outer = vi.fn()
    const inner = vi.fn()
    render(
      <>
        <Modal open title="Первое" onClose={outer}>
          <button>Действие</button>
        </Modal>
        <Modal open title="Второе" onClose={inner}>
          <button>Подтвердить</button>
        </Modal>
      </>,
    )
    await userEvent.keyboard('{Escape}')
    expect(inner).toHaveBeenCalledOnce()
    expect(outer).not.toHaveBeenCalled()
  })

  it('keeps an initially nested dialog on top despite child-first effect ordering', async () => {
    const outer = vi.fn()
    const inner = vi.fn()
    render(
      <Modal open title="Родитель" onClose={outer}>
        <Modal open title="Подтверждение" onClose={inner}>
          <button>Согласен</button>
        </Modal>
      </Modal>,
    )
    expect(screen.getByRole('dialog', { name: 'Подтверждение' })).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    expect(inner).toHaveBeenCalledOnce()
    expect(outer).not.toHaveBeenCalled()
  })
})
