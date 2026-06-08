// Тесты ScreensPage — плейлисты + слайды из real API (read-only).

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PlaylistDto, SlideDto } from '@/lib/api-types'
import ScreensPage from './ScreensPage'

const screenHooks = vi.hoisted(() => ({
  usePlaylists: vi.fn(),
  useSlides: vi.fn(),
  useCreatePlaylist: vi.fn(),
  useUpdatePlaylist: vi.fn(),
  useDeletePlaylist: vi.fn(),
  useUploadSlide: vi.fn(),
  useDeleteSlide: vi.fn(),
  useAssignSlide: vi.fn(),
}))
vi.mock('@/lib/queries/screens', () => screenHooks)

const pharmacyHooks = vi.hoisted(() => ({ usePharmacies: vi.fn() }))
vi.mock('@/lib/queries/pharmacies', () => pharmacyHooks)

// Общие мок-мутации, чтобы можно было ассертить вызовы.
const mut = {
  createPlaylist: vi.fn(),
  updatePlaylist: vi.fn(),
  deletePlaylist: vi.fn(),
  uploadSlide: vi.fn(),
  deleteSlide: vi.fn(),
  assignSlide: vi.fn(),
}

function mkPlaylist(over: Partial<PlaylistDto> = {}): PlaylistDto {
  return {
    id: 'pl_1',
    name: 'Утренняя ротация',
    status: 'active',
    slidesCount: 4,
    durationSec: 80,
    pharmacies: 24,
    pharmacyId: null,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

function mkSlide(over: Partial<SlideDto> = {}): SlideDto {
  return {
    id: 'sl_1',
    title: 'Аквалор',
    kind: 'video',
    durationSec: 20,
    mediaUrl: 's3://x/a.mp4',
    playlistId: 'pl_1',
    position: 0,
    ...over,
  }
}

function setData(
  playlists: PlaylistDto[] = [],
  slides: SlideDto[] = [],
  extra: Record<string, unknown> = {},
) {
  screenHooks.usePlaylists.mockReturnValue({
    data: playlists,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
    ...extra,
  })
  screenHooks.useSlides.mockReturnValue({
    data: slides,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setData([], [])
  screenHooks.useCreatePlaylist.mockReturnValue({ mutate: mut.createPlaylist, isPending: false })
  screenHooks.useUpdatePlaylist.mockReturnValue({ mutate: mut.updatePlaylist, isPending: false })
  screenHooks.useDeletePlaylist.mockReturnValue({ mutate: mut.deletePlaylist, isPending: false })
  screenHooks.useUploadSlide.mockReturnValue({ mutate: mut.uploadSlide, isPending: false })
  screenHooks.useDeleteSlide.mockReturnValue({ mutate: mut.deleteSlide, isPending: false })
  screenHooks.useAssignSlide.mockReturnValue({ mutate: mut.assignSlide, isPending: false })
  pharmacyHooks.usePharmacies.mockReturnValue({
    data: [{ id: 'ph_1', name: 'Аптека №1', city: 'Алматы' }],
  })
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

describe('ScreensPage — рендер', () => {
  it('H1 + Empty на пустых данных', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { level: 1, name: /Управление экранами/i }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Плейлистов пока нет/i)).toBeInTheDocument()
    expect(screen.getByText(/Библиотека пуста/i)).toBeInTheDocument()
  })

  it('показывает плейлисты из API', () => {
    setData([mkPlaylist({ id: 'pl_a' }), mkPlaylist({ id: 'pl_b', name: 'Промо' })], [])
    renderPage()
    expect(screen.getByTestId('playlists-table')).toBeInTheDocument()
    expect(screen.getByTestId('playlist-pl_a')).toBeInTheDocument()
    expect(screen.getByTestId('playlist-pl_b')).toBeInTheDocument()
  })

  it('показывает слайды из API (видео + библиотечный)', () => {
    setData(
      [],
      [
        mkSlide({ id: 'sl_v', kind: 'video' }),
        mkSlide({ id: 'sl_lib', kind: 'image', playlistId: null }),
      ],
    )
    renderPage()
    expect(screen.getByTestId('slides-list')).toBeInTheDocument()
    expect(screen.getByTestId('slide-sl_v')).toBeInTheDocument()
    expect(screen.getByTestId('slide-sl_lib')).toBeInTheDocument()
  })

  it('Расписание всегда Empty (Этап 5)', () => {
    setData([mkPlaylist()], [mkSlide()])
    renderPage()
    expect(screen.getByText(/Расписаний пока нет/i)).toBeInTheDocument()
  })
})

describe('ScreensPage — error', () => {
  it('баннер + Повторить когда плейлисты не загрузились', async () => {
    const refetch = vi.fn()
    setData([], [], { isError: true, error: new Error('x'), refetch })
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByText(/Не удалось загрузить плейлисты/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })
})

describe('ScreensPage — управление (ТЗ §3.3)', () => {
  it('кнопка «Загрузить слайд» открывает модалку с file-input', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('screens-upload-slide'))
    expect(screen.getByTestId('slide-file-input')).toBeInTheDocument()
  })

  it('кнопка «Новый плейлист» открывает модалку', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('screens-new-playlist'))
    expect(screen.getByRole('button', { name: /^Создать/ })).toBeInTheDocument()
  })

  it('активировать/в черновик зовёт useUpdatePlaylist', async () => {
    setData([mkPlaylist({ id: 'pl_x', status: 'draft' })], [])
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Активировать/ }))
    expect(mut.updatePlaylist).toHaveBeenCalled()
    expect(mut.updatePlaylist.mock.calls[0][0]).toMatchObject({
      id: 'pl_x',
      patch: { status: 'active' },
    })
  })

  it('назначение плейлиста на конкретный экран → useUpdatePlaylist с setTarget', async () => {
    setData([mkPlaylist({ id: 'pl_x' })], [])
    const user = userEvent.setup()
    renderPage()
    const select = screen
      .getByTestId('playlist-target-pl_x')
      .querySelector('select') as HTMLSelectElement
    await user.selectOptions(select, 'ph_1')
    expect(mut.updatePlaylist).toHaveBeenCalled()
    expect(mut.updatePlaylist.mock.calls.at(-1)?.[0]).toMatchObject({
      id: 'pl_x',
      patch: { setTarget: true, targetPharmacyId: 'ph_1' },
    })
  })

  it('возврат плейлиста на все экраны → targetPharmacyId=null', async () => {
    setData([mkPlaylist({ id: 'pl_y', pharmacyId: 'ph_1' })], [])
    const user = userEvent.setup()
    renderPage()
    const select = screen
      .getByTestId('playlist-target-pl_y')
      .querySelector('select') as HTMLSelectElement
    await user.selectOptions(select, '__all__')
    expect(mut.updatePlaylist.mock.calls.at(-1)?.[0]).toMatchObject({
      id: 'pl_y',
      patch: { setTarget: true, targetPharmacyId: null },
    })
  })

  it('удаление плейлиста с подтверждением → useDeletePlaylist', async () => {
    setData([mkPlaylist({ id: 'pl_x' })], [])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('playlist-delete-pl_x'))
    expect(mut.deletePlaylist).toHaveBeenCalledWith('pl_x', expect.anything())
  })

  it('назначение слайда в плейлист → useAssignSlide', async () => {
    setData([mkPlaylist({ id: 'pl_x', name: 'Утро' })], [mkSlide({ id: 'sl_x', playlistId: null })])
    const user = userEvent.setup()
    renderPage()
    // в строке слайда есть select с опцией плейлиста
    const select = screen.getByTestId('slide-sl_x').querySelector('select') as HTMLSelectElement
    await user.selectOptions(select, 'pl_x')
    expect(mut.assignSlide).toHaveBeenCalled()
    expect(mut.assignSlide.mock.calls[0][0]).toMatchObject({
      id: 'sl_x',
      req: { playlistId: 'pl_x' },
    })
  })

  it('удаление слайда → useDeleteSlide', async () => {
    setData([], [mkSlide({ id: 'sl_x' })])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByTestId('slide-delete-sl_x'))
    expect(mut.deleteSlide).toHaveBeenCalledWith('sl_x', expect.anything())
  })
})
