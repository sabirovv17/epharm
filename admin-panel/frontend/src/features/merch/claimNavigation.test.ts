import { beforeEach, describe, expect, it } from 'vitest'
import { captureMerchClaimNavigation, merchClaimChallenge, trustedMerchRedirect } from './claimNavigation'

const challenge = 'v1.signed-challenge-payload.signature'
const grant = 'opaque_grant_that_is_at_least_32_characters'
const returnUrl = `https://crm.inkar.kz/staff#claim=signed_nonce_123456789&grant=${grant}`

beforeEach(() => { sessionStorage.clear(); localStorage.clear() })

describe('merch claim capability routing', () => {
  it('removes capability from URL before telemetry and keeps it out of browser storage', () => {
    window.history.replaceState({}, '', `/learn/merch/claim#challenge=${challenge}`)
    captureMerchClaimNavigation()
    expect(merchClaimChallenge()).toBe(challenge)
    expect(window.location.hash).toBe('')
    expect(sessionStorage.length).toBe(0)
    expect(localStorage.length).toBe(0)
  })

  it.each([`?challenge=${challenge}`, `#challenge=${challenge}&challenge=${challenge}`, '#challenge=x', '#challenge=https://evil.example'])('rejects missing or ambiguous capabilities: %s', (suffix) => {
    window.history.replaceState({}, '', `/learn/merch/claim${suffix}`)
    captureMerchClaimNavigation()
    expect(merchClaimChallenge()).toBeNull()
    expect(window.location.hash).toBe('')
    expect(window.location.search).toBe('')
  })

  it('accepts only the exact CRM staff handoff with the issued opaque grant', () => {
    expect(trustedMerchRedirect(returnUrl, grant)).toBe(returnUrl)
  })

  it.each([
    'not-a-url',
    'javascript:alert(1)',
    returnUrl.replace('https://crm.inkar.kz', 'https://evil.example'),
    returnUrl.replace('https://crm.inkar.kz', 'http://crm.inkar.kz'),
    returnUrl.replace('/staff#', '/admin#'),
    returnUrl.replace('/staff#', '/staff?token=secret#'),
    `${returnUrl}&access_token=never-send-jwt`,
    `${returnUrl}&grant=${grant}`,
    returnUrl.replace(grant, 'another_grant_that_is_at_least_32_chars'),
    returnUrl.replace('crm.inkar.kz', 'user:pass@crm.inkar.kz'),
  ])('rejects an untrusted or mismatched handoff: %s', (url) => {
    expect(() => trustedMerchRedirect(url, grant)).toThrow()
  })
})
