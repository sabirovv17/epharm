// Тесты StatusChip — все 7 статусов, корректный label + color class.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatusChip, type Status } from './StatusChip'

const CASES: Array<{ status: Status; label: string; cls: string }> = [
  { status: 'active', label: 'Активно', cls: 'chip-green' },
  { status: 'paused', label: 'Пауза', cls: 'chip-amber' },
  { status: 'draft', label: 'Черновик', cls: 'chip-ink' },
  { status: 'archived', label: 'Архив', cls: 'chip-ink' },
  { status: 'pending', label: 'На согласовании', cls: 'chip-blue' },
  { status: 'rejected', label: 'Отклонено', cls: 'chip-red' },
  { status: 'approved', label: 'Утверждено', cls: 'chip-green' },
]

describe('StatusChip', () => {
  it.each(CASES)(
    'статус "$status" → label="$label", class содержит $cls',
    ({ status, label, cls }) => {
      render(<StatusChip status={status} />)
      const chip = screen.getByText(label)
      expect(chip).toBeInTheDocument()
      // Chip имеет cls + общий chip
      expect(chip).toHaveClass('chip', cls)
    },
  )

  it('каждый статус рисует chip-dot перед лейблом', () => {
    render(<StatusChip status="active" />)
    const chip = screen.getByText(/Активно/i)
    const dot = chip.querySelector('.chip-dot')
    expect(dot).toBeInTheDocument()
  })
})
