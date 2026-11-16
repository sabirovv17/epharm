import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ToastHost } from '@/ui'
import type { PharmacistDto, UnmappedPosmSellerDto } from '@/lib/api-types'
import { StandardNSellerMappingsModal } from './StandardNSellerMappingsModal'

const hooks = vi.hoisted(() => ({
  useStandardNPharmacistMappings: vi.fn(),
  useUnmappedStandardNSellers: vi.fn(),
  useUpsertStandardNPharmacistMapping: vi.fn(),
  useRevokeStandardNPharmacistMapping: vi.fn(),
}))
vi.mock('@/lib/queries/pharmacists', () => hooks)
const save = vi.fn()
const retry = vi.fn()
const person: PharmacistDto = {
  id: 'person-a', name: 'Synthetic pharmacist', iin: null, phone: null,
  pharmacyId: 'ph-a', pharmacyName: 'Synthetic pharmacy', city: 'Synthetic city',
  tier: 'Gold', receipts30d: null, rulesAccepted30d: null, earned30d: null, balance: null,
  coursesDone: 0, coursesTotal: 0, status: 'active', hasPassword: true,
  joinedAt: '2026-01-01', createdAt: '2026-01-01', updatedAt: '2026-01-01',
}
const seller: UnmappedPosmSellerDto = {
  pharmacyId: 'ph-a', pharmacyName: 'Synthetic pharmacy', externalUserId: '17',
  externalUserName: 'Observed source name', salesCount: 5, lastSeenAt: '2026-11-16T10:00:00Z',
  reason: 'confirmation_required', activePharmacistCount: 1, observedNameCount: 1,
}
function sellers(rows: UnmappedPosmSellerDto[], extra: Record<string, unknown> = {}) {
  hooks.useUnmappedStandardNSellers.mockReturnValue({ data: rows, isLoading: false, isError: false, isFetching: false, refetch: retry, ...extra })
}
function view(people: PharmacistDto[] = [person]) {
  return <ToastHost><StandardNSellerMappingsModal pharmacists={people} onClose={vi.fn()} /></ToastHost>
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  sellers([seller])
  hooks.useStandardNPharmacistMappings.mockReturnValue({ data: [], isLoading: false, isError: false, isFetching: false, refetch: vi.fn() })
  hooks.useUpsertStandardNPharmacistMapping.mockReturnValue({ mutate: save, isPending: false })
  hooks.useRevokeStandardNPharmacistMapping.mockReturnValue({ mutate: vi.fn(), isPending: false })
})
afterEach(() => vi.restoreAllMocks())

describe('HQ Standard-N seller confirmation', () => {
  it('offers only active same-pharmacy people and confirms the exact scope before writing', async () => {
    render(view([person, { ...person, id: 'blocked', status: 'blocked' }, { ...person, id: 'foreign', pharmacyId: 'ph-b' }]))
    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(save).not.toHaveBeenCalled()
    await userEvent.selectOptions(screen.getByRole('combobox'), person.id)
    await userEvent.click(screen.getByRole('button', { name: 'Связать' }))
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Synthetic pharmacy\nStandard-N USER_ID: 17\nФармацевт ePharm: Synthetic pharmacist'))
    expect(save).toHaveBeenCalledWith({ pharmacyId: 'ph-a', externalUserId: '17', externalUserName: 'Observed source name', pharmacistId: 'person-a' }, expect.any(Object))
    act(() => save.mock.calls[0][1].onSuccess())
    expect(screen.getByRole('button', { name: 'Связать' })).toBeDisabled()
    expect(screen.getByRole('combobox')).toHaveValue('')
  })
  it('does not write if explicit confirmation is declined', async () => {
    vi.mocked(window.confirm).mockReturnValue(false)
    render(view())
    await userEvent.selectOptions(screen.getByRole('combobox'), person.id)
    await userEvent.click(screen.getByRole('button', { name: 'Связать' }))
    expect(save).not.toHaveBeenCalled()
  })
  it.each(['', '   '])('never maps a missing external identifier %j', async (externalUserId) => {
    sellers([{ ...seller, externalUserId, reason: 'missing_user_id' }])
    render(view())
    expect(screen.getByText('USER_ID не передан')).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Связать' }))
    expect(save).not.toHaveBeenCalled()
    expect(window.confirm).not.toHaveBeenCalled()
  })
  it.each(['blocked', 'moved', 'removed'])('invalidates a stale candidate that becomes %s', async (change) => {
    const { rerender } = render(view())
    await userEvent.selectOptions(screen.getByRole('combobox'), person.id)
    const people = change === 'removed' ? [] : [{ ...person, ...(change === 'blocked' ? { status: 'blocked' as const } : { pharmacyId: 'ph-b' }) }]
    rerender(view(people))
    expect(screen.getByText(/Выбранный профиль больше не активен/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Связать' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Связать' }))
    expect(save).not.toHaveBeenCalled()
  })
  it.each([
    ['source_conflict', 'Конфликт источника и профиля'],
    ['inactive_recipient', 'Прежний получатель неактивен'],
    ['no_active_profiles', 'В аптеке нет активных профилей'],
    ['missing_name', 'Имя кассира не передано'],
    ['name_not_matched', 'Имя не сопоставлено однозначно'],
  ])('explains %s without automatically selecting a recipient', (reason, text) => {
    sellers([{ ...seller, reason }])
    render(view())
    expect(screen.getByText(new RegExp(text))).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveValue('')
    expect(save).not.toHaveBeenCalled()
  })
  it('warns about multiple observed names and treats unknown reasons conservatively', () => {
    sellers([{ ...seller, reason: 'unsafe server diagnostic text', observedNameCount: 2 }])
    render(view())
    expect(screen.getByText(/передавались разные имена \(2\)/)).toBeInTheDocument()
    expect(screen.getByText(/Требуется явное подтверждение HQ/)).toBeInTheDocument()
    expect(screen.queryByText('unsafe server diagnostic text')).not.toBeInTheDocument()
  })
  it('does not claim all sellers are verified when the discovery queue is empty', () => {
    sellers([])
    render(view())
    expect(screen.getByText('Нет продавцов в очереди сопоставления')).toBeInTheDocument()
    expect(screen.queryByText('Все продавцы сопоставлены')).not.toBeInTheDocument()
    expect(screen.getByText(/не подтверждает личность всех продавцов/)).toBeInTheDocument()
  })
  it('shows a retryable error rather than stale actionable sellers', async () => {
    sellers([seller], { isError: true, error: new Error('Synthetic failure') })
    render(view())
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Связать' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(retry).toHaveBeenCalledOnce()
  })
  it('disables save while refreshing the diagnostic evidence', async () => {
    const { rerender } = render(view())
    await userEvent.selectOptions(screen.getByRole('combobox'), person.id)
    sellers([seller], { isFetching: true })
    rerender(view())
    expect(screen.getByRole('button', { name: 'Связать' })).toBeDisabled()
  })
})
