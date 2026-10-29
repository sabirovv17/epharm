import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useUiStore } from '@/app/store'
import AccessManagement from './AccessManagement'
import SettingsPage from './SettingsPage'
import {
  accessApi,
  validateGrants,
  type AccessAccount,
  type AccessCatalog,
  type AccessGrant,
  type AccessProfile,
} from './access-api'

vi.mock('./access-api', async (original) => ({
  ...(await original<typeof import('./access-api')>()),
  accessApi: {
    me: vi.fn(),
    accounts: vi.fn(),
    people: vi.fn(),
    catalog: vi.fn(),
    profile: vi.fn(),
    create: vi.fn(),
    replace: vi.fn(),
  },
}))

const account: AccessAccount = {
  accountId: 'demo-admin-2',
  personId: 'demo-person-2',
  name: 'Тестовый сотрудник',
  email: 'demo@example.test',
  jobTitle: 'Тестовая должность',
  roleCeiling: 'HQ_HEAD',
  status: 'ACTIVE',
  managed: true,
  version: 6,
  primaryHqOwner: false,
  editable: true,
}
const grant = (action = 'view', pharmacyIds = ['ph-01']): AccessGrant => ({
  module: 'pharmacists',
  subsection: 'registry',
  action,
  allPharmacies: false,
  pharmacyIds,
})
const profile = (grants: AccessGrant[] = [grant()]): AccessProfile => ({
  accountId: account.accountId,
  personId: account.personId!,
  jobTitle: account.jobTitle,
  version: 7,
  grants,
})
function catalog(): AccessCatalog {
  return {
    assignableRoles: ['HQ_HEAD', 'TRAINING_MANAGER'],
    pharmacies: [
      { id: 'ph-01', name: 'Демо-аптека 01', city: 'Демо-город', address: 'Демо-адрес, 1' },
      { id: 'ph-02', name: 'Демо-аптека 02', city: 'Демо-город', address: 'Демо-адрес, 2' },
    ],
    permissions: ['view', 'edit'].map((action) => ({
      module: 'pharmacists',
      subsection: 'registry',
      action,
      moduleTitle: 'Фармацевты',
      subsectionTitle: 'Реестр',
      actionTitle: action === 'view' ? 'Просмотр реестра' : 'Изменение профиля',
      mode: 'SCOPED',
      availableForRoles: ['HQ_HEAD'],
      requiresPasswordReauthentication: false,
      delegatable: true,
      delegationScope: { allPharmacies: true, pharmacyIds: [] },
    })),
  }
}
function failure(status: number, fields?: Record<string, string>) {
  return new AxiosError('Synthetic response', undefined, undefined, undefined, {
    status,
    data: { code: 'VALIDATION_FAILED', message: 'Проверьте поля', fields },
    statusText: '',
    headers: {},
    config: {} as never,
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(accessApi.me).mockResolvedValue({
    managed: true,
    version: 1,
    grants: [],
    roleCeiling: 'HQ_HEAD',
    canViewAccounts: true,
    canManageAccounts: true,
  })
  vi.mocked(accessApi.accounts).mockResolvedValue([account])
  vi.mocked(accessApi.people).mockResolvedValue([
    {
      personId: 'demo-person-3',
      displayName: 'Проверенный демо-сотрудник',
      referenceLabel: 'Проверено',
    },
  ])
  vi.mocked(accessApi.catalog).mockResolvedValue(catalog())
  vi.mocked(accessApi.profile).mockResolvedValue(profile())
  vi.mocked(accessApi.create).mockResolvedValue({ ...profile([]), accountId: 'demo-admin-3' })
  vi.mocked(accessApi.replace).mockResolvedValue({ ...profile(), version: 8 })
})
async function editor() {
  const user = userEvent.setup()
  render(<AccessManagement />)
  await user.click(await screen.findByRole('button', { name: 'Настроить права' }))
  await screen.findByText(/Версия 7\. Каждое действие/)
  return user
}
async function creation() {
  const user = userEvent.setup()
  render(<AccessManagement />)
  await user.click(await screen.findByRole('button', { name: 'Создать аккаунт' }))
  return user
}
async function fillCreate(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(
    screen.getByRole('combobox', { name: 'Проверенный сотрудник' }),
    'demo-person-3',
  )
  await user.type(screen.getByLabelText('Имя сотрудника'), 'Новый демо-сотрудник')
  await user.type(screen.getByLabelText('Email нового аккаунта'), 'new@example.test')
  await user.selectOptions(
    screen.getByRole('combobox', { name: 'Роль — верхняя граница прав' }),
    'HQ_HEAD',
  )
  await user.type(screen.getByLabelText('Начальный пароль нового аккаунта'), 'SyntheticOnly-1234')
  await user.type(screen.getByLabelText(/Ваш текущий пароль/), 'SyntheticProof-1234')
}

describe('Server-authorized access management', () => {
  it('does not fetch accounts or show controls when the server denies management view', async () => {
    vi.mocked(accessApi.me).mockResolvedValue({
      managed: true,
      version: 1,
      grants: [],
      roleCeiling: 'HQ_HEAD',
      canViewAccounts: false,
      canManageAccounts: false,
    })
    render(<AccessManagement />)
    expect(
      await screen.findByText('У вас нет доступа к управлению аккаунтами.'),
    ).toBeInTheDocument()
    expect(accessApi.accounts).not.toHaveBeenCalled()
    expect(accessApi.people).not.toHaveBeenCalled()
    expect(accessApi.catalog).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Создать аккаунт' })).not.toBeInTheDocument()
  })
  it('shows unauthenticated failure without a successful empty list', async () => {
    vi.mocked(accessApi.me).mockRejectedValue(failure(401))
    render(<AccessManagement />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Сессия завершена')
    expect(screen.queryByText('Аккаунты не найдены.')).not.toBeInTheDocument()
    expect(accessApi.accounts).not.toHaveBeenCalled()
  })
  it('does not probe access for learner/anonymous or non-HQ settings users', () => {
    useUiStore.setState({ authedUser: null })
    const view = render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>,
    )
    expect(accessApi.me).not.toHaveBeenCalled()
    act(() =>
      useUiStore.setState({
        authedUser: {
          id: 'training-demo',
          name: 'Demo',
          email: 'training@example.test',
          company: 'Demo',
          role: 'TRAINING_MANAGER',
        },
      }),
    )
    view.rerender(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>,
    )
    expect(accessApi.me).not.toHaveBeenCalled()
    expect(screen.queryByText('Аккаунты и права доступа')).not.toBeInTheDocument()
  })
  it('requires explicit creation identity and role, creates no grants, clears successful form', async () => {
    const user = await creation()
    await user.click(screen.getByRole('button', { name: 'Создать без прав' }))
    expect(accessApi.create).not.toHaveBeenCalled()
    expect(screen.getByText('Выберите доступную роль.')).toBeInTheDocument()
    await fillCreate(user)
    await user.click(screen.getByRole('button', { name: 'Создать без прав' }))
    await waitFor(() => expect(accessApi.create).toHaveBeenCalledTimes(1))
    expect(accessApi.create).toHaveBeenCalledWith({
      email: 'new@example.test',
      name: 'Новый демо-сотрудник',
      role: 'HQ_HEAD',
      verifiedPersonId: 'demo-person-3',
      jobTitle: '',
      password: 'SyntheticOnly-1234',
      currentPassword: 'SyntheticProof-1234',
    })
    expect(
      await screen.findByText('Аккаунт создан без прав. Выберите его в списке и настройте доступ.'),
    ).toBeInTheDocument()
    expect(screen.queryByDisplayValue('SyntheticOnly-1234')).not.toBeInTheDocument()
  })
  it('blocks creation when verified people or assignable roles are empty', async () => {
    vi.mocked(accessApi.people).mockResolvedValue([])
    vi.mocked(accessApi.catalog).mockResolvedValue({ ...catalog(), assignableRoles: [] })
    await creation()
    expect(screen.getByText(/Нет проверенных сотрудников для привязки/)).toBeInTheDocument()
    expect(screen.getByText(/Нет доступных ролей/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Создать без прав' })).toBeDisabled()
    expect(accessApi.create).not.toHaveBeenCalled()
  })
  it('renders backend field errors beside input and drops password fields after rejected creation', async () => {
    vi.mocked(accessApi.create).mockRejectedValue(
      failure(400, {
        email: 'Этот email уже занят.',
        currentPassword: 'Подтвердите пароль ещё раз.',
      }),
    )
    const user = await creation()
    await fillCreate(user)
    await user.click(screen.getByRole('button', { name: 'Создать без прав' }))
    expect(await screen.findByText('Этот email уже занят.')).toBeInTheDocument()
    expect(screen.getByText('Подтвердите пароль ещё раз.')).toBeInTheDocument()
    expect(screen.getByLabelText(/Email нового аккаунта/)).toHaveValue('new@example.test')
    expect(screen.getByLabelText(/Начальный пароль нового аккаунта/)).toHaveValue('')
    expect(screen.getByLabelText(/Ваш текущий пароль/)).toHaveValue('')
  })
  it('shows unready or role-incompatible actions disabled and never auto-adds scope', async () => {
    const current = catalog()
    current.permissions.push({
      ...current.permissions[0],
      action: 'publish',
      actionTitle: 'Публикация',
      mode: 'NOT_READY',
    })
    current.permissions.push({
      ...current.permissions[0],
      action: 'approve',
      actionTitle: 'Подтверждение',
      availableForRoles: ['TRAINING_MANAGER'],
    })
    vi.mocked(accessApi.catalog).mockResolvedValue(current)
    const user = await editor()
    expect(screen.getByRole('checkbox', { name: /Публикация/ })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: /Подтверждение/ })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Изменение профиля' }))
    expect(screen.getByRole('radio', { name: 'Выбранные аптеки' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Вся сеть — явное разрешение' })).not.toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Проверить и сохранить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Пустой список не означает всю сеть')
    expect(accessApi.replace).not.toHaveBeenCalled()
  })
  it('keeps action scopes separate and uses fetched profile CAS version plus a fresh password', async () => {
    const user = await editor()
    await user.click(screen.getByRole('checkbox', { name: 'Изменение профиля' }))
    await user.click(
      screen.getByRole('checkbox', { name: 'Демо-город, Демо-аптека 02, Демо-адрес, 2' }),
    )
    await user.click(screen.getByRole('button', { name: 'Проверить и сохранить' }))
    const proof = screen.getByRole('button', { name: 'Подтвердить права' })
    expect(proof).toBeDisabled()
    expect(accessApi.replace).not.toHaveBeenCalled()
    await user.type(
      screen.getByLabelText('Ваш текущий пароль для сохранения'),
      'SyntheticProof-1234',
    )
    await user.click(proof)
    await waitFor(() =>
      expect(accessApi.replace).toHaveBeenCalledWith(
        account.accountId,
        7,
        [grant('view', ['ph-01']), grant('edit', ['ph-02'])],
        'SyntheticProof-1234',
      ),
    )
    expect(await screen.findByText('Права сохранены. Версия 8.')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('SyntheticProof-1234')).not.toBeInTheDocument()
  })
  it('does not retry stale writes automatically, preserves the draft until explicit reload', async () => {
    vi.mocked(accessApi.replace).mockRejectedValueOnce(failure(409))
    const user = await editor()
    await user.click(screen.getByRole('button', { name: 'Проверить и сохранить' }))
    await user.type(
      screen.getByLabelText('Ваш текущий пароль для сохранения'),
      'SyntheticProof-1234',
    )
    await user.click(screen.getByRole('button', { name: 'Подтвердить права' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Данные уже изменились')
    expect(accessApi.replace).toHaveBeenCalledTimes(1)
    expect(accessApi.profile).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Проверить и сохранить' })).toBeDisabled()
    expect(screen.getByLabelText('Ваш текущий пароль для сохранения')).toHaveValue('')
    vi.mocked(accessApi.profile).mockResolvedValueOnce({ ...profile(), version: 9 })
    await user.click(screen.getByRole('button', { name: 'Загрузить актуальные права' }))
    expect(await screen.findByText(/Версия 9\. Каждое действие/)).toBeInTheDocument()
    expect(accessApi.replace).toHaveBeenCalledTimes(1)
  })
  it('requires explicit fresh-password confirmation to revoke all rights', async () => {
    vi.mocked(accessApi.replace).mockResolvedValue({ ...profile([]), version: 8 })
    const user = await editor()
    await user.click(screen.getByRole('checkbox', { name: 'Просмотр реестра' }))
    await user.click(screen.getByRole('button', { name: 'Проверить и сохранить' }))
    expect(screen.getByText(/Все управляемые права аккаунта будут отозваны/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Отозвать все права' })).toBeDisabled()
    await user.type(
      screen.getByLabelText('Ваш текущий пароль для сохранения'),
      'SyntheticProof-1234',
    )
    await user.click(screen.getByRole('button', { name: 'Отозвать все права' }))
    await waitFor(() =>
      expect(accessApi.replace).toHaveBeenCalledWith(
        account.accountId,
        7,
        [],
        'SyntheticProof-1234',
      ),
    )
  })
  it('renders a management reader without create/save controls', async () => {
    vi.mocked(accessApi.me).mockResolvedValue({
      managed: true,
      version: 1,
      grants: [],
      roleCeiling: 'HQ_HEAD',
      canViewAccounts: true,
      canManageAccounts: false,
    })
    render(<AccessManagement />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Посмотреть права' }))
    await screen.findByText('Только просмотр. Изменение этого аккаунта недоступно.')
    expect(screen.queryByRole('button', { name: 'Создать аккаунт' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Проверить и сохранить' })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Просмотр реестра' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Аптеки: 1' }))
    expect(screen.getByText(/ID: ph-01/)).toBeInTheDocument()
  })
  it('aborts an unfinished capability request when leaving settings', () => {
    vi.mocked(accessApi.me).mockImplementation(() => new Promise(() => {}))
    const view = render(<AccessManagement />)
    const signal = vi.mocked(accessApi.me).mock.calls[0][0]
    view.unmount()
    expect(signal.aborted).toBe(true)
  })

  it('never puts access metadata or entered passwords into browser storage', async () => {
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem')
    const user = await creation()
    await fillCreate(user)
    expect(storageWrite).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByDisplayValue('SyntheticOnly-1234')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('SyntheticProof-1234')).not.toBeInTheDocument()
    expect(storageWrite).not.toHaveBeenCalled()
    storageWrite.mockRestore()
  })
})

describe('Grant boundary validation', () => {
  it('allows empty replacement only as revocation; never interprets an empty selected scope as ALL', () => {
    expect(validateGrants([], catalog(), 'HQ_HEAD')).toBeNull()
    expect(validateGrants([grant('view', [])], catalog(), 'HQ_HEAD')).toContain('Пустой список')
    expect(validateGrants([{ ...grant(), allPharmacies: true }], catalog(), 'HQ_HEAD')).toContain(
      'одну область',
    )
  })
  it('rejects unknown permissions, role mismatch, unknown pharmacies and duplicated grants', () => {
    expect(validateGrants([grant('unknown')], catalog(), 'HQ_HEAD')).toBeTruthy()
    expect(validateGrants([grant()], catalog(), 'TRAINING_MANAGER')).toContain('выбранной роли')
    expect(validateGrants([grant('view', ['unknown'])], catalog(), 'HQ_HEAD')).toContain(
      'подтверждённые аптеки',
    )
    expect(validateGrants([grant(), grant()], catalog(), 'HQ_HEAD')).toContain('повторное')
  })
  it('never enlarges the delegator scope or enables NOT_READY', () => {
    const current = catalog()
    current.permissions[0].delegationScope = { allPharmacies: false, pharmacyIds: ['ph-01'] }
    expect(validateGrants([grant('view', ['ph-02'])], current, 'HQ_HEAD')).toContain(
      'превышает ваши права',
    )
    expect(
      validateGrants([{ ...grant('view', []), allPharmacies: true }], current, 'HQ_HEAD'),
    ).toContain('превышает ваши права')
    current.permissions[0].delegatable = false
    expect(validateGrants([grant()], current, 'HQ_HEAD')).toContain('нет права выдавать')
    current.permissions[0].mode = 'NOT_READY'
    expect(validateGrants([grant()], current, 'HQ_HEAD')).toContain('ещё не подключено')
  })
})
