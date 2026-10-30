import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import AdminStaffAccessPanel from './AdminStaffAccessPanel'
import type { adminStaffAccessApi, StaffAccessCode } from './staff-access-api'
vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,synthetic') },
}))
const grant: StaffAccessCode = {
  codeId: 'code-one',
  organizationId: 'org',
  placementMonth: '2099-01-01',
  branchIds: ['branch'],
  allOrgBranches: false,
  deadlineAt: null,
  status: 'active',
  maxUses: 1,
  useCount: 0,
  qrUseCount: 0,
  createdAt: '2099-01-01T00:00:00Z',
  lastUsedAt: null,
  hasQrToken: true,
  manualRevoked: false,
  exhausted: false,
  expired: false,
  canRevoke: true,
}
const branch = {
  id: 'branch',
  code: '1',
  name: 'Демо-аптека',
  city: 'Город',
  address: 'Адрес',
  canCreate: true,
}
let api: typeof adminStaffAccessApi
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  api = {
    options: vi
      .fn()
      .mockResolvedValue({ organizations: [{ id: 'org', name: 'Демо-сеть', branches: [branch] }] }),
    settings: vi.fn().mockResolvedValue({
      organizationId: 'org',
      organizationName: 'Демо-сеть',
      placementMonth: '2099-01-01',
      branches: [branch],
      codes: [grant],
      canCreate: true,
    }),
    create: vi.fn().mockResolvedValue({
      grant,
      code: '123-456',
      accessToken: 'synthetic_staff_access_000000000000000000000000',
    }),
    revoke: vi.fn().mockResolvedValue({ ...grant, manualRevoked: true, canRevoke: false }),
  }
})
async function selectOrganization() {
  const user = userEvent.setup()
  await screen.findByRole('option', { name: 'Демо-сеть' })
  await user.selectOptions(screen.getByRole('combobox', { name: 'Аптечная организация' }), 'org')
  fireEvent.change(screen.getByLabelText('Месяц доступа'), { target: { value: '2099-01' } })
  await screen.findByText(/Доступных записей за месяц/)
  return user
}
it('without fresh view capability the panel performs no admin/options requests', () => {
  render(<AdminStaffAccessPanel enabled={false} principalKey="one" api={api} />)
  expect(api.options).not.toHaveBeenCalled()
  expect(api.settings).not.toHaveBeenCalled()
})
it('creates only explicit eligible branches and shows code/QR once without storage; principal switch clears them', async () => {
  const { rerender } = render(<AdminStaffAccessPanel enabled principalKey="one" api={api} />)
  const user = await selectOrganization()
  expect(screen.queryByText('123-456')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Создать код и QR' })).toBeDisabled()
  await user.click(screen.getByRole('checkbox', { name: 'Демо-аптека · Город · Адрес' }))
  await user.click(screen.getByRole('button', { name: 'Создать код и QR' }))
  expect(await screen.findByText('123-456')).toBeInTheDocument()
  expect(api.create).toHaveBeenCalledExactlyOnceWith({
    organizationId: 'org',
    placementMonth: '2099-01-01',
    branchIds: ['branch'],
    deadlineAt: null,
  })
  expect(sessionStorage.length + localStorage.length).toBe(0)
  expect(
    await screen.findByRole('img', { name: 'QR временного доступа сотрудника' }),
  ).toBeInTheDocument()
  rerender(<AdminStaffAccessPanel enabled principalKey="two" api={api} />)
  expect(screen.queryByText('123-456')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('img', { name: 'QR временного доступа сотрудника' }),
  ).not.toBeInTheDocument()
})
it('view-only scope preserves closed history and never invents create or revoke permissions', async () => {
  vi.mocked(api.settings).mockResolvedValue({
    organizationId: 'org',
    organizationName: 'Демо-сеть',
    placementMonth: '2099-01-01',
    branches: [{ ...branch, canCreate: false }],
    codes: [{ ...grant, expired: true, canRevoke: false }],
    canCreate: false,
  })
  render(<AdminStaffAccessPanel enabled principalKey="view-only" api={api} />)
  await selectOrganization()
  expect(screen.getByText('Срок истёк')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Создать код и QR' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Отозвать доступ' })).not.toBeInTheDocument()
  expect(api.create).not.toHaveBeenCalled()
  expect(api.revoke).not.toHaveBeenCalled()
})
it('explicit revoke confirms one selected grant and does not create another code', async () => {
  render(<AdminStaffAccessPanel enabled principalKey="one" api={api} />)
  const user = await selectOrganization()
  await user.click(screen.getByRole('button', { name: 'Отозвать доступ' }))
  expect(api.revoke).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Подтвердить отзыв' }))
  await waitFor(() => expect(api.revoke).toHaveBeenCalledExactlyOnceWith('code-one'))
  expect(api.create).not.toHaveBeenCalled()
})
