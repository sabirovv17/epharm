import { describe, expect, it } from 'vitest'
import { resolveEpharmMediaUrl } from './media'

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
