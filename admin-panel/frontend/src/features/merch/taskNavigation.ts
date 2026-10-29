let token: string | null = null

/** Called synchronously before telemetry. Neither tokens nor arbitrary query values remain in the URL. */
export function captureTaskNavigation() {
  if (window.location.pathname !== '/tasks/staff') return
  const values = new URLSearchParams(window.location.hash.slice(1)).getAll('task')
  token = values.length === 1 && /^[A-Za-z0-9_-]{32,256}$/.test(values[0]) ? values[0] : null
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
