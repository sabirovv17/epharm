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
  it.each(['/staff', '/merch/staff'])(
    'consumes both exact legacy task URL shapes at %s before telemetry',
    (path) => {
      for (const suffix of [`?task=${token}&unknown=discard`, `?task=1#task=${token}`]) {
        window.history.replaceState({}, '', path + suffix)
        captureTaskNavigation()
        expect(taskCapabilityToken()).toBe(token)
        expect(window.location.pathname + window.location.search + window.location.hash).toBe(
          '/tasks/staff?task=1',
        )
        expect(localStorage.length + sessionStorage.length).toBe(0)
      }
    },
  )
  it.each([
    `?task=${token}#task=${token}`,
    `?task=${token}&task=${token}`,
    `?task=1#task=${token}&task=${token}`,
    '?task=short',
  ])('rejects ambiguous or invented legacy token keys and still scrubs them: %s', (suffix) => {
    window.history.replaceState({}, '', '/merch/staff' + suffix)
    captureTaskNavigation()
    expect(taskCapabilityToken()).toBeNull()
    expect(window.location.href).not.toContain(token)
    expect(window.location.pathname).toBe('/tasks/staff')
  })
  it('leaves non-task legacy entries to the staff bootstrap dispatcher', () => {
    window.history.replaceState({}, '', `/staff?access=${token}`)
    captureTaskNavigation()
    expect(window.location.pathname + window.location.search).toBe(`/staff?access=${token}`)
  })
  it('does not capture a capability from a lookalike route', () => {
    window.history.replaceState({}, '', `/merch/staff-other?task=${token}`)
    captureTaskNavigation()
    expect(window.location.pathname).toBe('/merch/staff-other')
  })
})
