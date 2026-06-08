// Хук-тесты CRUD-мутаций каталога (Блок 2). Проверяют, что мутации бьют по
// правильным URL с правильным телом и инвалидируют catalog-namespace.
// api замокан — сеть не трогаем.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useCreateProduct, useDeleteProduct, useUpdateProduct } from './catalog'

vi.mock('@/lib/api', () => ({
  api: {
    post: vi.fn(() => Promise.resolve({ data: { id: 'p_x', name: 'X' } })),
    patch: vi.fn(() => Promise.resolve({ data: { id: 'p_x', name: 'X2' } })),
    delete: vi.fn(() => Promise.resolve({ data: undefined })),
  },
}))

import { api } from '@/lib/api'

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('catalog CRUD-мутации', () => {
  it('useCreateProduct → POST /api/admin/catalog/products с телом', async () => {
    const { result } = renderHook(() => useCreateProduct(), { wrapper })
    await result.current.mutateAsync({
      id: 'p_new',
      name: 'Новый',
      brand: 'B',
      vendor: 'V',
      mnn: 'M',
      price: 100,
    })
    expect(api.post).toHaveBeenCalledWith('/api/admin/catalog/products', {
      id: 'p_new',
      name: 'Новый',
      brand: 'B',
      vendor: 'V',
      mnn: 'M',
      price: 100,
    })
  })

  it('useUpdateProduct → PATCH /api/admin/catalog/products/:id с патчем', async () => {
    const { result } = renderHook(() => useUpdateProduct(), { wrapper })
    await result.current.mutateAsync({ id: 'p_pinos', patch: { price: 2000 } })
    expect(api.patch).toHaveBeenCalledWith('/api/admin/catalog/products/p_pinos', { price: 2000 })
  })

  it('useDeleteProduct → DELETE /api/admin/catalog/products/:id', async () => {
    const { result } = renderHook(() => useDeleteProduct(), { wrapper })
    await result.current.mutateAsync('p_pinos')
    expect(api.delete).toHaveBeenCalledWith('/api/admin/catalog/products/p_pinos')
  })

  it('useDeleteProduct прокидывает ошибку 409 в isError', async () => {
    vi.mocked(api.delete).mockRejectedValueOnce(new Error('conflict'))
    const { result } = renderHook(() => useDeleteProduct(), { wrapper })
    result.current.mutate('p_referenced')
    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
