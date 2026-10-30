// Тесты Sidebar — рендер 12 пунктов, активный пункт, callback onSelect,
// collapse, брендинг (Epharm), Contract widget — у всех empty state на этапе MVP.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Sidebar } from './Sidebar'
import { USERS } from '@/mocks/fixtures'

function setup(overrides: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  const onSelect = vi.fn()
  const onSelectTrainingTab = vi.fn()
  const onToggle = vi.fn()
  const onContractOpen = vi.fn()
  const utils = render(
    <Sidebar
      active="rules"
      onSelect={onSelect}
      collapsed={false}
      onToggle={onToggle}
      onContractOpen={onContractOpen}
      activeTrainingTab="overview"
      onSelectTrainingTab={onSelectTrainingTab}
      user={USERS.damir}
      {...overrides}
    />,
  )
  return { onSelect, onSelectTrainingTab, onToggle, onContractOpen, ...utils }
}

describe('Sidebar — брендинг', () => {
  it('в expanded — логотип и аптечный wordmark ePharm', () => {
    const { container } = setup()
    expect(container.querySelector('svg')).toBeInTheDocument() // бренд = SVG-логотип
    expect(screen.getByText('ePharm')).toBeInTheDocument()
    expect(screen.getByText(/Операционный центр/i)).toBeInTheDocument()
  })

  it('в expanded — нет упоминания старого бренда PharmaPay', () => {
    const { container } = setup()
    expect(container.innerHTML).not.toMatch(/PharmaPay/i)
  })
})

describe('Sidebar — навигация', () => {
  it('для HQ сохраняет семь групп, управление в отдельном нижнем блоке', () => {
    const { container } = setup({ user: USERS.bauyrzhan })
    ;[
      'Аналитика',
      'Мотивация фармацевтов',
      'Обучение',
      'Мерчендайзинг',
      'Аптеки и товары',
      'Фармацевты',
      'Управление',
    ].forEach((label) => expect(screen.getByRole('button', { name: label })).toBeInTheDocument())
    const bottom = container.querySelector('[aria-label="Управление"]')
    expect(bottom?.querySelector('button')).toHaveTextContent('Управление')
    expect(bottom?.closest('nav')).toBeNull()
  })

  it('бизнес-роль не получает обучение; неподключённый мерч не становится доступом', async () => {
    setup()
    expect(screen.queryByRole('button', { name: /^Обучение$/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Мерчендайзинг' }))
    expect(screen.getByRole('button', { name: /Кабинет производителя/ })).toBeDisabled()
    expect(
      screen.queryByRole('button', { name: 'Календарь и доступность' }),
    ).not.toBeInTheDocument()
  })

  it('обучение сохраняет все десять вкладок и AI, без финансов и чужих разделов', () => {
    setup({ user: USERS.lms, active: 'lms', activeTrainingTab: 'courses' })
    ;[
      'Дашборд',
      'Программы',
      'Онлайн-курсы',
      'Офлайн-мероприятия',
      'Назначения',
      'Посещаемость',
      'Результаты и экзамены',
      'Сертификаты',
      'Аналитика',
      'Настройки обучения',
      'AI-Экзаменация',
    ].forEach((label) => expect(screen.getByRole('button', { name: label })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Онлайн-курсы' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.queryByRole('button', { name: 'Мотивация фармацевтов' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Мерчендайзинг' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('contract-widget-empty')).not.toBeInTheDocument()
  })

  it('учебный пункт вызывает отдельную навигацию по вкладке', async () => {
    const { onSelectTrainingTab } = setup({ user: USERS.lms, active: 'lms' })
    await userEvent.click(screen.getByRole('button', { name: 'Результаты и экзамены' }))
    expect(onSelectTrainingTab).toHaveBeenCalledWith('results')
  })

  it('пункты внутри групп сохраняют старый section callback и активность', async () => {
    const { onSelect } = setup({ active: 'reconcile' })
    expect(screen.getByRole('button', { name: /Сверка чеков/ })).toHaveAttribute(
      'aria-current',
      'page',
    )
    await userEvent.click(screen.getByRole('button', { name: 'Аналитика' }))
    await userEvent.click(screen.getByRole('button', { name: /Дашборд аналитики/ }))
    expect(onSelect).toHaveBeenCalledWith('dashboard')
  })

  it('явное серверное право открывает только календарь мерча у регионала', async () => {
    const { onSelect } = setup({
      user: { ...USERS.lms, role: 'REGIONAL_MANAGER' },
      canViewMerchInventory: true,
      active: 'merch_inventory',
    })
    expect(screen.getByRole('button', { name: 'Календарь и доступность' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Мотивация фармацевтов' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Календарь и доступность' }))
    expect(onSelect).toHaveBeenCalledWith('merch_inventory')
  })
})

describe('Sidebar — collapse', () => {
  it('кнопка свернуть вызывает onToggle', async () => {
    const user = userEvent.setup()
    const { onToggle } = setup()
    await user.click(screen.getByTitle(/Свернуть/i))
    expect(onToggle).toHaveBeenCalledOnce()
  })

  it('в collapsed — кнопка логотипа разворачивает сайдбар', async () => {
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
