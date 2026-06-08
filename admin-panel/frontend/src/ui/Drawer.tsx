// Drawer — admin §11.2 — right-aligned panel, 480-560 wide, slide-in от правого края.
import type { ReactNode } from 'react'
import { IconButton } from './Button'
import { IconClose } from './icons'

interface DrawerProps {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
  width?: number
}

export function Drawer({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  width = 520,
}: DrawerProps) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-[70]" onClick={onClose}>
      <div className="scrim absolute inset-0" />
      <div
        className="slide-in absolute bottom-0 right-0 top-0 flex flex-col bg-white shadow-elevated"
        style={{ width }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="hairline flex items-start justify-between border-b px-5 pb-4 pt-5">
          <div className="min-w-0 flex-1">
            <div className="text-[16px] font-extrabold text-ink-900">{title}</div>
            {subtitle && <div className="mt-0.5 truncate text-[13px] text-ink-500">{subtitle}</div>}
          </div>
          <IconButton onClick={onClose} tip="Закрыть">
            <IconClose size={18} />
          </IconButton>
        </div>
        <div className="scrollbar-thin flex-1 overflow-auto px-5 py-4">{children}</div>
        {footer && (
          <div className="hairline flex items-center justify-end gap-2 border-t bg-paper-hover px-5 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
