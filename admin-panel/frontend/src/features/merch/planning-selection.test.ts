import { describe, expect, it, vi } from 'vitest'
import { cheapestPlace, exactKzt, suggestPlaces } from './planning-selection'
import type { CatalogBranch, PlacementGateway } from './placements-api'
import type { CommercialProduct, CommercialShelf } from './producer-commercial-api'
const month = '2026-11-01'
const product: CommercialProduct = {
  id: 'p',
  productName: 'Synthetic',
  sku: null,
  productCategory: '',
  productSubcategory: '',
  facingCount: 2,
  activeMonths: [month],
  shelfCategories: ['AA'],
}
const branch = (id = 'a'): CatalogBranch => ({
  id,
  name: 'Synthetic',
  address: 'Synthetic',
  city: 'Алматы',
  canCreate: true,
  canAssign: false,
  canViewPrices: true,
  items: [
    {
      shelfId: `s-${id}`,
      code: 'S1',
      saleMode: 'whole_or_halves',
      category: 'AA',
      facingMonthPrice: '500',
      whole: { price: 15000, available: true },
      left: { price: 8000, available: true },
      right: { price: 8000, available: true },
    },
  ],
})
describe('exact, scoped proposal before the single reservation writer', () => {
  it('includes facing fees and chooses an available nonoverlapping portion, never a cheaper ineligible shelf', () => {
    const b = branch()
    b.items.unshift({
      ...b.items[0],
      shelfId: 'wrong-category',
      category: 'BB',
      whole: { price: 0, available: true },
    })
    const result = cheapestPlace(b, product, month, [], 9000n, 'Алматы')!
    expect(result.total).toBe(9000n)
    expect(result.row.portion).toBe('left')
    expect(result.row.shelfId).toBe('s-a')
    expect(cheapestPlace(b, product, month, [result.row], 9000n, '')!.row.portion).toBe('right')
    expect(
      cheapestPlace(b, product, month, [{ ...result.row, portion: 'whole' }], 100000n, ''),
    ).toBeNull()
    expect(cheapestPlace(b, product, month, [], 8999n, '')).toBeNull()
  })
  it('does not guess an unknown tariff, fractional/unsafe money, inactive month or denied price scope', () => {
    expect(exactKzt('9007199254740993')).toBe(9007199254740993n)
    for (const value of [NaN, Infinity, 9007199254740992, '1.5', '-1', '1e10'])
      expect(exactKzt(value)).toBeNull()
    const b = branch()
    expect(
      cheapestPlace({ ...b, canViewPrices: false }, product, month, [], 100000n, ''),
    ).toBeNull()
    expect(cheapestPlace(b, product, '2026-12-01', [], 100000n, '')).toBeNull()
    b.items[0].facingMonthPrice = null
    expect(cheapestPlace(b, product, month, [], 100000n, '')).toBeNull()
  })
  it('uses exact BigInt totals above JS integer precision without turning them into a numeric API amount', () => {
    const b = branch()
    b.items[0].facingMonthPrice = '9007199254740993'
    const result = cheapestPlace(b, product, month, [], 9223372036854775807n, '')!
    expect(result.total).toBe(18014398509489986n)
    expect(result.row.price).toBe('8000')
  })
  it('paginates the whole authorized region, reports a shortfall and never saves or reserves a preview', async () => {
    const catalog = vi
      .fn()
      .mockResolvedValueOnce({ branches: [branch('a')], total: 2, limit: 1 })
      .mockResolvedValueOnce({ branches: [branch('b')], total: 2, limit: 1 })
    const gateway = {
      realm: 'admin',
      catalog,
      save: vi.fn(),
      transition: vi.fn(),
    } as unknown as PlacementGateway
    const result = await suggestPlaces(
      gateway,
      'producer',
      month,
      product,
      [],
      3,
      18000n,
      '',
      new AbortController().signal,
      vi.fn(),
    )
    expect(result.rows).toHaveLength(2)
    expect(result.complete).toBe(false)
    expect(result.total).toBe(18000n)
    expect(catalog.mock.calls.map((args) => args[2])).toEqual([0, 1])
    expect(gateway.save).not.toHaveBeenCalled()
    expect(gateway.transition).not.toHaveBeenCalled()
  })
  it('loads producer branch availability through its own gateway and respects cancellation and bounds', async () => {
    const b = branch()
    const catalog = vi
      .fn()
      .mockResolvedValueOnce({ branches: [{ ...b, items: [] }], total: 1, limit: 50 })
      .mockResolvedValueOnce({ branches: [b], total: 1, limit: 50 })
    const gateway = { realm: 'producer', catalog } as unknown as PlacementGateway
    expect(
      (
        await suggestPlaces(
          gateway,
          'own',
          month,
          product,
          [],
          1,
          9000n,
          '',
          new AbortController().signal,
          vi.fn(),
        )
      ).rows,
    ).toHaveLength(1)
    expect(catalog.mock.calls[1][4]).toBe(b.id)
    const aborted = new AbortController()
    aborted.abort()
    catalog.mockResolvedValue({ branches: [b], total: 1, limit: 50 })
    await expect(
      suggestPlaces(
        gateway,
        'own',
        month,
        product,
        [] as CommercialShelf[],
        1,
        9000n,
        '',
        aborted.signal,
        vi.fn(),
      ),
    ).rejects.toThrow()
    catalog.mockResolvedValue({ branches: [], total: 1001, limit: 50 })
    await expect(
      suggestPlaces(
        gateway,
        'own',
        month,
        product,
        [],
        1,
        9000n,
        '',
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toThrow('1000')
  })
})
