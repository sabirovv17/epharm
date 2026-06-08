// Тесты BuilderForm — поведение полей ввода (textarea advantages, script, bonus).
// Гипотеза 1 (REPRODUCTION):
//   value={advantages.join('\n')} + onChange .split('\n').filter(Boolean)
//   уничтожает пустые строки прямо во время набора, делая невозможным:
//     - перевод курсора на следующую строку клавишей Enter
//     - сохранение порядка строк с пробелами
//   Это легко проверяется так: устанавливаем advantages=['А','Б'], имитируем ввод
//   "А\nБ\n" — должны увидеть состояние, где между Б и пустотой стоит trailing newline.
//   В текущей реализации trailing \n сразу обрезается → cursor jump.

import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { RuleDto } from '@/lib/api-types'
import { BuilderForm } from './RuleBuilder'

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
  catalogHooks.useProducts.mockReturnValue({ data: [], isLoading: false })
  catalogHooks.useProductLookup.mockReturnValue(() => undefined)
})

function mkRule(over: Partial<RuleDto> = {}): RuleDto {
  return {
    id: 'r_test',
    type: 'substitution',
    status: 'active',
    trigger: { kind: 'product', value: 'p_x' },
    recommend: 'p_y',
    bonus: 300,
    script: '',
    advantages: [],
    abTest: null,
    pharmacies: 0,
    impressions: 0,
    accepts: 0,
    convRate: 0,
    revenue: 0,
    payout: 0,
    createdBy: '',
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

describe('BuilderForm — advantages textarea', () => {
  it('REPRO: ввод "А\\nБ\\n" должен сохранить trailing newline (юзер собирается печатать третий пункт)', () => {
    const onChange = vi.fn()
    const rule = mkRule({ advantages: ['А', 'Б'] })
    render(<BuilderForm value={rule} onChange={onChange} />)

    // Находим textarea «Преимущества»
    const advLabel = screen.getByText(/^Преимущества/)
    const textarea = advLabel.closest('label')!.querySelector('textarea')!

    // Имитируем нажатие Enter после Б — value становится "А\nБ\n" (пустая строка третья)
    fireEvent.change(textarea, { target: { value: 'А\nБ\n' } })

    // Хорошее поведение: onChange получает массив, в котором есть три элемента
    // (последний — пустая строка, где юзер сейчас будет печатать). До фикса фактически
    // приходит ['А','Б'] — пустая строка обрезается, юзер не может продолжить ввод.
    const lastCall = onChange.mock.calls.at(-1)?.[0]
    expect(lastCall.advantages).toEqual(['А', 'Б', ''])
  })

  it('REPRO: ввод "А\\n\\nБ" должен сохранить пустую строку между пунктами', () => {
    const onChange = vi.fn()
    const rule = mkRule({ advantages: ['А', 'Б'] })
    render(<BuilderForm value={rule} onChange={onChange} />)
    const textarea = screen
      .getByText(/^Преимущества/)
      .closest('label')!
      .querySelector('textarea')!

    fireEvent.change(textarea, { target: { value: 'А\n\nБ' } })

    // До фикса .filter(Boolean) убирает пустой элемент → ['А','Б'] (visual gap пропадает).
    const lastCall = onChange.mock.calls.at(-1)?.[0]
    expect(lastCall.advantages).toEqual(['А', '', 'Б'])
  })

  it('value=[] → textarea пустой', () => {
    const onChange = vi.fn()
    render(<BuilderForm value={mkRule({ advantages: [] })} onChange={onChange} />)
    const textarea = screen
      .getByText(/^Преимущества/)
      .closest('label')!
      .querySelector('textarea') as HTMLTextAreaElement
    expect(textarea.value).toBe('')
  })

  it('value=[3 строки] → textarea показывает их через \\n', () => {
    const rule = mkRule({ advantages: ['Один', 'Два', 'Три'] })
    render(<BuilderForm value={rule} onChange={vi.fn()} />)
    const textarea = screen
      .getByText(/^Преимущества/)
      .closest('label')!
      .querySelector('textarea') as HTMLTextAreaElement
    expect(textarea.value).toBe('Один\nДва\nТри')
  })
})

describe('BuilderForm — script textarea (sanity check)', () => {
  it('script сохраняется как-есть, без фильтрации', () => {
    const onChange = vi.fn()
    render(<BuilderForm value={mkRule()} onChange={onChange} />)
    const textarea = screen
      .getByText(/^Скрипт для покупателя/)
      .closest('label')!
      .querySelector('textarea')!
    fireEvent.change(textarea, { target: { value: 'Строка 1\n\nСтрока 3' } })
    const lastCall = onChange.mock.calls.at(-1)?.[0]
    expect(lastCall.script).toBe('Строка 1\n\nСтрока 3')
  })
})
