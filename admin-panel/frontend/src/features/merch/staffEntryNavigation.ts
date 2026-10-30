import { captureTaskNavigation } from './taskNavigation'

export type StaffEntry =
  | { kind: 'plain' | 'invalid' | 'expired_claim' }
  | { kind: 'access'; access: string }

let entry: StaffEntry = { kind: 'plain' }
const legacyPaths = new Set(['/staff', '/merch/staff'])

/** Runs before telemetry. Each legacy entry has one authority type; mixed credentials deny. */
export function captureStaffEntryNavigation() {
  const path = window.location.pathname
  if (!legacyPaths.has(path) && path !== '/tasks/staff') return
  const query = new URLSearchParams(window.location.search)
  const fragment = new URLSearchParams(window.location.hash.slice(1))
  const has = (key: string) => query.has(key) || fragment.has(key)
  const task = has('task')
  const access = has('access')
  const claim = has('claim') || has('grant')
  const mixed = Number(task) + Number(access) + Number(claim) > 1
  if (!mixed && (path === '/tasks/staff' || task)) {
    captureTaskNavigation()
    return
  }
  entry = { kind: 'plain' }
  if (mixed || path === '/tasks/staff') entry = { kind: 'invalid' }
  else if (access) {
    const values = [...query.getAll('access'), ...fragment.getAll('access')]
    entry =
      values.length === 1 && /^[A-Za-z0-9_-]{32,256}$/.test(values[0])
        ? { kind: 'access', access: values[0] }
        : { kind: 'invalid' }
  } else if (claim) {
    entry =
      !query.has('claim') &&
      !query.has('grant') &&
      fragment.getAll('claim').length === 1 &&
      fragment.getAll('grant').length === 1 &&
      /^[A-Za-z0-9._~-]{16,4096}$/.test(fragment.get('claim') || '') &&
      /^[A-Za-z0-9_-]{32,256}$/.test(fragment.get('grant') || '')
        ? { kind: 'expired_claim' }
        : { kind: 'invalid' }
  }
  window.history.replaceState(window.history.state, '', '/staff')
  let referrer = document.querySelector<HTMLMetaElement>('meta[name="referrer"]')
  if (!referrer) {
    referrer = document.createElement('meta')
    referrer.name = 'referrer'
    document.head.append(referrer)
  }
  referrer.content = 'no-referrer'
}

export function staffEntry() {
  return entry
}
export function clearStaffEntry() {
  entry = { kind: 'plain' }
}
