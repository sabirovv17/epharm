import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import InventoryEditor, { NetworkStructureButton } from './InventoryEditor'
import { inventoryEditorApi, type InventoryEditorState } from './api'

vi.mock('./api', () => ({
  inventoryEditorApi: {
    get: vi.fn(),
    organizations: vi.fn(),
    branch: vi.fn(),
    container: vi.fn(),
    shelf: vi.fn(),
    configure: vi.fn(),
    prices: vi.fn(),
    floor: vi.fn(),
    publishFloor: vi.fn(),
    upload: vi.fn(),
    archivePhoto: vi.fn(),
    photo: vi.fn(),
  },
}))
const state: InventoryEditorState = {
  branch: {
    id: 'branch-1',
    organizationId: 'org-1',
    organizationName: 'Synthetic',
    code: '01',
    name: 'Демо-аптека',
    address: 'Демо-адрес',
    city: 'Демо',
    division: null,
    active: true,
    epharmPharmacyId: null,
    source: { system: 'MERCH_CRM', origin: 'https://crm.example.test', id: 'branch-1' },
  },
  revision: 4,
  latitude: null,
  longitude: null,
  containers: [
    {
      id: 'cabinet-1',
      code: 'A-01',
      name: 'Шкаф',
      type: 'cabinet',
      category: 'A',
      position: 0,
      active: true,
    },
  ],
  shelves: [
    {
      shelf: {
        id: 'shelf-1',
        containerId: 'cabinet-1',
        code: 'AA-01',
        category: 'AA',
        title: null,
        type: 'shelf',
        width: 0.8,
        position: 0,
        active: true,
        saleMode: 'whole_only',
        baseMonthlyPrice: null,
        halfMonthlyPrice: null,
      },
      maxProducts: 12,
    },
  ],
  monthlyPrices: [],
  photos: [],
  floorPlan: {
    version: 3,
    room: { width: 18, depth: 11 },
    fixtures: [
      {
        id: 'cashier',
        type: 'cashier',
        label: 'Касса',
        x: 9,
        y: 1,
        width: 4,
        depth: 1,
        orientation: 'horizontal',
      },
      {
        id: 'entrance',
        type: 'entrance',
        label: 'Вход',
        x: 9,
        y: 10,
        width: 2.6,
        depth: 0.5,
        orientation: 'horizontal',
      },
    ],
    cabinetPositions: [
      {
        cabinetCode: 'A-01',
        x: 2,
        y: 4,
        width: 1.4,
        depth: 0.55,
        orientation: 'horizontal',
        zone: 'custom',
      },
    ],
  },
  floorPlanDraft: null,
  floorPlanWarning: null,
  publishedAt: '2026-10-29T00:00:00Z',
  canEditStructure: true,
  canEditPassport: true,
  canConfigurePrices: false,
  canManageOrganizations: false,
}
function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <InventoryEditor branchId="branch-1" month="2027-01" onClose={vi.fn()} onChanged={vi.fn()} />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(inventoryEditorApi.get).mockResolvedValue(structuredClone(state))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})
describe('native inventory editor', () => {
  it('accepts a WebP file unchanged for an explicitly permitted private passport upload', async () => {
    vi.mocked(inventoryEditorApi.upload).mockResolvedValue({ revision: state.revision + 1 })
    const user = setup()
    const input = await screen.findByLabelText('Файл JPG/PNG/WebP')
    expect(input).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp')
    const file = new File(['synthetic-webp'], 'synthetic.webp', { type: 'image/webp' })
    await user.upload(input, file)
    expect((input as HTMLInputElement).files?.[0]).toBe(file)
    // jsdom's native required-file validation does not observe user-event's mocked FileList.
    fireEvent.submit(input.closest('form')!)
    await waitFor(() =>
      expect(inventoryEditorApi.upload).toHaveBeenCalledWith(
        'branch-1',
        state.revision,
        file,
        'facade',
        '',
        null,
      ),
    )
  })
  it('uses fresh server controls and never presents withheld prices as zero', async () => {
    vi.mocked(inventoryEditorApi.get).mockResolvedValue({
      ...state,
      canEditStructure: false,
      canEditPassport: false,
    })
    const user = setup()
    await screen.findByRole('heading', { name: 'Демо-аптека · Демо' })
    expect(screen.getByRole('textbox', { name: 'Название' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Сохранить паспорт' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Цены' }))
    expect(screen.getByText('Нет доступа')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Применить цены' })).not.toBeInTheDocument()
    expect(screen.queryByText('0 ₸')).not.toBeInTheDocument()
  })
  it('sends the displayed revision with branch edits without inventing a pharmacy binding', async () => {
    vi.mocked(inventoryEditorApi.branch).mockResolvedValue({ revision: 5 })
    const user = setup()
    const input = await screen.findByRole('textbox', { name: 'Название' })
    await user.clear(input)
    await user.type(input, 'Новое название')
    await user.click(screen.getByRole('button', { name: 'Сохранить паспорт' }))
    await waitFor(() =>
      expect(inventoryEditorApi.branch).toHaveBeenCalledWith(
        'branch-1',
        4,
        expect.objectContaining({ name: 'Новое название', organizationId: 'org-1' }),
      ),
    )
    const sent = vi.mocked(inventoryEditorApi.branch).mock.calls[0][2]
    expect(sent).not.toHaveProperty('epharmPharmacyId')
    expect(sent).not.toHaveProperty('priceAdjustmentPercent')
  })
  it('409 removes editable stale controls until the user explicitly reloads', async () => {
    vi.mocked(inventoryEditorApi.branch).mockRejectedValue(
      new Error('Данные изменились. Обновите страницу'),
    )
    const user = setup()
    await screen.findByRole('textbox', { name: 'Название' })
    await user.click(screen.getByRole('button', { name: 'Сохранить паспорт' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Данные изменились')
    expect(screen.queryByRole('button', { name: 'Сохранить паспорт' })).not.toBeInTheDocument()
    expect(inventoryEditorApi.branch).toHaveBeenCalledTimes(1)
    vi.mocked(inventoryEditorApi.get).mockResolvedValue({ ...state, revision: 5 })
    await user.click(screen.getByRole('button', { name: 'Обновить данные' }))
    await screen.findByRole('button', { name: 'Сохранить паспорт' })
    expect(inventoryEditorApi.branch).toHaveBeenCalledTimes(1)
  })
  it('does not publish dirty geometry and publishes only the saved CAS draft', async () => {
    let current = structuredClone(state)
    vi.mocked(inventoryEditorApi.get).mockImplementation(async () => current)
    vi.mocked(inventoryEditorApi.floor).mockImplementation(async (_id, _revision, plan) => {
      current = { ...current, revision: 5, floorPlanDraft: plan }
      return { revision: 5 }
    })
    vi.mocked(inventoryEditorApi.publishFloor).mockResolvedValue({ revision: 6 })
    const user = setup()
    await screen.findByRole('textbox', { name: 'Название' })
    await user.click(screen.getByRole('tab', { name: 'Схема помещения' }))
    expect(screen.getByRole('button', { name: 'Опубликовать схему' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Сохранить черновик схемы' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Опубликовать схему' })).toBeEnabled(),
    )
    expect(inventoryEditorApi.publishFloor).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Опубликовать схему' }))
    await waitFor(() => expect(inventoryEditorApi.publishFloor).toHaveBeenCalledWith('branch-1', 5))
  })
  it('requires separate financial eligibility before creating a newly priced place', async () => {
    const user = setup()
    await screen.findByRole('textbox', { name: 'Название' })
    await user.click(screen.getByRole('tab', { name: 'Шкафы и полки' }))
    expect(screen.getByRole('button', { name: 'Добавить рекламное место' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Изменить место' }))
    expect(screen.queryByRole('spinbutton', { name: 'Начальная цена, ₸' })).not.toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: 'Товаров на полке' })).toHaveValue(12)
  })
  it('does not expose global structure forms after permission denial', async () => {
    vi.mocked(inventoryEditorApi.organizations).mockRejectedValue(new Error('Нет доступа'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <NetworkStructureButton onChanged={vi.fn()} />
      </QueryClientProvider>,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Структура сети' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Нет доступа')
    expect(screen.queryByRole('button', { name: 'Добавить организацию' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добавить аптеку мерча' })).not.toBeInTheDocument()
  })
})
