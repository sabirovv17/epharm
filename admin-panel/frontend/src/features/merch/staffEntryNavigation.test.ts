import { beforeEach, describe, expect, it } from 'vitest'
import { captureStaffEntryNavigation, clearStaffEntry, staffEntry } from './staffEntryNavigation'
import { taskCapabilityToken } from './taskNavigation'
const access = 'synthetic_staff_access_0000000000000000000000000'
const task = 'synthetic_task_capability_00000000000000000000000'
beforeEach(() => {
  clearStaffEntry()
  sessionStorage.clear()
  localStorage.clear()
})
describe('one pre-telemetry staff entry dispatcher', () => {
  it.each(['/staff', '/merch/staff'])(
    'keeps retired plain %s separate from task capabilities',
    (path) => {
      window.history.replaceState({}, '', path)
      captureStaffEntryNavigation()
      expect(staffEntry()).toEqual({ kind: 'plain' })
      expect(window.location.pathname + window.location.search).toBe('/staff')
    },
  )
  it.each([`/staff?access=${access}`, `/merch/staff?access=${access}`, `/staff#access=${access}`])(
    'preserves old QR and new fragment entry: %s',
    (url) => {
      window.history.replaceState({}, '', url)
      captureStaffEntryNavigation()
      expect(staffEntry()).toEqual({ kind: 'access', access })
      expect(window.location.href).not.toContain(access)
      expect(window.location.pathname).toBe('/staff')
      expect(sessionStorage.length + localStorage.length).toBe(0)
      expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe(
        'no-referrer',
      )
    },
  )
  it.each([
    `/staff?task=${task}`,
    `/merch/staff?task=1#task=${task}`,
    `/tasks/staff?task=1#task=${task}`,
  ])('retains the real task capability path: %s', (url) => {
    window.history.replaceState({}, '', url)
    captureStaffEntryNavigation()
    expect(taskCapabilityToken()).toBe(task)
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(
      '/tasks/staff?task=1',
    )
  })
  it('consumes an old claim without pretending it was redeemed', () => {
    window.history.replaceState({}, '', `/staff#claim=synthetic_claim_000000&grant=${access}`)
    captureStaffEntryNavigation()
    expect(staffEntry()).toEqual({ kind: 'expired_claim' })
    expect(window.location.pathname + window.location.hash).toBe('/staff')
    expect(sessionStorage.length).toBe(0)
  })
  it.each([
    `?access=${access}&access=${access}`,
    `?access=${access}#access=${access}`,
    `?task=${task}#access=${access}`,
    `?access=${access}#claim=synthetic_claim_000000&grant=${task}`,
    '#claim=synthetic_claim_000000',
    '#access=short',
  ])('rejects mixed/duplicate or incomplete authority: %s', (suffix) => {
    window.history.replaceState({}, '', '/staff' + suffix)
    captureStaffEntryNavigation()
    expect(staffEntry()).toEqual({ kind: 'invalid' })
    expect(window.location.search + window.location.hash).toBe('')
  })
  it('does not consume the independent learner claim route', () => {
    const url = '/learn/merch/claim#challenge=synthetic_challenge_000000'
    window.history.replaceState({}, '', url)
    captureStaffEntryNavigation()
    expect(window.location.pathname + window.location.hash).toBe(url)
  })
})
