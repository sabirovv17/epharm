// Тесты ContractModal — guard'ы на отсутствие контракта.
// Регрессионный bag: до этого падал с TypeError при пустом CONTRACT.approvedBy.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ToastHost } from '@/ui'
import { ContractModal } from './ContractModal'
import { USERS } from '@/mocks/fixtures'

function setup(props: Partial<Parameters<typeof ContractModal>[0]> = {}) {
  return render(
    <ToastHost>
      <ContractModal open onClose={vi.fn()} user={null} {...props} />
    </ToastHost>,
  )
}

describe('ContractModal — guard на отсутствие контракта', () => {
  it('user=null → ничего не рендерится (нет крашa)', () => {
    const { container } = setup({ user: null, open: true })
    // body модалки не должен появиться
    expect(container.textContent).not.toMatch(/Контракт/i)
    expect(container.textContent).not.toMatch(/Бренды в контракте/i)
  })

  it('user без контракта (aigerim, CATEGORY_LEAD) → ничего не рендерится', () => {
    const { container } = setup({ user: USERS.aigerim, open: true })
    expect(container.textContent).not.toMatch(/Контракт/i)
  })

  it('user без контракта (bauyrzhan, HQ_HEAD) → ничего не рендерится', () => {
    const { container } = setup({ user: USERS.bauyrzhan, open: true })
    expect(container.textContent).not.toMatch(/Контракт/i)
  })

  it('даже при open=false и user=null → не падает', () => {
    expect(() => setup({ user: null, open: false })).not.toThrow()
  })
})

describe('ContractModal — render с контрактом', () => {
  it('user=damir → модалка показана с пустыми данными CONTRACT (которые сейчас все нули)', () => {
    // damir на MVP не имеет контракта (userHasContract=false), поэтому модалка тоже не открывается.
    // Когда подключим backend и contract придёт реальный — этот тест поменяется.
    setup({ user: USERS.damir, open: true })
    expect(screen.queryByText(/Контракт ·/i)).not.toBeInTheDocument()
  })
})
