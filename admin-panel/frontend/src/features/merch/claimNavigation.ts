const CLAIM_PATH = '/learn/merch/claim'
let pendingChallenge: string | null = null

// Run before telemetry starts: capabilities belong in memory, not URLs or browser storage.
export function captureMerchClaimNavigation() {
  if (window.location.pathname !== CLAIM_PATH) return
  const values = new URLSearchParams(window.location.hash.slice(1)).getAll('challenge')
  pendingChallenge = values.length === 1 && /^[A-Za-z0-9._~-]{16,4096}$/.test(values[0])
    ? values[0] : null
  window.history.replaceState(window.history.state, '', CLAIM_PATH)
}

export function merchClaimChallenge() {
  return pendingChallenge
}

export function trustedMerchRedirect(raw: string, grant: string): string {
  let url: URL
  try { url = new URL(raw) }
  catch { throw new Error('Не удалось безопасно вернуться к заданию. Отсканируйте QR-код заново.') }
  const fragment = new URLSearchParams(url.hash.slice(1))
  if (url.origin !== 'https://crm.inkar.kz' || url.pathname !== '/staff' ||
      url.username || url.password || url.search ||
      fragment.getAll('grant').length !== 1 || fragment.get('grant') !== grant ||
      !/^[A-Za-z0-9_-]{32,256}$/.test(grant) ||
      fragment.getAll('claim').length !== 1 ||
      !/^[A-Za-z0-9._~-]{16,4096}$/.test(fragment.get('claim') || '') ||
      [...fragment.keys()].some((key) => key !== 'claim' && key !== 'grant')) {
    throw new Error('Не удалось безопасно вернуться к заданию. Отсканируйте QR-код заново.')
  }
  return url.href
}
