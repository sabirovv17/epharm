let token: string | null = null

/** Called synchronously before telemetry. Neither tokens nor arbitrary query values remain in the URL. */
export function captureTaskNavigation() {
  const path = window.location.pathname
  const legacy = path === '/merch/staff' || path === '/staff'
  if (path !== '/tasks/staff' && !legacy) return
  if (
    legacy &&
    !new URLSearchParams(window.location.search).has('task') &&
    !new URLSearchParams(window.location.hash.slice(1)).has('task')
  )
    return
  const fragments = new URLSearchParams(window.location.hash.slice(1)).getAll('task')
  const queries = new URLSearchParams(window.location.search).getAll('task')
  const valid = (value: string) => /^[A-Za-z0-9_-]{32,256}$/.test(value)
  // Legacy issuers used either ?task=CAP or ?task=1#task=CAP. Ambiguous inputs fail closed.
  const fragment =
    fragments.length === 1 &&
    valid(fragments[0]) &&
    (!legacy || queries.length === 0 || (queries.length === 1 && queries[0] === '1'))
  const query = legacy && fragments.length === 0 && queries.length === 1 && valid(queries[0])
  token = fragment ? fragments[0] : query ? queries[0] : null
  window.history.replaceState(window.history.state, '', '/tasks/staff?task=1')
  let referrer = document.querySelector<HTMLMetaElement>('meta[name="referrer"]')
  if (!referrer) {
    referrer = document.createElement('meta')
    referrer.name = 'referrer'
    document.head.append(referrer)
  }
  referrer.content = 'no-referrer'
}
export function taskCapabilityToken() {
  return token
}
