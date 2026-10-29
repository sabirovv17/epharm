import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import IdentityManagement from './IdentityManagement'
import {
  accessApi,
  type AccessAccount,
  type AccessCatalog,
  type SourceAccessAccount,
  type VerifiedPerson,
} from './access-api'
vi.mock('./access-api', async (original) => ({
  ...(await original<typeof import('./access-api')>()),
  accessApi: {
    sourceAccounts: vi.fn(),
    verifyPerson: vi.fn(),
    bindSource: vi.fn(),
    provisionSource: vi.fn(),
    bindPharmacist: vi.fn(),
    pharmacistOptions: vi.fn(),
  },
}))
const person: VerifiedPerson = {
  personId: 'person-demo',
  displayName: 'Демо Проверенный',
  referenceLabel: 'Связь подтверждена',
}
const account: AccessAccount = {
  accountId: 'account-demo',
  personId: person.personId,
  name: 'Демо Проверенный',
  email: 'demo@example.test',
  jobTitle: '',
  roleCeiling: 'HQ_HEAD',
  status: 'active',
  managed: true,
  version: 1,
  primaryHqOwner: false,
  editable: true,
}
const source: SourceAccessAccount = {
  sourceAccountId: 'source-demo',
  name: account.name,
  email: account.email,
  sourceRole: 'mas',
  active: false,
  hasPassword: true,
  accountId: null,
  personId: null,
}
const catalog: AccessCatalog = {
  permissions: [],
  pharmacies: [],
  assignableRoles: ['REGIONAL_MANAGER', 'TRAINING_MANAGER'],
}
const changed = vi.fn()
function mount(canManage = true, people = [person]) {
  return render(
    <IdentityManagement
      accounts={[
        account,
        {
          ...account,
          accountId: 'same-name-other-person',
          personId: 'different-person',
          email: 'other@example.test',
        },
      ]}
      people={people}
      catalog={catalog}
      canManage={canManage}
      onChanged={changed}
    />,
  )
}
async function proof(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Основание проверки личности'), 'DEMO-request-1')
  await user.click(screen.getByRole('checkbox', { name: /Я проверил личность/ }))
  await user.type(
    screen.getByLabelText('Текущий пароль подтверждения личности'),
    'synthetic-proof-only',
  )
}
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  vi.mocked(accessApi.sourceAccounts).mockResolvedValue([source])
  vi.mocked(accessApi.verifyPerson).mockResolvedValue(person)
  vi.mocked(accessApi.provisionSource).mockResolvedValue({
    accountId: 'new-demo',
    personId: person.personId,
    jobTitle: '',
    version: 0,
    grants: [],
  })
  vi.mocked(accessApi.bindSource).mockResolvedValue({})
  vi.mocked(accessApi.bindPharmacist).mockResolvedValue({})
  vi.mocked(accessApi.pharmacistOptions).mockResolvedValue({
    items: [
      {
        id: 'pharmacist-demo',
        name: 'Демо Фармацевт',
        pharmacyName: 'Демо-аптека',
        personId: null,
      },
    ],
    limit: 50,
    offset: 0,
    hasMore: true,
  })
})
describe('explicit verified identity and source-account operations', () => {
  it('creates a verified person only after explicit evidence, password and acknowledgement; nothing is persisted in browser storage', async () => {
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('button', { name: 'Подтвердить нового человека' }))
    expect(screen.getByRole('button', { name: 'Подтвердить действие' })).toBeDisabled()
    await user.type(screen.getByLabelText('ФИО подтверждаемого человека'), 'Демо Новый')
    await proof(user)
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    await waitFor(() =>
      expect(accessApi.verifyPerson).toHaveBeenCalledExactlyOnceWith({
        name: 'Демо Новый',
        verificationReference: 'DEMO-request-1',
        currentPassword: 'synthetic-proof-only',
      }),
    )
    expect(changed).toHaveBeenCalledOnce()
    expect(
      JSON.stringify([Object.values(localStorage), Object.values(sessionStorage)]),
    ).not.toMatch(/synthetic-proof|DEMO-request/)
  })
  it('never guesses a human match from name/email; binding requires a selected person and that person’s exact managed account', async () => {
    const user = userEvent.setup()
    mount()
    await user.click(await screen.findByRole('button', { name: 'Сопоставить аккаунт' }))
    const people = screen.getByRole('combobox', { name: 'Проверенный человек для сопоставления' })
    expect(people).toHaveValue('')
    await user.selectOptions(people, person.personId)
    const target = screen.getByRole('combobox', { name: 'Целевой административный аккаунт' })
    expect(within(target).queryByRole('option', { name: /other@example/ })).not.toBeInTheDocument()
    expect(target).toHaveValue('')
    await user.selectOptions(target, account.accountId)
    await proof(user)
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    await waitFor(() =>
      expect(accessApi.bindSource).toHaveBeenCalledExactlyOnceWith(source.sourceAccountId, {
        targetAccountId: account.accountId,
        verifiedPersonId: person.personId,
        verificationReference: 'DEMO-request-1',
        currentPassword: 'synthetic-proof-only',
      }),
    )
    expect(accessApi.provisionSource).not.toHaveBeenCalled()
  })
  it('provisions an explicitly chosen role without grants and preserves the inactive source warning', async () => {
    const user = userEvent.setup()
    mount()
    await user.click(await screen.findByRole('button', { name: 'Перенести без прав' }))
    const role = screen.getByRole('combobox', { name: 'Роль переносимого аккаунта' })
    expect(role).toHaveValue('')
    expect(
      within(role).queryByRole('option', { name: /Главный администратор/ }),
    ).not.toBeInTheDocument()
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Проверенный человек для сопоставления' }),
      person.personId,
    )
    await user.selectOptions(role, 'REGIONAL_MANAGER')
    await proof(user)
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    await waitFor(() => expect(accessApi.provisionSource).toHaveBeenCalledOnce())
    const body = vi.mocked(accessApi.provisionSource).mock.calls[0][1]
    expect(body).toMatchObject({
      role: 'REGIONAL_MANAGER',
      verifiedPersonId: person.personId,
      email: source.email,
    })
    expect(body).not.toHaveProperty('grants')
    expect(body).not.toHaveProperty('active')
    expect(body).not.toHaveProperty('password')
  })
  it('clears password after a conflict, never repeats the write and shows field errors', async () => {
    vi.mocked(accessApi.verifyPerson).mockRejectedValue(
      new AxiosError('Conflict', undefined, undefined, undefined, {
        status: 409,
        data: { message: 'Данные изменились', fields: { name: 'Уточните ФИО' } },
        config: {} as never,
        headers: {},
        statusText: '',
      }),
    )
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('button', { name: 'Подтвердить нового человека' }))
    await user.type(screen.getByLabelText('ФИО подтверждаемого человека'), 'Демо Новый')
    await proof(user)
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Текущий пароль подтверждения личности')).toHaveValue('')
    expect(screen.getByText('Уточните ФИО')).toBeInTheDocument()
    expect(accessApi.verifyPerson).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: 'Отмена' }))
    await user.click(screen.getByRole('button', { name: 'Подтвердить нового человека' }))
    expect(screen.getByLabelText('Текущий пароль подтверждения личности')).toHaveValue('')
  })
  it('keeps advertisers in their separate SMS realm and cannot invent people when none are verified', async () => {
    vi.mocked(accessApi.sourceAccounts).mockResolvedValue([{ ...source, sourceRole: 'advertiser' }])
    mount(true, [])
    await screen.findByText('Отдельный кабинет производителя по SMS')
    expect(screen.queryByRole('button', { name: 'Перенести без прав' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сопоставить аккаунт' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Связать фармацевта' })).toBeDisabled()
    expect(accessApi.verifyPerson).not.toHaveBeenCalled()
  })
  it('links only a server-selected pharmacist; paginated search does not silently truncate or accept an arbitrary id', async () => {
    const user = userEvent.setup()
    mount()
    await user.click(screen.getByRole('button', { name: 'Связать фармацевта' }))
    await user.click(await screen.findByRole('button', { name: 'Далее' }))
    await waitFor(() =>
      expect(accessApi.pharmacistOptions).toHaveBeenLastCalledWith('', 50, expect.any(AbortSignal)),
    )
    await user.type(screen.getByLabelText('Поиск фармацевта для связи'), 'Демо%')
    await user.click(screen.getByRole('button', { name: 'Найти' }))
    await waitFor(() =>
      expect(accessApi.pharmacistOptions).toHaveBeenLastCalledWith(
        'Демо%',
        0,
        expect.any(AbortSignal),
      ),
    )
    await user.click(await screen.findByRole('button', { name: 'Демо Фармацевт · Демо-аптека' }))
    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Проверенный человек для сопоставления' }),
      person.personId,
    )
    await proof(user)
    await user.click(screen.getByRole('button', { name: 'Подтвердить действие' }))
    await waitFor(() =>
      expect(accessApi.bindPharmacist).toHaveBeenCalledExactlyOnceWith({
        pharmacistId: 'pharmacist-demo',
        verifiedPersonId: person.personId,
        verificationReference: 'DEMO-request-1',
        currentPassword: 'synthetic-proof-only',
      }),
    )
  })
  it('shows read-only source rows without management controls when account editing is denied', async () => {
    mount(false)
    await screen.findByText(source.email!)
    expect(
      screen.queryByRole('button', { name: 'Подтвердить нового человека' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сопоставить аккаунт' })).not.toBeInTheDocument()
  })
})
