// Тесты PharmacyDetailPage — рендер формы, save, delete (confirm), loading/error/not-found.

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ToastHost } from '@/ui'
import type { PharmacyDto } from '@/lib/api-types'
import PharmacyDetailPage from './PharmacyDetailPage'

const hooks = vi.hoisted(() => ({
  usePharmacy: vi.fn(),
  useUpdatePharmacy: vi.fn(),
  useDeletePharmacy: vi.fn(),
}))

vi.mock('@/lib/queries/pharmacies', () => hooks)

function mkPharmacy(over: Partial<PharmacyDto> = {}): PharmacyDto {
  return {
    id: 'ph_1',
    name: 'Аптека №1',
    chainId: 'europharma',
    chainName: 'Europharma',
    city: 'Алматы',
    district: 'Бостандыкский',
    addr: 'ул. Абая, 10',
    group: 'pilot',
    pharmacists: 3,
    receipts30d: 500,
    gmv30d: 1_500_000,
    liftPct: 15.5,
    rulesAccepted: 42,
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  }
}

const updateMutate = vi.fn()
const deleteMutate = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  hooks.useUpdatePharmacy.mockReturnValue({ mutate: updateMutate, isPending: false })
  hooks.useDeletePharmacy.mockReturnValue({ mutate: deleteMutate, isPending: false })
})

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/pharmacies/ph_1']}>
        <ToastHost>
          <Routes>
            <Route path="/pharmacies/:id" element={<PharmacyDetailPage />} />
            <Route path="/pharmacies" element={<div>СПИСОК</div>} />
          </Routes>
        </ToastHost>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PharmacyDetailPage', () => {
  it('рендерит форму с данными аптеки', () => {
    hooks.usePharmacy.mockReturnValue({ data: mkPharmacy(), isLoading: false, isError: false })
    renderPage()
    expect(screen.getByTestId('pharmacy-detail')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Аптека №1')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Алматы')).toBeInTheDocument()
    // мета read-only
    expect(screen.getByText('Europharma')).toBeInTheDocument()
  })

  it('показывает POSM pharmacyId в отдельном read-only блоке', () => {
    hooks.usePharmacy.mockReturnValue({
      data: mkPharmacy({ id: 'sloc_01KSAHYDSPA5SA3MRBHRTY6HRW' }),
      isLoading: false,
      isError: false,
    })
    renderPage()
    expect(screen.getByTestId('pharmacy-detail-posm-id')).toHaveTextContent(
      'sloc_01KSAHYDSPA5SA3MRBHRTY6HRW',
    )
  })

  it('правка названия → активируется Сохранить → mutate', async () => {
    hooks.usePharmacy.mockReturnValue({ data: mkPharmacy(), isLoading: false, isError: false })
    const user = userEvent.setup()
    renderPage()
    const nameInput = screen.getByDisplayValue('Аптека №1')
    await user.clear(nameInput)
    await user.type(nameInput, 'Аптека №2')
    await user.click(screen.getByRole('button', { name: /Сохранить/ }))
    expect(updateMutate).toHaveBeenCalled()
    expect(updateMutate.mock.calls[0][0].patch.name).toBe('Аптека №2')
  })

  it('Удалить с подтверждением → deleteMutate', async () => {
    hooks.usePharmacy.mockReturnValue({ data: mkPharmacy(), isLoading: false, isError: false })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Удалить/ }))
    expect(deleteMutate).toHaveBeenCalledWith('ph_1', expect.anything())
  })

  it('confirm=false → delete не вызывается', async () => {
    hooks.usePharmacy.mockReturnValue({ data: mkPharmacy(), isLoading: false, isError: false })
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Удалить/ }))
    expect(deleteMutate).not.toHaveBeenCalled()
  })

  it('loading state', () => {
    hooks.usePharmacy.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    renderPage()
    expect(screen.getByText(/Загружаем аптеку…/)).toBeInTheDocument()
  })

  it('error state + Повторить', async () => {
    const refetch = vi.fn()
    hooks.usePharmacy.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('x'),
      refetch,
    })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Повторить/ }))
    expect(refetch).toHaveBeenCalled()
  })

  it('Назад → навигация на список', async () => {
    hooks.usePharmacy.mockReturnValue({ data: mkPharmacy(), isLoading: false, isError: false })
    const user = userEvent.setup()
    renderPage()
    await user.click(screen.getByRole('button', { name: /Все аптеки/ }))
    await waitFor(() => expect(screen.getByText('СПИСОК')).toBeInTheDocument())
  })
})
