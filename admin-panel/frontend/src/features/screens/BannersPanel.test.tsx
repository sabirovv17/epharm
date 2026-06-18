// Тесты BannersPanel — список баннеров + переключатель «Показывать» + стрелки ↑↓
// + удаление + форма создания/редактирования (4 поля).
// Мокаем модуль queries/banners; панель управляется извне (editing/setEditing),
// поэтому оборачиваем её в Harness со своим состоянием и кнопкой «добавить».

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { BannerDto } from '@/lib/api-types'
import { BannersPanel, type BannerEditing } from './BannersPanel'

const bannerHooks = vi.hoisted(() => ({
  useBanners: vi.fn(),
  useCreateBanner: vi.fn(),
  useUpdateBanner: vi.fn(),
  useDeleteBanner: vi.fn(),
  useReorderBanner: vi.fn(),
  useUploadBannerImage: vi.fn(),
}))
vi.mock('@/lib/queries/banners', () => bannerHooks)

const mut = {
  create: vi.fn(),
  update: vi.fn(),
  del: vi.fn(),
  reorder: vi.fn(),
  upload: vi.fn(),
}

function mkBanner(over: Partial<BannerDto> = {}): BannerDto {
  return {
    id: 'bn_1',
    title: 'Скидки на витамины',
    subtitle: 'До конца месяца',
    imageUrl: 'https://minio/x/banner.jpg',
    detailMd: '# Подробности',
    status: 'active',
    position: 0,
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
    ...over,
  }
}

function setData(banners: BannerDto[] = [], extra: Record<string, unknown> = {}) {
  bannerHooks.useBanners.mockReturnValue({
    data: banners,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setData([])
  bannerHooks.useCreateBanner.mockReturnValue({ mutate: mut.create, isPending: false })
  bannerHooks.useUpdateBanner.mockReturnValue({ mutate: mut.update, isPending: false })
  bannerHooks.useDeleteBanner.mockReturnValue({ mutate: mut.del, isPending: false })
  bannerHooks.useReorderBanner.mockReturnValue({ mutate: mut.reorder, isPending: false })
  bannerHooks.useUploadBannerImage.mockReturnValue({ mutate: mut.upload, isPending: false })
})

// Оборачиваем управляемую панель: своё состояние + внешняя кнопка «Добавить»
// имитирует кнопку из шапки вкладок ScreensPage.
function Harness({ initial = null }: { initial?: BannerEditing }) {
  const [editing, setEditing] = useState<BannerEditing>(initial)
  return (
    <>
      <button data-testid="ext-add" onClick={() => setEditing('new')}>
        add
      </button>
      <BannersPanel editing={editing} setEditing={setEditing} />
    </>
  )
}

function renderPanel(initial: BannerEditing = null) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <Harness initial={initial} />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BannersPanel — список', () => {
  it('пустое состояние + кнопка «Добавить баннер»', () => {
    renderPanel()
    expect(screen.getByText(/Баннеров пока нет/i)).toBeInTheDocument()
    expect(screen.getByTestId('banners-create-empty')).toBeInTheDocument()
  })

  it('показывает баннеры из API', () => {
    setData([mkBanner({ id: 'bn_a' }), mkBanner({ id: 'bn_b', title: 'Промо' })])
    renderPanel()
    expect(screen.getByTestId('banners-list')).toBeInTheDocument()
    expect(screen.getByTestId('banner-bn_a')).toBeInTheDocument()
    expect(screen.getByTestId('banner-bn_b')).toBeInTheDocument()
  })

  it('активный баннер показывает заголовок и статус «Показывается»', () => {
    setData([mkBanner({ id: 'bn_x', title: 'Витамины', status: 'active' })])
    renderPanel()
    const row = screen.getByTestId('banner-bn_x')
    expect(row).toHaveTextContent('Витамины')
    expect(row).toHaveTextContent(/Показывается/i)
  })

  it('скрытый баннер (draft) показывает «Скрыт» и выключенный переключатель', () => {
    setData([mkBanner({ id: 'bn_d', status: 'draft' })])
    renderPanel()
    const row = screen.getByTestId('banner-bn_d')
    expect(row).toHaveTextContent(/Скрыт/i)
    expect(within(row).getByRole('switch')).toHaveAttribute('aria-checked', 'false')
  })
})

describe('BannersPanel — error', () => {
  it('ошибка списка + «Повторить»', async () => {
    const refetch = vi.fn()
    setData([], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPanel()
    expect(screen.getByText(/Не удалось загрузить баннеры/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('BannersPanel — статус и порядок (управление в списке)', () => {
  it('переключатель «Показывать» у активного → useUpdateBanner status draft', async () => {
    setData([mkBanner({ id: 'bn_x', status: 'active' })])
    const user = userEvent.setup()
    renderPanel()
    const row = screen.getByTestId('banner-bn_x')
    await user.click(within(row).getByRole('switch'))
    expect(mut.update).toHaveBeenCalled()
    expect(mut.update.mock.calls[0][0]).toMatchObject({ id: 'bn_x', patch: { status: 'draft' } })
  })

  it('стрелка «вниз» у первого баннера зовёт useReorderBanner', async () => {
    setData([mkBanner({ id: 'bn_a', position: 0 }), mkBanner({ id: 'bn_b', position: 1 })])
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('banner-down-bn_a'))
    expect(mut.reorder).toHaveBeenCalled()
    const updates = mut.reorder.mock.calls[0][0] as { id: string; position: number }[]
    // оба баннера меняют позицию местами
    expect(updates).toEqual(
      expect.arrayContaining([
        { id: 'bn_b', position: 0 },
        { id: 'bn_a', position: 1 },
      ]),
    )
  })

  it('стрелка «вверх» у первого баннера заблокирована', () => {
    setData([mkBanner({ id: 'bn_a' }), mkBanner({ id: 'bn_b' })])
    renderPanel()
    expect(screen.getByTestId('banner-up-bn_a')).toBeDisabled()
    expect(screen.getByTestId('banner-down-bn_a')).not.toBeDisabled()
  })

  it('удаление с подтверждением → useDeleteBanner', async () => {
    setData([mkBanner({ id: 'bn_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('banner-delete-bn_x'))
    expect(mut.del).toHaveBeenCalledWith('bn_x', expect.anything())
  })
})

describe('BannersPanel — форма', () => {
  it('создание: 4 поля, сабмит зовёт useCreateBanner (active + позиция в конец)', async () => {
    setData([mkBanner({ id: 'bn_a' })]) // уже есть 1 баннер → новый position = 1
    const user = userEvent.setup()
    renderPanel()

    await user.click(screen.getByTestId('ext-add'))
    expect(screen.getByText(/Новый баннер/i)).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText(/Скидки на витамины/i), 'Новая акция')

    // Загрузка картинки → upload.mutate; прокручиваем onSuccess вручную.
    const file = new File(['x'], 'banner.png', { type: 'image/png' })
    await user.upload(screen.getByTestId('banner-file-input') as HTMLInputElement, file)
    expect(mut.upload).toHaveBeenCalled()
    const uploadOpts = mut.upload.mock.calls[0][1] as {
      onSuccess: (r: { imageUrl: string }) => void
    }
    uploadOpts.onSuccess({ imageUrl: 'https://minio/x/new.png' })

    await user.click(screen.getByRole('button', { name: /^Создать$/ }))
    expect(mut.create).toHaveBeenCalled()
    expect(mut.create.mock.calls[0][0]).toMatchObject({
      title: 'Новая акция',
      imageUrl: 'https://minio/x/new.png',
      status: 'active',
      position: 1,
    })
  })

  it('форма НЕ содержит полей статуса и позиции (упрощённый UX)', async () => {
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('ext-add'))
    // в форме нет селекта статуса и числового поля позиции
    expect(screen.queryByText(/^Статус$/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Позиция$/)).not.toBeInTheDocument()
  })

  it('редактирование: форма с данными баннера → useUpdateBanner', async () => {
    setData([mkBanner({ id: 'bn_e', title: 'Старый заголовок' })])
    const user = userEvent.setup()
    renderPanel()
    await user.click(screen.getByTestId('banner-edit-bn_e'))
    expect(screen.getByText(/Редактирование баннера/i)).toBeInTheDocument()
    expect(screen.getByDisplayValue('Старый заголовок')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Сохранить$/ }))
    expect(mut.update).toHaveBeenCalled()
    expect(mut.update.mock.calls[0][0]).toMatchObject({ id: 'bn_e' })
  })
})
