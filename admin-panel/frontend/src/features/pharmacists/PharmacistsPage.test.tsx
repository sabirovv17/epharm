// Тесты PharmacistsPage — пустой/loading/error/полный список + block/unblock.

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PharmacistDto } from '@/lib/api-types'
import { useUiStore } from '@/app/store'
import PharmacistsPage from './PharmacistsPage'

const pharmacistHooks = vi.hoisted(() => ({
  usePharmacists: vi.fn(),
  usePharmacist: vi.fn(),
  useCreatePharmacist: vi.fn(),
  useUpdatePharmacist: vi.fn(),
  useActivatePharmacist: vi.fn(),
  useBlockPharmacist: vi.fn(),
  useUnblockPharmacist: vi.fn(),
  useStandardNPharmacistMappings: vi.fn(),
  useUnmappedStandardNSellers: vi.fn(),
  useUpsertStandardNPharmacistMapping: vi.fn(),
  useRevokeStandardNPharmacistMapping: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacists', () => pharmacistHooks)

const pharmacyHooks = vi.hoisted(() => ({
  usePharmacies: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

const lmsHooks = vi.hoisted(() => ({
  useTrainingPreferences: vi.fn(),
  useTrainingPreferenceHistory: vi.fn(),
  useChangeTrainingPreference: vi.fn(),
  useMassChangeTrainingPreferences: vi.fn(),
  useTrainingDashboard: vi.fn(),
  usePharmacistTrainingProfile: vi.fn(),
}))

vi.mock('@/lib/queries/lms', () => lmsHooks)
const accessHooks = vi.hoisted(() => ({ useAdministrativeCapabilities: vi.fn() }))
vi.mock('@/app/useAdministrativeCapabilities', () => accessHooks)
const originalUser = useUiStore.getState().authedUser
afterEach(() => {
  act(() => useUiStore.setState({ authedUser: originalUser }))
})

function mkPharmacist(over: Partial<PharmacistDto> = {}): PharmacistDto {
  return {
    id: 'u_1',
    name: 'Айгерим Касенова',
    iin: '950101000001',
    phone: '+7 (701) 100-10-20',
    pharmacyId: 'ph_1',
    pharmacyName: 'Europharma №100',
    city: 'Алматы',
    tier: 'Gold',
    receipts30d: 50,
    rulesAccepted30d: 15,
    earned30d: 80_000,
    balance: 50_000,
    coursesDone: 3,
    coursesTotal: 9,
    status: 'active',
    hasPassword: true,
    joinedAt: '2026-01-15',
    createdAt: '2026-01-15T00:00:00Z',
    updatedAt: '2026-01-15T00:00:00Z',
    ...over,
  }
}

function setPharmacists(list: PharmacistDto[] = []) {
  pharmacistHooks.usePharmacists.mockReturnValue({
    data: list,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  accessHooks.useAdministrativeCapabilities.mockReturnValue({
    canExportLearnerCredentials: false,
    canExportPharmacists: false,
    data: { version: 1 },
  })
  useUiStore.setState({
    authedUser: {
      id: 'hq-test',
      email: 'hq@test.invalid',
      name: 'HQ',
      company: 'Test',
      role: 'HQ_HEAD',
    },
  })
  setPharmacists([])
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [
      {
        id: 'ph_1',
        name: 'Ауэзова 134',
        chainId: 'inkar',
        chainName: 'Inkar',
        city: 'Алматы',
        district: '',
        addr: '',
        group: 'pilot',
        pharmacists: 0,
        receipts30d: 0,
        gmv30d: 0,
        liftPct: 0,
        rulesAccepted: 0,
        active: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ],
  })
  pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useCreatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useBlockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useTrainingPreferences.mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    isSuccess: true,
  })
  lmsHooks.useTrainingPreferenceHistory.mockReturnValue({ data: [], isLoading: false })
  lmsHooks.useChangeTrainingPreference.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useMassChangeTrainingPreferences.mockReturnValue({ mutate: vi.fn(), isPending: false })
  lmsHooks.useTrainingDashboard.mockReturnValue({
    data: {
      capabilities: {
        canManagePrograms: true,
        canManageAssignments: true,
        canManageEvents: true,
        canMarkAttendance: true,
        canAdjustRewards: false,
        canRecordResults: true,
        canManagePreferences: true,
        canExport: true,
        regionalScope: [],
      },
    },
    isLoading: false,
    isError: false,
  })
  lmsHooks.usePharmacistTrainingProfile.mockReturnValue({
    data: {
      pharmacistId: 'u_1',
      pharmacistName: 'Айгерим Касенова',
      pharmacyName: 'Europharma №100',
      city: 'Алматы',
      defaultFormat: 'hybrid',
      totalAssignments: 1,
      completedAssignments: 1,
      inProgressAssignments: 0,
      totalRewards: 5_000,
      assignments: [],
      certificates: [
        {
          id: 'certificate-1',
          number: 'EPH-2026-001',
          assignmentId: 'assignment-1',
          pharmacistId: 'u_1',
          pharmacistName: 'Айгерим Касенова',
          programName: 'Работа с продуктом',
          format: 'hybrid',
          issuedAt: '2026-08-01T10:00:00Z',
          expiresAt: null,
          score: 92,
          signerName: 'Руководитель обучения',
          status: 'valid',
          verificationToken: 'verify-token',
          pdfUrl: '/api/public/training/certificates/verify-token/pdf',
        },
      ],
      rewards: [],
      preferenceHistory: [],
    },
    isLoading: false,
    isError: false,
  })
})

function LocationProbe() {
  const location = useLocation()
  return (
    <output data-testid="location-probe">
      {location.pathname}
      {location.search}
    </output>
  )
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <PharmacistsPage />
          <LocationProbe />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PharmacistsPage — рендер', () => {
  it('H1 + 4 metrics', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Фармацевты/i })).toBeInTheDocument()
    expect(screen.getByText('Всего фармацевтов')).toBeInTheDocument()
    // Активные / Pending встречаются в Metrics + Tabs
    expect(screen.getAllByText('Активные').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Pending').length).toBeGreaterThan(0)
    expect(screen.getByText('Текущие балансы')).toBeInTheDocument()
  })

  it('Empty state на пустом списке', () => {
    renderPage()
    expect(screen.getByText(/Фармацевтов пока нет/i)).toBeInTheDocument()
  })

  it('форма создания открывается без чужих персональных данных', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Добавить фармацевта' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Телефон' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: 'ФИО' })).toHaveValue('')
    expect(screen.getByLabelText('Пароль для входа')).toBeInTheDocument()
  })
})

describe('PharmacistsPage — loading/error', () => {
  it('isLoading', () => {
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    })
    renderPage()
    expect(screen.getByText(/Загружаем фармацевтов/i)).toBeInTheDocument()
  })

  it('isError', async () => {
    const refetch = vi.fn()
    pharmacistHooks.usePharmacists.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('PharmacistsPage — список и actions', () => {
  it.each([
    'FINANCE_REVIEWER',
    'CATEGORY_LEAD',
    'BRAND_MANAGER',
    'TRAINING_MANAGER',
    'REGIONAL_MANAGER',
    'TRAINER',
    undefined,
  ] as const)('роль %s видит баланс без сброса пароля', (role) => {
    useUiStore.setState({
      authedUser: role
        ? {
            id: 'profile-test',
            email: 'profile@test.invalid',
            name: 'Profile reader',
            company: 'Test',
            role,
          }
        : null,
    })
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([
      mkPharmacist({ id: 'ordinary-read' }),
      mkPharmacist({ id: 'no-password', hasPassword: false }),
    ])
    renderPage()
    const row = screen.getByTestId('pharmacist-row-ordinary-read')
    expect(within(row).getByText(/50\s000\s₸/)).toBeInTheDocument()
    expect(within(row).queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Сменить пароль' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Задать пароль' })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it.each(['SYSTEM_ADMIN', 'HQ_HEAD'] as const)(
    'роль %s меняет пароль без balance в PATCH',
    async (role) => {
      useUiStore.setState({
        authedUser: {
          id: 'profile-test',
          email: 'profile@test.invalid',
          name: 'Profile editor',
          company: 'Test',
          role,
        },
      })
      const mutate = vi.fn()
      pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
      setPharmacists([mkPharmacist({ id: 'ordinary-edit' })])
      const user = userEvent.setup()
      renderPage()
      const row = screen.getByTestId('pharmacist-row-ordinary-edit')
      expect(within(row).getByText(/50\s000\s₸/)).toBeInTheDocument()
      expect(within(row).queryByRole('spinbutton')).not.toBeInTheDocument()
      await user.click(within(row).getByRole('button', { name: 'Сменить пароль' }))
      await user.type(screen.getByLabelText('Новый пароль'), 'TestPassword123!')
      await user.click(screen.getByRole('button', { name: 'Сохранить пароль' }))
      expect(mutate).toHaveBeenCalledWith(
        { id: 'ordinary-edit', patch: { password: 'TestPassword123!' } },
        expect.any(Object),
      )
      expect(mutate.mock.calls[0][0].patch).not.toHaveProperty('balance')
    },
  )

  it('потеря полномочий закрывает уже открытый сброс пароля', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: 'Сменить пароль' }))
    await user.type(screen.getByLabelText('Новый пароль'), 'TestPassword123!')
    act(() =>
      useUiStore.setState({
        authedUser: {
          id: 'finance-test',
          email: 'finance@test.invalid',
          name: 'Finance',
          company: 'Test',
          role: 'FINANCE_REVIEWER',
        },
      }),
    )
    expect(screen.queryByRole('button', { name: 'Сохранить пароль' })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()
  })

  it('таблица показывает строки', () => {
    setPharmacists([
      mkPharmacist({ id: 'u_a', name: 'Айгерим' }),
      mkPharmacist({ id: 'u_b', name: 'Бауыржан', status: 'blocked' }),
    ])
    renderPage()
    expect(screen.getByTestId('pharmacists-table')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_a')).toBeInTheDocument()
    expect(screen.getByTestId('pharmacist-row-u_b')).toBeInTheDocument()
  })

  it('показывает ИИН отдельной колонкой без пароля или его хеша', () => {
    const pharmacist = {
      ...mkPharmacist({ iin: '001201000001' }),
      password: 'SyntheticPasswordMustNotBeRendered!',
      passwordHash: '$2b$12$syntheticHashMustNotBeRendered',
    }
    setPharmacists([pharmacist])
    renderPage()

    const table = screen.getByTestId('pharmacists-table')
    const header = within(table).getByRole('columnheader', { name: 'ИИН' })
    expect(header).toHaveAttribute('scope', 'col')
    const row = screen.getByTestId('pharmacist-row-u_1')
    expect(within(row).getByRole('cell', { name: '001201000001' })).toBeInTheDocument()
    expect(within(row).getAllByRole('cell')).toHaveLength(
      within(table).getAllByRole('columnheader').length,
    )
    expect(table.outerHTML).not.toContain(pharmacist.password)
    expect(table.outerHTML).not.toContain(pharmacist.passwordHash)
    expect(within(table).queryByRole('columnheader', { name: /пароль/i })).not.toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Сменить пароль' })).toBeInTheDocument()
  })

  it('оставляет пустой ИИН явным и сохраняет колонки без массового выбора', () => {
    lmsHooks.useTrainingDashboard.mockReturnValue({
      data: { capabilities: { canManagePreferences: false, canManageAssignments: false } },
    })
    setPharmacists([mkPharmacist({ iin: '' })])
    renderPage()

    const table = screen.getByTestId('pharmacists-table')
    const row = screen.getByTestId('pharmacist-row-u_1')
    expect(within(table).getByRole('columnheader', { name: 'ИИН' })).toBeInTheDocument()
    expect(within(row).getByRole('cell', { name: '—' })).toBeInTheDocument()
    expect(within(row).getAllByRole('cell')).toHaveLength(9)
    expect(within(table).getAllByRole('columnheader')).toHaveLength(9)
    expect(within(table).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('позволяет выбрать сотрудников для D9 без прав на изменение обучения', async () => {
    const user = userEvent.setup()
    accessHooks.useAdministrativeCapabilities.mockReturnValue({
      canExportLearnerCredentials: true,
      canExportPharmacists: false,
      data: { version: 1 },
    })
    lmsHooks.useTrainingDashboard.mockReturnValue({
      data: { capabilities: { canManagePreferences: false, canManageAssignments: false } },
    })
    setPharmacists([mkPharmacist()])
    renderPage()
    const download = screen.getByRole('button', { name: 'Учётные данные ЛК' })
    expect(download).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Айгерим Касенова' }))
    expect(download).toBeEnabled()
    expect(
      screen.queryByRole('button', { name: /Изменить формат|Назначить обучение/ }),
    ).not.toBeInTheDocument()
    await user.click(download)
    expect(screen.getByRole('dialog')).toHaveTextContent('Айгерим Касенова')
  })

  it('показывает скрытые поля прочерком и не выдаёт частичную сумму за общий баланс', () => {
    setPharmacists([
      mkPharmacist({ id: 'visible', name: 'Доступный баланс', balance: 50_000 }),
      mkPharmacist({
        id: 'restricted',
        name: 'Скрытые поля',
        iin: null,
        phone: null,
        receipts30d: null,
        rulesAccepted30d: null,
        earned30d: null,
        balance: null,
      }),
    ])
    renderPage()
    const row = screen.getByTestId('pharmacist-row-restricted')
    expect(within(row).getAllByRole('cell', { name: '—' })).toHaveLength(3)
    expect(row).not.toHaveTextContent('null')
    expect(row).not.toHaveTextContent(/\b0\s*₸/)
    const balanceMetric = screen.getByText('Текущие балансы').closest('.card') as HTMLElement
    expect(within(balanceMetric).getByText('—')).toBeInTheDocument()
    expect(balanceMetric).not.toHaveTextContent(/50\s*000/)
  })

  it('сохраняет настоящий ноль и полную сумму для нескрытых балансов', () => {
    setPharmacists([
      mkPharmacist({ id: 'zero', name: 'Нулевой баланс', balance: 0, receipts30d: 0 }),
    ])
    renderPage()
    const row = screen.getByTestId('pharmacist-row-zero')
    expect(within(row).getByRole('cell', { name: /0\s*₸/ })).toBeInTheDocument()
    expect(within(row).getByRole('cell', { name: '0' })).toBeInTheDocument()
    const balanceMetric = screen.getByText('Текущие балансы').closest('.card') as HTMLElement
    expect(within(balanceMetric).getByText(/0\s*₸/)).toBeInTheDocument()
  })

  it('не добавляет буквальное null в поиск скрытых персональных полей', async () => {
    setPharmacists([mkPharmacist({ name: 'Скрытые данные', iin: null, phone: null })])
    renderPage()
    const user = userEvent.setup()
    await user.type(screen.getByPlaceholderText('Поиск по имени, аптеке, ИИН, телефону'), 'null')
    expect(screen.queryByTestId('pharmacist-row-u_1')).not.toBeInTheDocument()
  })

  it('окно активации сохраняет явные placeholders скрытого ИИН и телефона', async () => {
    setPharmacists([mkPharmacist({ status: 'pending', iin: null, phone: null })])
    renderPage()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Активировать' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('— · —')).toBeInTheDocument()
    expect(dialog).not.toHaveTextContent('null')
  })

  it('поиск по ИИН сохраняет выбор строк и выбирает только видимых сотрудников', async () => {
    setPharmacists([
      mkPharmacist({ id: 'u_first', name: 'Первый', iin: '001201000001' }),
      mkPharmacist({ id: 'u_second', name: 'Второй', iin: '021201000002' }),
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Первый' }))
    const search = screen.getByPlaceholderText('Поиск по имени, аптеке, ИИН, телефону')
    await user.type(search, '021201000002')
    expect(screen.queryByTestId('pharmacist-row-u_first')).not.toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '021201000002' })).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'Выбрать всех видимых фармацевтов' }))
    expect(screen.getByRole('button', { name: 'Назначить обучение (2)' })).toBeInTheDocument()

    await user.clear(search)
    expect(screen.getByRole('checkbox', { name: 'Выбрать Первый' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Выбрать Второй' })).toBeChecked()
  })

  it('фильтр active скрывает blocked', async () => {
    setPharmacists([
      mkPharmacist({ id: 'a', name: 'Active-X', status: 'active' }),
      mkPharmacist({ id: 'b', name: 'Blocked-X', status: 'blocked' }),
    ])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Активные/ }))
    expect(screen.getByText('Active-X')).toBeInTheDocument()
    expect(screen.queryByText('Blocked-X')).not.toBeInTheDocument()
  })

  it('кнопка «Разблок.» вызывает useUnblockPharmacist', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUnblockPharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_b', status: 'blocked' })])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Разблок\./ }))
    expect(mutate).toHaveBeenCalledWith('u_b', expect.any(Object))
  })

  it('администратор может задать новый пароль фармацевту', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useUpdatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([mkPharmacist({ id: 'u_password', hasPassword: false })])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Задать пароль' }))
    await user.type(screen.getByLabelText('Новый пароль'), 'Farm123!')
    await user.click(screen.getByRole('button', { name: 'Сохранить пароль' }))

    expect(mutate).toHaveBeenCalledWith(
      { id: 'u_password', patch: { password: 'Farm123!' } },
      expect.any(Object),
    )
  })

  it('pending фармацевта можно назначить аптеке и активировать', async () => {
    const mutate = vi.fn()
    pharmacistHooks.useActivatePharmacist.mockReturnValue({ mutate, isPending: false })
    setPharmacists([
      mkPharmacist({
        id: 'u_pending',
        status: 'pending',
        pharmacyId: '',
        pharmacyName: '',
        city: '',
      }),
    ])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Активировать' }))
    const dialog = screen.getByRole('dialog', { name: 'Активация фармацевта' })
    await user.selectOptions(within(dialog).getByRole('combobox'), 'ph_1')
    await user.click(within(dialog).getByRole('button', { name: 'Активировать' }))

    expect(mutate).toHaveBeenCalledWith(
      { id: 'u_pending', request: { pharmacyId: 'ph_1' } },
      expect.any(Object),
    )
  })

  it('передаёт выбранных активных фармацевтов в массовое назначение обучения', async () => {
    setPharmacists([mkPharmacist({ id: 'u_active', name: 'Айгерим' })])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('checkbox', { name: 'Выбрать Айгерим' }))
    await user.click(screen.getByRole('button', { name: 'Назначить обучение (1)' }))

    expect(screen.getByTestId('location-probe')).toHaveTextContent(
      '/lms?action=assign&pharmacists=u_active',
    )
  })

  it('открывает полную карточку обучения фармацевта', async () => {
    setPharmacists([mkPharmacist()])
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: 'Обучение' }))

    expect(lmsHooks.usePharmacistTrainingProfile).toHaveBeenCalledWith('u_1')
    const dialog = screen.getByRole('dialog', { name: 'Айгерим Касенова' })
    expect(within(dialog).getByText('Гибрид')).toBeInTheDocument()
    expect(within(dialog).getByText('Работа с продуктом')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Работа с продуктом/ })).toHaveAttribute(
      'href',
      '/api/public/training/certificates/verify-token/pdf',
    )
  })

  it('не показывает ноль вместо скрытой истории учебных начислений в профиле', async () => {
    setPharmacists([mkPharmacist()])
    const previous = lmsHooks.usePharmacistTrainingProfile.getMockImplementation()!
    lmsHooks.usePharmacistTrainingProfile.mockImplementation((...args: unknown[]) => {
      const result = previous(...args)
      return { ...result, data: { ...result.data, totalRewards: null, rewards: [] } }
    })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: 'Обучение' }))
    const dialog = screen.getByRole('dialog', { name: 'Айгерим Касенова' })
    expect(within(dialog).getByText('Нет доступа')).toBeInTheDocument()
    expect(within(dialog).getByText('Бонусы за обучение')).toBeInTheDocument()
  })
})
