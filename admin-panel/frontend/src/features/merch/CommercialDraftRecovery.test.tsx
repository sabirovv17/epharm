import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import CommercialDraftRecovery from './CommercialDraftRecovery'
import type { CommercialGateway, CommercialPlanData } from './producer-commercial-api'

const draft: CommercialPlanData = {
  title: 'Current unsaved plan',
  startMonth: '2027-01-01',
  endMonth: '2027-01-01',
  budgetLimit: '9007199254740993',
  status: 'draft',
  notes: '',
  planningMode: 'shelves',
  productBriefs: [],
  shelfSelection: [],
  extraActivities: [],
  items: [],
}
const gateway = {
  realm: 'producer',
  save: vi.fn(),
  submit: vi.fn(),
} as unknown as CommercialGateway
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('explicit commercial draft import preview', () => {
  it('previews a new file without changing owner or saving until the user explicitly applies only plan fields', async () => {
    const recover = vi.fn()
    const user = userEvent.setup()
    render(
      <CommercialDraftRecovery
        initial={null}
        draft={draft}
        gateway={gateway}
        onRecover={recover}
      />,
    )
    await user.click(screen.getByText('Сохранение и восстановление локального черновика'))
    const file = new File(['synthetic'], 'draft.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', {
      value: async () =>
        JSON.stringify({
          ...draft,
          title: 'Imported synthetic plan',
          producerId: 'forged-other-owner',
          canEdit: true,
        }),
    })
    await user.upload(screen.getByLabelText('Импорт JSON черновика'), file)
    expect(await screen.findByText(/Проверка файла: Imported synthetic plan/)).toBeInTheDocument()
    expect(recover).not.toHaveBeenCalled()
    expect(gateway.save).not.toHaveBeenCalled()
    expect(
      screen.queryByRole('button', { name: 'Сохранить изменения в этой сессии' }),
    ).not.toBeInTheDocument()
    await user.click(
      screen.getByRole('button', { name: 'Перенести проверенные поля в текущий черновик' }),
    )
    expect(recover).toHaveBeenCalledWith({ ...draft, title: 'Imported synthetic plan' })
    expect(gateway.save).not.toHaveBeenCalled()
    expect(gateway.submit).not.toHaveBeenCalled()
  })

  it('rejects a file containing a credential without showing a transferable preview', async () => {
    const recover = vi.fn()
    const user = userEvent.setup()
    render(
      <CommercialDraftRecovery
        initial={null}
        draft={draft}
        gateway={gateway}
        onRecover={recover}
      />,
    )
    await user.click(screen.getByText('Сохранение и восстановление локального черновика'))
    const file = new File(['synthetic'], 'draft.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', {
      value: async () => JSON.stringify({ ...draft, password: 'synthetic-private-secret' }),
    })
    await user.upload(screen.getByLabelText('Импорт JSON черновика'), file)
    expect(await screen.findByRole('status')).toHaveTextContent('без паролей')
    expect(
      screen.queryByRole('button', { name: 'Перенести проверенные поля в текущий черновик' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('synthetic-private-secret')).not.toBeInTheDocument()
    expect(recover).not.toHaveBeenCalled()
  })
})
