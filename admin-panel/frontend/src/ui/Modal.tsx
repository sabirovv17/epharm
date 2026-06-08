// Modal — admin §11.1 — centered, max-width 460-620, esc closes, slide-in 220ms.
import { useEffect, type ReactNode } from 'react'
import { IconButton } from './Button'
import { IconClose } from './icons'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
  width?: number
}

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  width = 520,
}: ModalProps) {
  useEffect(() => {
    if (!open) return
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [open, onClose])

  if (!open) return null
  return (
    <div
      className="scrim fixed inset-0 z-[80] flex items-center justify-center p-6"
      onClick={onClose}
    >
      <div
        className="card slide-in w-full shadow-elevated"
        style={{ maxWidth: width }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="hairline flex items-start justify-between border-b px-5 pb-3 pt-5">
          <div>
            <div className="text-[16px] font-extrabold text-ink-900">{title}</div>
            {subtitle && <div className="mt-0.5 text-[13px] text-ink-500">{subtitle}</div>}
          </div>
          <IconButton onClick={onClose} tip="Esc">
            <IconClose size={18} />
          </IconButton>
        </div>
        <div className="scrollbar-thin max-h-[70vh] overflow-auto px-5 py-4">{children}</div>
        {footer && (
          <div className="hairline flex items-center justify-end gap-2 rounded-b-2xl border-t bg-paper-hover px-5 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
