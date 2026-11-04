export function maskedIin(value: string | null | undefined): string {
  const normalized = value?.trim()
  if (!normalized) return '—'
  return `••••••••${normalized.slice(-4)}`
}

export function maskedPhone(value: string | null | undefined): string {
  const normalized = value?.trim()
  if (!normalized) return '—'
  const digits = normalized.replace(/\D/g, '')
  return digits.length >= 4 ? `••• ••• ${digits.slice(-4)}` : '••••'
}
