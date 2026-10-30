import type { CatalogBranch, PlacementGateway, Portion } from './placements-api'
import type { CommercialProduct, CommercialShelf } from './producer-commercial-api'

export function exactKzt(value: string | number | null | undefined): bigint | null {
  if (
    value == null ||
    (typeof value === 'number' && !Number.isSafeInteger(value)) ||
    !/^\d+$/.test(String(value))
  )
    return null
  return BigInt(value)
}
export function cheapestPlace(
  branch: CatalogBranch,
  product: CommercialProduct,
  month: string,
  selected: CommercialShelf[],
  remaining: bigint,
  city: string,
) {
  if (
    !branch.canCreate ||
    !branch.canViewPrices ||
    (city && branch.city.toLocaleLowerCase('ru') !== city.toLocaleLowerCase('ru')) ||
    !product.activeMonths.includes(month)
  )
    return null
  const candidates: { row: CommercialShelf; total: bigint }[] = []
  for (const place of branch.items) {
    if (product.shelfCategories.length && !product.shelfCategories.includes(place.category ?? ''))
      continue
    const fee = exactKzt(place.facingMonthPrice)
    if (fee == null) continue
    for (const portion of ['whole', 'left', 'right'] as Portion[]) {
      const offer = place[portion]
      const price = exactKzt(offer?.price)
      if (!offer?.available || price == null) continue
      if (
        selected.some(
          (row) =>
            row.shelfId === place.shelfId &&
            row.placementMonths.includes(month) &&
            (row.portion === portion || row.portion === 'whole' || portion === 'whole'),
        )
      )
        continue
      const total = price + fee * BigInt(product.facingCount)
      if (total > remaining || total > 9223372036854775807n) continue
      candidates.push({
        total,
        row: {
          id: crypto.randomUUID(),
          shelfId: place.shelfId,
          pharmacyId: branch.id,
          portion,
          placementMonths: [month],
          products: [structuredClone(product)],
          price: String(price),
          code: place.code,
          pharmacyName: branch.name,
          city: branch.city,
          address: branch.address,
        },
      })
    }
  }
  return (
    candidates.sort((a, b) =>
      a.total < b.total
        ? -1
        : a.total > b.total
          ? 1
          : `${a.row.shelfId}:${a.row.portion}`.localeCompare(`${b.row.shelfId}:${b.row.portion}`),
    )[0] ?? null
  )
}

/** Preview only. The reservation writer rechecks every saved item and tariff on explicit submit. */
export async function suggestPlaces(
  gateway: PlacementGateway,
  producerId: string,
  month: string,
  product: CommercialProduct,
  selected: CommercialShelf[],
  count: number,
  budget: bigint,
  city: string,
  signal: AbortSignal,
  progress: (count: number) => void,
) {
  if (
    !Number.isInteger(count) ||
    count < 1 ||
    count > 50 ||
    budget < 0n ||
    !product.activeMonths.includes(month)
  )
    throw new Error('Выберите товар, месяц, от 1 до 50 аптек и целый неотрицательный бюджет.')
  const rows: CommercialShelf[] = []
  let total = 0n
  let checked = 0
  let offset = 0
  const seen = new Set<string>()
  for (;;) {
    const page = await gateway.catalog(producerId, month, offset, signal)
    if (page.total > 1000)
      throw new Error(
        'В области больше 1000 аптек: уточните область доступа перед подбором. Частичный результат не применён.',
      )
    if (!page.branches.length && offset < page.total)
      throw new Error('Каталог изменился: обновите список и повторите подбор.')
    for (const initial of page.branches) {
      signal.throwIfAborted()
      if (seen.has(initial.id)) continue
      seen.add(initial.id)
      if (city && initial.city.toLocaleLowerCase('ru') !== city.toLocaleLowerCase('ru')) continue
      let branch = initial
      if (gateway.realm === 'producer') {
        const detail = await gateway.catalog(producerId, month, offset, signal, initial.id)
        const found = detail.branches.find((item) => item.id === initial.id)
        if (!found) throw new Error('Аптека стала недоступна. Подбор не применён.')
        branch = found
      }
      checked++
      progress(checked)
      const candidate = cheapestPlace(
        branch,
        product,
        month,
        [...selected, ...rows],
        budget - total,
        city,
      )
      if (candidate) {
        rows.push(candidate.row)
        total += candidate.total
      }
      if (rows.length === count) return { rows, total, checked, complete: true }
    }
    offset += page.limit
    if (offset >= page.total) return { rows, total, checked, complete: false }
    if (page.limit < 1 || offset > 1000)
      throw new Error('Некорректная страница каталога; результат не применён.')
  }
}
