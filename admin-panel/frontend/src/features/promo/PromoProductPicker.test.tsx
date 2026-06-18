// Тесты PromoProductPicker.
// Покрытие:
//  - T6: серверная пагинация + «Показать ещё» (накопление страниц).
//  - п.8 (РЕГРЕСС поиска): при смене запроса НЕ показывать stale-результаты
//    прошлого q. Корень бага был в placeholderData: react-query отдавал данные
//    предыдущего запроса как результат нового → в выдаче «витамин» висели первые
//    50 из всех 27975 товаров, total мигал 27975 вместо релевантных 528.
//
// Мокаем useStorefront. Для пагинации — срез по offset/limit. Для регресса поиска —
// разные ответы по аргументу q: '' → нерелевантные (Везилют, total 27975),
// 'витамин' → релевантные (Now Витамин С, total 528).

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

function mkProduct(over: Partial<StorefrontProductDto> & { id: string }): StorefrontProductDto {
  return {
    name: `Товар ${over.id}`,
    brand: 'Бренд',
    mnn: null,
    rxOtc: null,
    price: 1000,
    currency: 'KZT',
    imageUrl: null,
    barcode: null,
    category: null,
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

// ─── T6: пагинация ───────────────────────────────────────────────────────────
describe('PromoProductPicker — пагинация (T6)', () => {
  // Эмулируем серверную пагинацию: возвращаем срез по offset/limit от total=120.
  const TOTAL = 120
  function page(offset: number, limit: number): StorefrontPageDto {
    const items: StorefrontProductDto[] = []
    for (let i = offset; i < Math.min(offset + limit, TOTAL); i++) {
      items.push(mkProduct({ id: `prod_${i}`, name: `Товар ${i}`, price: 1000 + i }))
    }
    return { items, total: TOTAL, limit, offset }
  }

  beforeEach(() => {
    storefrontHooks.useStorefront.mockImplementation((_q: string, offset: number, limit = 50) => ({
      data: page(offset, limit),
      isFetching: false,
    }))
  })

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

  // Ревью-баг #5: если сервер на «Показать ещё» вернул страницу без НОВЫХ id
  // (дубликаты при рассинхроне дедупа с total) — кнопка не должна остаться вечной
  // мёртвой. Пагинация закрывается: total схлопывается до фактически загруженного.
  it('страница-дубликат на «Показать ещё» закрывает пагинацию (нет мёртвой кнопки)', async () => {
    // page 0 → товары 0..49; любой offset>0 → ТЕ ЖЕ 0..49 (дубликаты), total=120.
    storefrontHooks.useStorefront.mockImplementation((_q: string, offset: number, limit = 50) => {
      const items: StorefrontProductDto[] = []
      for (let i = 0; i < limit; i++) items.push(mkProduct({ id: `prod_${i}`, name: `Товар ${i}` }))
      return { data: { items, total: 120, limit, offset }, isFetching: false }
    })
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    expect(screen.getByText(/Показано 50 из 120/)).toBeInTheDocument()
    await user.click(screen.getByTestId('pp-load-more'))
    // Новых id нет → пагинация закрыта: «Показать ещё» исчезла, total = 50.
    await waitFor(() => expect(screen.queryByTestId('pp-load-more')).not.toBeInTheDocument())
    expect(screen.getByText(/Показано 50 из 50/)).toBeInTheDocument()
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

// ─── п.8: регресс поиска — не показывать stale-результаты прошлого запроса ─────
describe('PromoProductPicker — регресс поиска (п.8: без stale-результатов)', () => {
  // Реалистичный сценарий бага: пустой q → весь каталог (нерелевантный Везилют,
  // total 27975); q='витамин' → релевантная выдача (Now Витамин С, total 528).
  const EMPTY: StorefrontPageDto = {
    items: [mkProduct({ id: 'prod_vez', name: 'Везилют' })],
    total: 27975,
    limit: 50,
    offset: 0,
  }
  const VITAMIN: StorefrontPageDto = {
    items: [mkProduct({ id: 'prod_vit', name: 'Now Витамин С', price: 4990 })],
    total: 528,
    limit: 50,
    offset: 0,
  }

  beforeEach(() => {
    // Ответ зависит ТОЛЬКО от текущего q — мок не «залипает» на прошлом запросе.
    // Это воспроизводит честный бэкенд: q='' и q='витамин' возвращают разное.
    storefrontHooks.useStorefront.mockImplementation((q: string) => ({
      data: q === 'витамин' ? VITAMIN : EMPTY,
      isFetching: false,
    }))
  })

  it('3) начальная выдача (пустой q) корректна — показан весь каталог и его total', () => {
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    expect(screen.getByText('Везилют')).toBeInTheDocument()
    expect(screen.getByText(/Показано 1 из 27975/)).toBeInTheDocument()
  })

  it('1) после ввода «витамин» в списке ТОЛЬКО релевантные товары, без stale «Везилют»', async () => {
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    // До ввода — stale-кандидат на экране
    expect(screen.getByText('Везилют')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText(/Поиск товара/), 'витамин')

    // После debounce (300мс) выдача переключается на релевантную.
    await waitFor(() => expect(screen.getByText('Now Витамин С')).toBeInTheDocument())
    // Ключевая проверка регресса: прошлый результат НЕ висит в списке.
    expect(screen.queryByText('Везилют')).not.toBeInTheDocument()
  })

  it('2) строка «показано X из Y» показывает total ТЕКУЩЕГО запроса (528), а не stale 27975', async () => {
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={vi.fn()} />)
    expect(screen.getByText(/Показано 1 из 27975/)).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText(/Поиск товара/), 'витамин')

    await waitFor(() => expect(screen.getByText(/Показано 1 из 528/)).toBeInTheDocument())
    // Stale total прошлого запроса не должен оставаться на экране.
    expect(screen.queryByText(/27975/)).not.toBeInTheDocument()
  })

  it('выбор найденного товара отдаёт снимок именно релевантного товара', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<PromoProductPicker value={null} onChange={onChange} />)
    await user.type(screen.getByPlaceholderText(/Поиск товара/), 'витамин')
    await waitFor(() => expect(screen.getByText('Now Витамин С')).toBeInTheDocument())
    await user.click(screen.getByText('Now Витамин С'))
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ medusaProductId: 'prod_vit', productName: 'Now Витамин С' }),
    )
  })
})

// ─── MultiProductPicker (T2) ─────────────────────────────────────────────────
describe('MultiProductPicker (T2)', () => {
  beforeEach(() => {
    storefrontHooks.useStorefront.mockImplementation(() => ({
      data: {
        items: [
          mkProduct({ id: 'prod_3', name: 'Товар 3' }),
          mkProduct({ id: 'prod_7', name: 'Товар 7' }),
        ],
        total: 2,
        limit: 50,
        offset: 0,
      },
      isFetching: false,
    }))
  })

  it('клик по товару зовёт onPick; уже выбранные помечены', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(<MultiProductPicker selectedIds={['prod_3']} onPick={onPick} />)
    await user.click(screen.getByText('Товар 7'))
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 'prod_7' }))
  })
})
