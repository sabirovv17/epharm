// Тесты ScreensPage — упрощённая вкладка «Экраны»: онлайн-кассы + один ролик (эфир)
// + загрузка/замена. Плюс переключение на вкладку «Баннеры».

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { ActivePlaylistDto } from '@/lib/api-types'
import ScreensPage from './ScreensPage'

const screenHooks = vi.hoisted(() => ({
  useConnectedScreens: vi.fn(),
  useBroadcast: vi.fn(),
  useUploadBroadcast: vi.fn(),
}))
vi.mock('@/lib/queries/screens', () => screenHooks)

// Вкладка «Баннеры» рендерит BannersPanel — мокаем его queries-модуль.
const bannerHooks = vi.hoisted(() => ({
  useBanners: vi.fn(),
  useCreateBanner: vi.fn(),
  useUpdateBanner: vi.fn(),
  useDeleteBanner: vi.fn(),
  useReorderBanner: vi.fn(),
  useUploadBannerImage: vi.fn(),
}))
vi.mock('@/lib/queries/banners', () => bannerHooks)

const uploadMutate = vi.fn()

function setBroadcast(data: ActivePlaylistDto | undefined) {
  screenHooks.useBroadcast.mockReturnValue({ data, isLoading: false, isError: false })
}

beforeEach(() => {
  vi.clearAllMocks()
  screenHooks.useConnectedScreens.mockReturnValue({
    data: { total: 0, devices: [] },
    isLoading: false,
  })
  setBroadcast({ playlistId: null, name: '', slides: [] })
  screenHooks.useUploadBroadcast.mockReturnValue({ mutate: uploadMutate, isPending: false })

  bannerHooks.useBanners.mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  })
  bannerHooks.useCreateBanner.mockReturnValue({ mutate: vi.fn(), isPending: false })
  bannerHooks.useUpdateBanner.mockReturnValue({ mutate: vi.fn(), isPending: false })
  bannerHooks.useDeleteBanner.mockReturnValue({ mutate: vi.fn(), isPending: false })
  bannerHooks.useReorderBanner.mockReturnValue({ mutate: vi.fn(), isPending: false })
  bannerHooks.useUploadBannerImage.mockReturnValue({ mutate: vi.fn(), isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ToastHost>
          <ScreensPage />
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ScreensPage — онлайн-кассы', () => {
  it('H1 + счётчик подключённых касс', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { level: 1, name: /Управление экранами/i }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('connected-registers')).toHaveTextContent('Подключено касс')
  })

  it('показывает онлайн-кассы (total + устройства)', () => {
    screenHooks.useConnectedScreens.mockReturnValue({
      data: {
        total: 2,
        devices: [
          { deviceId: 'kassa-1', pharmacyId: 'ph_1', lastSeen: '2026-06-17T10:00:00Z' },
          { deviceId: 'kassa-2', pharmacyId: null, lastSeen: '2026-06-17T10:01:00Z' },
        ],
      },
      isLoading: false,
    })
    renderPage()
    const w = screen.getByTestId('connected-registers')
    expect(w).toHaveTextContent('2')
    expect(screen.getByTestId('connected-kassa-1')).toBeInTheDocument()
    expect(screen.getByTestId('connected-kassa-2')).toBeInTheDocument()
  })
})

describe('ScreensPage — эфир (один ролик)', () => {
  it('нет ролика → пустое состояние + кнопка «Загрузить ролик»', () => {
    renderPage()
    expect(screen.getByText(/Ролик ещё не загружен/i)).toBeInTheDocument()
    expect(screen.getByTestId('broadcast-upload')).toHaveTextContent(/Загрузить ролик/i)
    expect(screen.queryByTestId('broadcast-video')).not.toBeInTheDocument()
  })

  it('есть ролик → видео + кнопка «Заменить ролик»', () => {
    setBroadcast({
      playlistId: 'pl_broadcast',
      name: 'Эфир касс',
      slides: [{ url: 'https://m/promo.mp4', kind: 'video', durationSec: 8, title: 'Промо' }],
    })
    renderPage()
    const video = screen.getByTestId('broadcast-video') as HTMLVideoElement
    expect(video.src).toContain('promo.mp4')
    expect(screen.getByTestId('broadcast-upload')).toHaveTextContent(/Заменить ролик/i)
  })

  it('выбор файла → useUploadBroadcast.mutate с файлом', async () => {
    const user = userEvent.setup()
    renderPage()
    const file = new File(['x'], 'promo.mp4', { type: 'video/mp4' })
    await user.upload(screen.getByTestId('broadcast-file-input') as HTMLInputElement, file)
    expect(uploadMutate).toHaveBeenCalled()
    expect(uploadMutate.mock.calls[0][0]).toMatchObject({ file })
  })

  it('загрузка идёт → кнопка «Загружаем…» и disabled', () => {
    screenHooks.useUploadBroadcast.mockReturnValue({ mutate: uploadMutate, isPending: true })
    renderPage()
    const btn = screen.getByTestId('broadcast-upload')
    expect(btn).toHaveTextContent(/Загружаем/i)
    expect(btn).toBeDisabled()
  })
})

describe('ScreensPage — вкладки', () => {
  it('переключение на «Баннеры приложения» показывает панель + кнопку «Добавить баннер»', async () => {
    const user = userEvent.setup()
    renderPage()
    // по умолчанию — эфир
    expect(screen.getByTestId('broadcast-card')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Баннеры приложения/i }))
    expect(screen.getByTestId('banners-create')).toBeInTheDocument()
    expect(screen.queryByTestId('broadcast-card')).not.toBeInTheDocument()
  })
})
