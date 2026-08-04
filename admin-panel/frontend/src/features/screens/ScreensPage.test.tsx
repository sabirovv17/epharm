// Тесты ScreensPage — онлайн-кассы + 12 независимых рекламных слотов,
// образующих один эфирный плейлист. Плюс переключение на вкладку «Баннеры».

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
  useUploadBroadcastSlot: vi.fn(),
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
  screenHooks.useUploadBroadcastSlot.mockReturnValue({
    mutate: uploadMutate,
    isPending: false,
    variables: undefined,
  })

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
          {
            deviceId: 'kassa-1',
            pharmacyId: 'ph_1',
            pharmacyName: 'Аспект-траст',
            pharmacyAddress: 'Алматы, Достык 248а',
            lastSeen: '2026-06-17T10:00:00Z',
          },
          {
            deviceId: 'kassa-2',
            pharmacyId: null,
            pharmacyName: null,
            pharmacyAddress: null,
            lastSeen: '2026-06-17T10:01:00Z',
          },
        ],
      },
      isLoading: false,
    })
    renderPage()
    const w = screen.getByTestId('connected-registers')
    expect(w).toHaveTextContent('2')
    expect(screen.getByTestId('connected-kassa-1')).toBeInTheDocument()
    expect(screen.getByTestId('connected-kassa-2')).toBeInTheDocument()
    // касса с известной аптекой → название + адрес (а не сырой id)
    expect(screen.getByTestId('connected-kassa-1')).toHaveTextContent('Аспект-траст')
    expect(screen.getByTestId('connected-kassa-1')).toHaveTextContent('Достык 248а')
    // касса без аптеки → «без аптеки»
    expect(screen.getByTestId('connected-kassa-2')).toHaveTextContent('без аптеки')
  })
})

describe('ScreensPage — эфир (12 слотов)', () => {
  it('нет роликов → показывает 12 независимых пустых слотов', () => {
    renderPage()
    expect(screen.getAllByTestId(/^broadcast-slot-\d+$/)).toHaveLength(12)
    expect(screen.getByTestId('broadcast-slot-1-upload')).toHaveTextContent(/Загрузить видео/i)
    expect(screen.getByTestId('broadcast-slot-12')).toBeInTheDocument()
    expect(screen.queryByTestId(/^broadcast-video-\d+$/)).not.toBeInTheDocument()
  })

  it('раскладывает ролики по position, оставляя промежуточные слоты пустыми', () => {
    setBroadcast({
      playlistId: 'pl_broadcast',
      name: 'Эфир касс',
      slides: [
        {
          id: 'sl_1',
          url: 'https://m/promo-1.mp4',
          kind: 'video',
          durationSec: 8,
          title: 'Промо 1',
          position: 0,
        },
        {
          id: 'sl_5',
          url: 'https://m/promo-5.mp4',
          kind: 'video',
          durationSec: 12,
          title: 'Промо 5',
          position: 4,
        },
      ],
    })
    renderPage()
    expect((screen.getByTestId('broadcast-video-1') as HTMLVideoElement).src).toContain(
      'promo-1.mp4',
    )
    expect((screen.getByTestId('broadcast-video-5') as HTMLVideoElement).src).toContain(
      'promo-5.mp4',
    )
    expect(screen.queryByTestId('broadcast-video-2')).not.toBeInTheDocument()
    expect(screen.getByTestId('broadcast-slot-1-upload')).toHaveTextContent(/Заменить видео/i)
    expect(screen.getByTestId('broadcast-slot-2-upload')).toHaveTextContent(/Загрузить видео/i)
    expect(screen.getByTestId('broadcast-card')).toHaveTextContent('Заполнено 2 из 12')
  })

  it('выбор файла в слоте 3 → адресная мутация только этого слота', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('broadcast-slot-3-upload'))
    const file = new File(['x'], 'promo.mp4', { type: 'video/mp4' })
    await user.upload(screen.getByTestId('broadcast-file-input') as HTMLInputElement, file)
    expect(uploadMutate).toHaveBeenCalled()
    expect(uploadMutate.mock.calls[0][0]).toMatchObject({ slot: 3, file })
  })

  it('загрузка слота 4 → только он подписан «Загружаем», повторные загрузки заблокированы', () => {
    screenHooks.useUploadBroadcastSlot.mockReturnValue({
      mutate: uploadMutate,
      isPending: true,
      variables: { slot: 4 },
    })
    renderPage()
    expect(screen.getByTestId('broadcast-slot-4-upload')).toHaveTextContent(/Загружаем/i)
    expect(screen.getByTestId('broadcast-slot-1-upload')).toBeDisabled()
    expect(screen.getByTestId('broadcast-slot-12-upload')).toBeDisabled()
  })

  it('переключает отображение между сеткой и списком без изменения данных', async () => {
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByTestId('broadcast-view-grid')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('broadcast-grid')).toBeInTheDocument()

    await user.click(screen.getByTestId('broadcast-view-list'))
    expect(screen.getByTestId('broadcast-view-list')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('broadcast-list')).toBeInTheDocument()
    expect(screen.getAllByTestId(/^broadcast-slot-\d+$/)).toHaveLength(12)
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
