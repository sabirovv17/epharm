// Тесты PeriodPicker — дефолт на текущий месяц, выбор месяца/года, «Текущий месяц».

import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useUiStore, currentPeriod } from '@/app/store'
import { PeriodPicker, formatPeriod, MONTHS } from './PeriodPicker'

beforeEach(() => {
  // Сбрасываем период к текущему месяцу перед каждым тестом (store — синглтон).
  useUiStore.setState({ period: currentPeriod() })
})

describe('PeriodPicker — дефолт', () => {
  it('по умолчанию показывает текущий месяц (реальная дата)', () => {
    render(<PeriodPicker />)
    expect(screen.getByTestId('period-picker')).toHaveTextContent(formatPeriod(currentPeriod()))
  })

  it('дропдаун закрыт изначально', () => {
    render(<PeriodPicker />)
    expect(screen.queryByTestId('period-dropdown')).not.toBeInTheDocument()
  })
})

describe('PeriodPicker — открытие/закрытие', () => {
  it('клик открывает дропдаун', async () => {
    const user = userEvent.setup()
    render(<PeriodPicker />)
    await user.click(screen.getByTestId('period-picker'))
    expect(screen.getByTestId('period-dropdown')).toBeInTheDocument()
  })

  it('Esc закрывает дропдаун', async () => {
    const user = userEvent.setup()
    render(<PeriodPicker />)
    await user.click(screen.getByTestId('period-picker'))
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('period-dropdown')).not.toBeInTheDocument()
  })
})

describe('PeriodPicker — выбор месяца', () => {
  it('клик по месяцу обновляет label и store', async () => {
    useUiStore.setState({ period: { year: 2026, month: 4 } }) // Май 2026
    const user = userEvent.setup()
    render(<PeriodPicker />)
    expect(screen.getByTestId('period-picker')).toHaveTextContent('Май 2026')

    await user.click(screen.getByTestId('period-picker'))
    await user.click(screen.getByTestId('period-month-0')) // Январь

    expect(screen.getByTestId('period-picker')).toHaveTextContent('Январь 2026')
    expect(useUiStore.getState().period).toEqual({ year: 2026, month: 0 })
    // дропдаун закрылся после выбора
    expect(screen.queryByTestId('period-dropdown')).not.toBeInTheDocument()
  })
})

describe('PeriodPicker — навигация по годам', () => {
  it('вперёд/назад меняет отображаемый год, выбор пишет этот год', async () => {
    useUiStore.setState({ period: { year: 2026, month: 4 } })
    const user = userEvent.setup()
    render(<PeriodPicker />)
    await user.click(screen.getByTestId('period-picker'))

    expect(screen.getByTestId('period-year')).toHaveTextContent('2026')
    await user.click(screen.getByTestId('period-year-next'))
    expect(screen.getByTestId('period-year')).toHaveTextContent('2027')
    await user.click(screen.getByTestId('period-year-prev'))
    await user.click(screen.getByTestId('period-year-prev'))
    expect(screen.getByTestId('period-year')).toHaveTextContent('2025')

    await user.click(screen.getByTestId('period-month-11')) // Декабрь 2025
    expect(useUiStore.getState().period).toEqual({ year: 2025, month: 11 })
    expect(screen.getByTestId('period-picker')).toHaveTextContent('Декабрь 2025')
  })
})

describe('PeriodPicker — «Текущий месяц»', () => {
  it('возвращает период к текущему месяцу', async () => {
    useUiStore.setState({ period: { year: 2020, month: 0 } })
    const user = userEvent.setup()
    render(<PeriodPicker />)
    expect(screen.getByTestId('period-picker')).toHaveTextContent('Январь 2020')

    await user.click(screen.getByTestId('period-picker'))
    await user.click(screen.getByTestId('period-current'))

    expect(useUiStore.getState().period).toEqual(currentPeriod())
    expect(screen.getByTestId('period-picker')).toHaveTextContent(formatPeriod(currentPeriod()))
  })
})

describe('formatPeriod', () => {
  it('форматирует «Месяц Год»', () => {
    expect(formatPeriod({ year: 2026, month: 4 })).toBe('Май 2026')
    expect(formatPeriod({ year: 2026, month: 11 })).toBe('Декабрь 2026')
    expect(MONTHS).toHaveLength(12)
  })
})
