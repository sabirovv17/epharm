/** Source identifiers only: never derive a barcode from a product id, SKU or name. */
export function collectProductBarcodes(product: {
  barcode?: string | null
  barcodes?: readonly string[] | null
}): string[] {
  const result = new Set<string>()
  for (const raw of [product.barcode, ...(Array.isArray(product.barcodes) ? product.barcodes : [])]) {
    if (typeof raw !== 'string') continue
    const value = raw.trim()
    if (!value) continue
    const packed = value.split(/[_;,|\s]+/).filter(Boolean)
    const values = packed.length > 0 && packed.every((code) => /^\d{8,14}$/.test(code))
      ? packed
      : value.length <= 32 && !/\p{Cc}/u.test(value) ? [value] : []
    for (const code of values) {
      result.add(code)
      if (result.size === 100) return [...result]
    }
  }
  return [...result]
}
