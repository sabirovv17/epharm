import { describe, expect, it } from 'vitest'
import { collectProductBarcodes } from './productBarcodes'

describe('source product barcodes', () => {
  it('keeps canonical first, all aliases, leading zeros and order without duplicates', () => {
    expect(collectProductBarcodes({ barcode: ' 0012345678905 ', barcodes: ['4601164003157', '0012345678905', 'LOCAL/01'] }))
      .toEqual(['0012345678905', '4601164003157', 'LOCAL/01'])
  })
  it('unpacks complete legacy GTIN tokens but never chops long numbers or local identifiers', () => {
    expect(collectProductBarcodes({ barcode: '_0012345678905_4601164003157_', barcodes: ['LOC_001', 'abc12345678xyz'] }))
      .toEqual(['0012345678905', '4601164003157', 'LOC_001', 'abc12345678xyz'])
    expect(collectProductBarcodes({ barcode: '123456789012345' })).toEqual(['123456789012345'])
  })
  it('supports a plural-only source and never invents an identity for an empty or malformed source', () => {
    expect(collectProductBarcodes({ barcodes: ['0012345678905'] })).toEqual(['0012345678905'])
    expect(collectProductBarcodes({ barcode: ' ', barcodes: ['', '1'.repeat(33), '\u0000'] })).toEqual([])
  })
  it('bounds each code to 32 characters and the unique set to 100', () => {
    const barcodes = Array.from({ length: 110 }, (_, index) => `LOCAL-${index}`)
    expect(collectProductBarcodes({ barcode: 'A'.repeat(32), barcodes })).toHaveLength(100)
    expect(collectProductBarcodes({ barcode: 'A'.repeat(33), barcodes: ['LOCAL-01'] })).toEqual(['LOCAL-01'])
  })
})
