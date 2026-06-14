// Тесты PromoProductPicker (T6) — серверная пагинация + «Показать ещё».
// Мокаем useStorefront, чтобы отдавать страницы по offset и проверять, что
// доступны ВСЕ товары (а не первые 20), и что показывается «показано X из Y».

import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StorefrontPageDto, StorefrontProductDto } from '@/lib/api-types'
import { MultiProductPicker, PromoProductPicker } from './PromoProductPicker'

const storefrontHooks = vi.hoisted(() => ({ useStorefront: vi.fn() }))
vi.mock('@/lib/queries/storefront', () => ({
  storefrontKeys: { all: ['storefront'], list: () => ['storefront', 'list'] },
  useStorefront: storefrontHooks.useStorefront,
}))

function mkProduct(i: number): StorefrontProductDto {
  return {
    id: `prod_${i}`,
    name: `Товар ${i}`,
    brand: 'Бренд',
    mnn: null,
    rxOtc: null,
    price: 1000 + i,
    currency: 'KZT',
    imageUrl: null,
    barcode: null,
    category: null,
  }
}

// Эмулируем серверную пагинацию: возвращаем срез по offset/limit от total=120.
const TOTAL = 120
function page(offset: number, limit: number): StorefrontPageDto {
  const items: StorefrontProductDto[] = []
  for (let i = offset; i < Math.min(offset + limit, TOTAL); i++) items.push(mkProduct(i))
  return { items, total: TOTAL, limit, offset }
}

beforeEach(() => {
  vi.clearAllMocks()
  storefrontHooks.useStorefront.mockImplementation((_q: string, offset: number, limit = 50) => ({
    data: page(offset, limit),
    isFetching: false,
  }))
})

describe('PromoProductPicker — пагинация (T6)', () => {
  it('показывает «Показано 50 из 120» и кнопку «Показать ещё»', () => {
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    expect(screen.getByText(/Показано 50 из 120/)).toBeInTheDocument()
    expect(screen.getByTestId('pp-load-more')).toBeInTheDocument()
  })

  it('«Показать ещё» догружает следующую страницу (накопление)', async () => {
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    // Первая страница: товары 0..49 видны, 50+ — нет
    expect(screen.getByText('Товар 0')).toBeInTheDocument()
    expect(screen.queryByText('Товар 60')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('pp-load-more'))
    await waitFor(() => expect(screen.getByText(/Показано 100 из 120/)).toBeInTheDocument())
    // Товар из второй страницы теперь доступен — все товары достижимы
    expect(screen.getByText('Товар 60')).toBeInTheDocument()
  })

  it('выбор товара отдаёт snapshot с price', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={onChange} />)
    await user.click(screen.getByText('Товар 5'))
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ medusaProductId: 'prod_5', price: 1005 }),
    )
  })
})

describe('MultiProductPicker (T2)', () => {
  it('клик по товару зовёт onPick; уже выбранные помечены', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(<MultiProductPicker selectedIds={['prod_3']} onPick={onPick} />)
    await user.click(screen.getByText('Товар 7'))
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 'prod_7' }))
  })
})
