import { beforeEach, describe, expect, it } from 'vitest'
import { captureTaskNavigation, taskCapabilityToken } from './taskNavigation'
const token = 'synthetic_task_capability_000000000000000000000000'
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})
describe('native task capability bootstrap', () => {
  it('removes the fragment before telemetry and keeps a valid capability only in memory', () => {
    window.history.replaceState({}, '', `/tasks/staff?task=1#task=${token}`)
    captureTaskNavigation()
    expect(taskCapabilityToken()).toBe(token)
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(
      '/tasks/staff?task=1',
    )
    expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe(
      'no-referrer',
    )
    expect(localStorage.length + sessionStorage.length).toBe(0)
  })
  it.each([
    `?task=${token}`,
    `#task=${token}&task=${token}`,
    '#task=short',
    '#task=https://example.invalid',
  ])('does not accept a query, duplicate or malformed capability: %s', (suffix) => {
    window.history.replaceState({}, '', `/tasks/staff${suffix}`)
    captureTaskNavigation()
    expect(taskCapabilityToken()).toBeNull()
    expect(window.location.hash).toBe('')
    expect(window.location.search).toBe('?task=1')
  })
})
