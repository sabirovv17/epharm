// Тесты RuleRow — рендер строки + row action menu (⋯ → Дублировать / Архивировать).
// Замокан useProductLookup (продукты возвращают undefined — fallback на trigger.kind).

import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { RuleDto } from '@/lib/api-types'
import { RuleRow } from './RuleRow'

const catalogHooks = vi.hoisted(() => ({
  useProducts: vi.fn(),
  useBrands: vi.fn(),
  useMnnGroups: vi.fn(),
  useProductLookup: vi.fn(),
  buildProductIndex: vi.fn(),
}))

vi.mock('@/lib/queries/catalog', () => catalogHooks)

beforeEach(() => {
  vi.clearAllMocks()
  catalogHooks.useProductLookup.mockReturnValue(() => undefined)
})

function mkRule(over: Partial<RuleDto> = {}): RuleDto {
  return {
    id: 'r_test',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_x' },
    recommend: 'p_y',
    bonus: 520,
    script: '',
    advantages: [],
    abTest: null,
    pharmacies: 0,
    impressions: 0,
    accepts: 0,
    convRate: 36.8,
    revenue: 0,
    payout: 0,
    createdBy: '',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function renderRow(props: Partial<Parameters<typeof RuleRow>[0]> = {}) {
  return render(
    <ul>
      <RuleRow rule={mkRule()} selected={false} onSelect={vi.fn()} {...props} />
    </ul>,
  )
}

describe('RuleRow — рендер', () => {
  it('показывает % конверсии и сумму бонуса', () => {
    renderRow({ rule: mkRule({ convRate: 42.5, bonus: 777 }) })
    expect(screen.getByText('42.5%')).toBeInTheDocument()
    expect(screen.getByText(/\+777/)).toBeInTheDocument()
  })

  it('selected → inset-зелёный shadow + bg', () => {
    const { container } = renderRow({ selected: true })
    expect(container.querySelector('li')).toHaveClass('bg-brand-green-50/60')
  })
})

describe('RuleRow — row action menu', () => {
  it('меню скрыто пока триггер не нажат', () => {
    renderRow({ onArchive: vi.fn(), onDuplicate: vi.fn() })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('клик «⋯» открывает меню', async () => {
    const user = userEvent.setup()
    renderRow({ onArchive: vi.fn(), onDuplicate: vi.fn() })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Дублировать/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /В архив/ })).toBeInTheDocument()
  })

  it('клик «Дублировать» вызывает onDuplicate с rule + закрывает меню', async () => {
    const onDuplicate = vi.fn()
    const user = userEvent.setup()
    renderRow({ onArchive: vi.fn(), onDuplicate })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    await user.click(screen.getByRole('menuitem', { name: /Дублировать/ }))
    expect(onDuplicate).toHaveBeenCalledWith(expect.objectContaining({ id: 'r_test' }))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('клик «В архив» вызывает onArchive', async () => {
    const onArchive = vi.fn()
    const user = userEvent.setup()
    renderRow({ onArchive, onDuplicate: vi.fn() })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    await user.click(screen.getByRole('menuitem', { name: /В архив/ }))
    expect(onArchive).toHaveBeenCalledWith(expect.objectContaining({ id: 'r_test' }))
  })

  it('у archived rule — пункт «Уже в архиве» disabled', async () => {
    const onArchive = vi.fn()
    const user = userEvent.setup()
    renderRow({
      rule: mkRule({ status: 'archived' }),
      onArchive,
      onDuplicate: vi.fn(),
    })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    const item = screen.getByRole('menuitem', { name: /Уже в архиве/ })
    expect(item).toBeDisabled()
    await user.click(item)
    expect(onArchive).not.toHaveBeenCalled()
  })

  it('Escape закрывает меню', async () => {
    const user = userEvent.setup()
    renderRow({ onArchive: vi.fn(), onDuplicate: vi.fn() })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('клик на row при открытом меню не пробрасывается в onSelect', async () => {
    const onSelect = vi.fn()
    const user = userEvent.setup()
    renderRow({ onSelect, onArchive: vi.fn(), onDuplicate: vi.fn() })
    await user.click(screen.getByTestId('row-menu-trigger-r_test'))
    onSelect.mockClear() // trigger click уже мог пробросить; смотрим только следующие клики
    // Клик в меню не должен вызывать onSelect
    await user.click(screen.getByRole('menuitem', { name: /Дублировать/ }))
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('без onArchive и onDuplicate props — кнопка ⋯ не рендерится', () => {
    renderRow()
    expect(screen.queryByTestId('row-menu-trigger-r_test')).not.toBeInTheDocument()
  })
})
