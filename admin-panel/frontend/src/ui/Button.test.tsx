// Тесты Button + IconButton — variants, sizes, leading/trailing, disabled, click.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button, IconButton } from './Button'

describe('Button', () => {
  it('рендерит текст и применяет btn-primary btn-md по умолчанию', () => {
    render(<Button>Сохранить</Button>)
    const btn = screen.getByRole('button', { name: /Сохранить/i })
    expect(btn).toHaveClass('btn', 'btn-md', 'btn-primary')
  })

  it.each(['primary', 'ink', 'ghost', 'outline', 'danger'] as const)(
    'применяет класс btn-%s для variant=%s',
    (v) => {
      render(<Button variant={v}>X</Button>)
      expect(screen.getByRole('button')).toHaveClass(`btn-${v}`)
    },
  )

  it.each(['sm', 'md', 'lg'] as const)('применяет класс btn-%s для size=%s', (s) => {
    render(<Button size={s}>X</Button>)
    expect(screen.getByRole('button')).toHaveClass(`btn-${s}`)
  })

  it('добавляет внешний className к классам btn', () => {
    render(<Button className="my-extra">X</Button>)
    expect(screen.getByRole('button')).toHaveClass('my-extra', 'btn', 'btn-md', 'btn-primary')
  })

  it('рендерит leading и trailing slots вокруг текста', () => {
    render(
      <Button
        leading={<span data-testid="lead">L</span>}
        trailing={<span data-testid="trail">T</span>}
      >
        Middle
      </Button>,
    )
    expect(screen.getByTestId('lead')).toBeInTheDocument()
    expect(screen.getByText('Middle')).toBeInTheDocument()
    expect(screen.getByTestId('trail')).toBeInTheDocument()
  })

  it('срабатывает onClick по нажатию', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()
    render(<Button onClick={onClick}>Тыкни</Button>)
    await user.click(screen.getByRole('button'))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('disabled блокирует клик', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()
    render(
      <Button onClick={onClick} disabled>
        Заблочен
      </Button>,
    )
    await user.click(screen.getByRole('button'))
    expect(onClick).not.toHaveBeenCalled()
  })
})

describe('IconButton', () => {
  it('применяет btn-icon класс', () => {
    render(<IconButton aria-label="close">X</IconButton>)
    expect(screen.getByRole('button')).toHaveClass('btn-icon', 'btn-ghost')
  })

  it('без tip — рендерит просто button', () => {
    render(<IconButton aria-label="close">X</IconButton>)
    expect(screen.queryByText(/tip/i)).not.toBeInTheDocument()
  })

  it('с tip — оборачивает в tooltip контейнер с tip-body', () => {
    render(
      <IconButton aria-label="close" tip="Закрыть">
        X
      </IconButton>,
    )
    expect(screen.getByText('Закрыть')).toBeInTheDocument()
    expect(screen.getByText('Закрыть')).toHaveClass('tip-body')
  })
})
