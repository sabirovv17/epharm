// StatusChip — admin §9.2 (status mapping table)

import { useT } from '@/i18n'

export type Status =
  | 'active'
  | 'paused'
  | 'draft'
  | 'archived'
  | 'pending'
  | 'rejected'
  | 'approved'

interface StatusChipProps {
  status: Status
}

const MAP: Record<Status, { cls: string; dot: string }> = {
  active: { cls: 'chip-green', dot: '#16C97A' },
  paused: { cls: 'chip-amber', dot: '#F1B416' },
  draft: { cls: 'chip-ink', dot: '#9098A6' },
  archived: { cls: 'chip-ink', dot: '#9098A6' },
  pending: { cls: 'chip-blue', dot: '#2A2BE2' },
  rejected: { cls: 'chip-red', dot: '#E5484D' },
  approved: { cls: 'chip-green', dot: '#16C97A' },
}

export function StatusChip({ status }: StatusChipProps) {
  const t = useT()
  const m = MAP[status] || MAP.draft
  return (
    <span className={`chip ${m.cls}`}>
      <span className="chip-dot" style={{ background: m.dot }} /> {t(`status.${status}`)}
    </span>
  )
}
