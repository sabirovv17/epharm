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

// Точки совпадают по цвету со своим chip: chip-green/chip-blue теперь коралловые.
// paused/rejected — семантика (amber/red), draft/archived — нейтраль (тёплый ink-400).
const MAP: Record<Status, { cls: string; dot: string }> = {
  active: { cls: 'chip-green', dot: '#B95336' },
  paused: { cls: 'chip-amber', dot: '#F1B416' },
  draft: { cls: 'chip-ink', dot: '#96938D' },
  archived: { cls: 'chip-ink', dot: '#96938D' },
  pending: { cls: 'chip-blue', dot: '#B95336' },
  rejected: { cls: 'chip-red', dot: '#E5484D' },
  approved: { cls: 'chip-green', dot: '#B95336' },
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
