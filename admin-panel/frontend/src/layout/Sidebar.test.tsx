// Тесты Sidebar — рендер 12 пунктов, активный пункт, callback onSelect,
// collapse, брендинг (Epharm), Contract widget — у всех empty state на этапе MVP.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Sidebar } from './Sidebar'
import { SECTIONS, USERS } from '@/mocks/fixtures'

function setup(overrides: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  const onSelect = vi.fn()
  const onToggle = vi.fn()
  const onContractOpen = vi.fn()
  const utils = render(
    <Sidebar
      active="rules"
      onSelect={onSelect}
      collapsed={false}
      onToggle={onToggle}
      onContractOpen={onContractOpen}
      user={USERS.damir}
      {...overrides}
    />,
  )
  return { onSelect, onToggle, onContractOpen, ...utils }
}

describe('Sidebar — брендинг', () => {
  it('в expanded — wordmark Epharm с зелёным акцентом на «E»', () => {
    setup()
    const accentE = screen.getByText('E')
    expect(accentE).toHaveClass('text-brand-green-400')
    expect(accentE.parentElement).toHaveTextContent('Epharm')
    expect(screen.getByText(/Console · HQ/i)).toBeInTheDocument()
  })

  it('в expanded — нет упоминания старого бренда PharmaPay', () => {
    const { container } = setup()
    expect(container.innerHTML).not.toMatch(/PharmaPay/i)
  })
})

describe('Sidebar — навигация', () => {
  it('рендерит все 12 пунктов из SECTIONS', () => {
    setup()
    SECTIONS.forEach((s) => {
      expect(screen.getByRole('button', { name: new RegExp(s.label, 'i') })).toBeInTheDocument()
    })
  })

  it('активный пункт получает sidebar-active класс', () => {
    setup({ active: 'reconcile' })
    expect(screen.getByRole('button', { name: /Сверка чеков/i })).toHaveClass('sidebar-active')
  })

  it('клик по пункту вызывает onSelect с правильным id', async () => {
    const user = userEvent.setup()
    const { onSelect } = setup()
    await user.click(screen.getByRole('button', { name: /Дашборд аналитики/i }))
    expect(onSelect).toHaveBeenCalledWith('dashboard')
  })

  it('badge не рендерится (в фикстурах сейчас undefined)', () => {
    setup()
    expect(screen.queryByText('12')).not.toBeInTheDocument()
    expect(screen.queryByText('507')).not.toBeInTheDocument()
    expect(screen.queryByText('24')).not.toBeInTheDocument()
    expect(screen.queryByText('!')).not.toBeInTheDocument()
  })
})

describe('Sidebar — collapse', () => {
  it('кнопка свернуть вызывает onToggle', async () => {
    const user = userEvent.setup()
    const { onToggle } = setup()
    await user.click(screen.getByTitle(/Свернуть/i))
    expect(onToggle).toHaveBeenCalledOnce()
  })

  it('в collapsed — floating expand-tab показан и вызывает onToggle', async () => {
    const user = userEvent.setup()
    const { onToggle } = setup({ collapsed: true })
    await user.click(screen.getByTitle(/Развернуть сайдбар/i))
    expect(onToggle).toHaveBeenCalled()
  })
})

describe('Sidebar — Contract widget на пустых данных (MVP)', () => {
  it.each([USERS.damir, USERS.aigerim, USERS.bauyrzhan] as const)(
    'у %s — empty state без цифр',
    (user) => {
      setup({ user })
      expect(screen.getByTestId('contract-widget-empty')).toBeInTheDocument()
      expect(screen.getByText(/Не подписан/i)).toBeInTheDocument()
      expect(screen.queryByTestId('contract-widget-active')).not.toBeInTheDocument()
    },
  )

  it('empty state не содержит ни одного процента или валюты', () => {
    setup()
    const widget = screen.getByTestId('contract-widget-empty')
    expect(widget).not.toHaveTextContent(/%/)
    expect(widget).not.toHaveTextContent(/₸/)
  })

  it('empty state не кликабелен (нет button внутри)', () => {
    setup()
    expect(screen.getByTestId('contract-widget-empty').querySelector('button')).toBeNull()
  })

  it('в collapsed — статичный shield (не кликабелен), onContractOpen не вызывается', async () => {
    const user = userEvent.setup()
    const { onContractOpen } = setup({ collapsed: true })
    const placeholder = screen.getByTitle(/Нет активного контракта/i)
    expect(placeholder.tagName).toBe('DIV')
    await user.click(placeholder)
    expect(onContractOpen).not.toHaveBeenCalled()
  })

  it('заголовок «Контракт» виден в empty state', () => {
    setup()
    expect(screen.getByText(/^Контракт$/i)).toBeInTheDocument()
  })
})
