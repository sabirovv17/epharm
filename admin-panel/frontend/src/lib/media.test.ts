import { describe, expect, it } from 'vitest'
import { resolveEpharmMediaUrl, safeImageSrc } from './media'

describe('safeImageSrc', () => {
  it('accepts same-origin relative and remote HTTPS images', () => {
    expect(safeImageSrc('/api/media/img?u=encoded')).toBe('/api/media/img?u=encoded')
    expect(safeImageSrc('assets/product.webp')).toBe('assets/product.webp')
    expect(safeImageSrc('https://cdn.example/product.webp')).toBe(
      'https://cdn.example/product.webp',
    )
  })

  it('allows HTTP only for loopback development hosts', () => {
    expect(safeImageSrc('http://localhost:8080/image.webp')).toBe(
      'http://localhost:8080/image.webp',
    )
    expect(safeImageSrc('http://cdn.example/image.webp')).toBeUndefined()
  })

  it.each([
    'javascript:alert(1)',
    'data:image/svg+xml,<svg onload=alert(1)>',
    'blob:https://example.test/id',
    '//cdn.example/image.webp',
    'file:///etc/passwd',
  ])('rejects active-content or ambiguous source %s', (source) => {
    expect(safeImageSrc(source)).toBeUndefined()
  })
})

describe('resolveEpharmMediaUrl', () => {
  const storedVideo =
    'https://epharm.inkar.kz/s3/epharm-receipts/screens/3d4d40f7-5cc2-4ea6-a038-7f4de9796ccc.mp4?version=1'

  it('uses the temporary HTTP origin for a stored video when the console is opened on port 8060', () => {
    expect(resolveEpharmMediaUrl(storedVideo, 'http://epharm.inkar.kz:8060')).toBe(
      'http://epharm.inkar.kz:8060/s3/epharm-receipts/screens/3d4d40f7-5cc2-4ea6-a038-7f4de9796ccc.mp4?version=1',
    )
  })

  it('keeps the public HTTPS URL unchanged when it is already the current origin', () => {
    expect(resolveEpharmMediaUrl(storedVideo, 'https://epharm.inkar.kz')).toBe(storedVideo)
  })

  it('does not rewrite third-party media URLs', () => {
    const externalVideo = 'https://cdn.example.com/s3/epharm-receipts/screens/promo.mp4'
    expect(resolveEpharmMediaUrl(externalVideo, 'http://epharm.inkar.kz:8060')).toBe(externalVideo)
  })
})
