// Modal — admin §11.1 — centered, max-width 460-620, esc closes, slide-in 220ms.
import { useEffect, useId, useRef, type ReactNode } from 'react'
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

const openDialogs: HTMLElement[] = []

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  width = 520,
}: ModalProps) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  useEffect(() => {
    closeRef.current = onClose
  }, [onClose])
  useEffect(() => {
    const dialog = dialogRef.current
    if (!open || !dialog) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    // Child effects mount before parents. Keep nested dialogs above their parent.
    const childIndex = openDialogs.findIndex((entry) => dialog.contains(entry))
    if (childIndex < 0) openDialogs.push(dialog)
    else openDialogs.splice(childIndex, 0, dialog)
    if (openDialogs.at(-1) === dialog) dialog.focus({ preventScroll: true })
    const k = (e: KeyboardEvent) => {
      if (openDialogs.at(-1) !== dialog) return
      if (e.key === 'Escape') {
        e.preventDefault()
        closeRef.current()
      }
      if (e.key !== 'Tab') return
      const targets = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
        ),
      )
        .filter(
          (element) =>
            !element.closest('[hidden], [inert]') &&
            getComputedStyle(element).display !== 'none' &&
            getComputedStyle(element).visibility !== 'hidden',
        )
        .sort((left, right) =>
          left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1,
        )
      const first = targets[0]
      const last = targets.at(-1)
      if (!first || !last) {
        e.preventDefault()
        dialog.focus()
        return
      }
      if (e.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
        e.preventDefault()
        last.focus()
      } else if (
        !e.shiftKey &&
        (document.activeElement === last || document.activeElement === dialog)
      ) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', k, true)
    return () => {
      document.removeEventListener('keydown', k, true)
      const wasTop = openDialogs.at(-1) === dialog
      const index = openDialogs.indexOf(dialog)
      if (index >= 0) openDialogs.splice(index, 1)
      if (wasTop) {
        const remaining = openDialogs.at(-1)
        const restore =
          previous?.isConnected && (!remaining || remaining.contains(previous))
            ? previous
            : remaining
        restore?.focus({ preventScroll: true })
      }
    }
  }, [open])

  if (!open) return null
  return (
    <div
      className="scrim fixed inset-0 z-[80] flex items-center justify-center p-3 sm:p-6"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        className="card slide-in flex max-h-[calc(100dvh-24px)] w-full min-w-0 flex-col overflow-hidden rounded-2xl shadow-elevated outline-none sm:max-h-[calc(100dvh-48px)]"
        style={{ maxWidth: width }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <div className="hairline flex shrink-0 items-start justify-between gap-3 border-b px-4 pb-4 pt-5 sm:px-6">
          <div className="min-w-0 break-words">
            <div
              id={titleId}
              className="text-[18px] font-extrabold tracking-[-0.015em] text-ink-900"
            >
              {title}
            </div>
            {subtitle && <div className="mt-0.5 text-[13px] text-ink-500">{subtitle}</div>}
          </div>
          <IconButton onClick={onClose} tip="Esc" aria-label="Закрыть окно">
            <IconClose size={18} />
          </IconButton>
        </div>
        <div className="scrollbar-thin min-h-0 overflow-auto px-4 py-4 sm:px-6">{children}</div>
        {footer && (
          <div className="hairline flex shrink-0 flex-wrap items-center justify-end gap-2 border-t bg-paper-input px-4 py-3 sm:px-6">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
