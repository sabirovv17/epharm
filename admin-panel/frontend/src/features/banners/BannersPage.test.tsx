// Тесты BannersPage — список баннеров из API + форма создания/редактирования.
// Подход 1:1 со ScreensPage.test.tsx: мокаем модуль queries/banners, проверяем
// рендер списка, открытие формы и вызов мутации создания.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { BannerDto } from '@/lib/api-types'
import BannersPage from './BannersPage'

const bannerHooks = vi.hoisted(() => ({
  useBanners: vi.fn(),
  useCreateBanner: vi.fn(),
  useUpdateBanner: vi.fn(),
  useDeleteBanner: vi.fn(),
  useUploadBannerImage: vi.fn(),
}))
vi.mock('@/lib/queries/banners', () => bannerHooks)

// Общие мок-мутации, чтобы ассертить вызовы.
const mut = {
  create: vi.fn(),
  update: vi.fn(),
  del: vi.fn(),
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
  bannerHooks.useUploadBannerImage.mockReturnValue({ mutate: mut.upload, isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <BannersPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BannersPage — рендер', () => {
  it('H1 + Empty на пустых данных', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: /Баннеры/i })).toBeInTheDocument()
    expect(screen.getByText(/Баннеров пока нет/i)).toBeInTheDocument()
  })

  it('показывает баннеры из API', () => {
    setData([mkBanner({ id: 'bn_a' }), mkBanner({ id: 'bn_b', title: 'Промо' })])
    renderPage()
    expect(screen.getByTestId('banners-list')).toBeInTheDocument()
    expect(screen.getByTestId('banner-bn_a')).toBeInTheDocument()
    expect(screen.getByTestId('banner-bn_b')).toBeInTheDocument()
  })

  it('баннер показывает заголовок, статус и позицию', () => {
    setData([mkBanner({ id: 'bn_x', title: 'Витамины', status: 'active', position: 3 })])
    renderPage()
    const row = screen.getByTestId('banner-bn_x')
    expect(row).toHaveTextContent('Витамины')
    expect(row).toHaveTextContent(/Активен/i)
    expect(row).toHaveTextContent('3')
  })
})

describe('BannersPage — error', () => {
  it('баннер ошибки + Повторить когда список не загрузился', async () => {
    const refetch = vi.fn()
    setData([], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить баннеры/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('BannersPage — форма', () => {
  it('кнопка «Создать» открывает форму с file-input', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('banners-create'))
    expect(screen.getByTestId('banner-file-input')).toBeInTheDocument()
    // Заголовок модалки Modal рендерится как <div> (не heading-роль) — ищем по тексту.
    expect(screen.getByText(/Новый баннер/i)).toBeInTheDocument()
  })

  it('сабмит создания вызывает useCreateBanner с заголовком и картинкой', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('banners-create'))

    // Заголовок вводим вручную.
    const titleInput = screen.getByPlaceholderText(/Скидки на витамины/i)
    await user.type(titleInput, 'Новая акция')

    // Загрузка картинки: имитируем выбор файла → upload.mutate должен сработать.
    const file = new File(['x'], 'banner.png', { type: 'image/png' })
    await user.upload(screen.getByTestId('banner-file-input') as HTMLInputElement, file)
    expect(mut.upload).toHaveBeenCalled()
    // Прокручиваем onSuccess загрузки картинки, чтобы в форме появился imageUrl.
    const uploadOpts = mut.upload.mock.calls[0][1] as {
      onSuccess: (r: { imageUrl: string }) => void
    }
    uploadOpts.onSuccess({ imageUrl: 'https://minio/x/new.png' })

    // Теперь кнопка создания активна → сабмит.
    await user.click(screen.getByRole('button', { name: /^Создать$/ }))
    expect(mut.create).toHaveBeenCalled()
    expect(mut.create.mock.calls[0][0]).toMatchObject({
      title: 'Новая акция',
      imageUrl: 'https://minio/x/new.png',
      status: 'draft',
    })
  })

  it('редактирование открывает форму с данными баннера и зовёт useUpdateBanner', async () => {
    setData([mkBanner({ id: 'bn_e', title: 'Старый заголовок' })])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /^Редактировать$/ }))
    // Заголовок модалки Modal рендерится как <div> (не heading-роль) — ищем по тексту.
    expect(screen.getByText(/Редактирование баннера/i)).toBeInTheDocument()
    expect(screen.getByDisplayValue('Старый заголовок')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Сохранить$/ }))
    expect(mut.update).toHaveBeenCalled()
    expect(mut.update.mock.calls[0][0]).toMatchObject({ id: 'bn_e' })
  })
})

describe('BannersPage — управление статусом и удалением', () => {
  it('кнопка «В архив» зовёт useUpdateBanner со статусом archived', async () => {
    setData([mkBanner({ id: 'bn_x', status: 'active' })])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /^В архив$/ }))
    expect(mut.update).toHaveBeenCalled()
    expect(mut.update.mock.calls[0][0]).toMatchObject({
      id: 'bn_x',
      patch: { status: 'archived' },
    })
  })

  it('удаление с подтверждением → useDeleteBanner', async () => {
    setData([mkBanner({ id: 'bn_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('banner-delete-bn_x'))
    expect(mut.del).toHaveBeenCalledWith('bn_x', expect.anything())
  })
})
