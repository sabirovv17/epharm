// SectionCard — admin §14 — белая карточка с заголовком + action + body
import type { ReactNode } from 'react'

interface SectionCardProps {
  title?: string
  subtitle?: string
  action?: ReactNode
  children: ReactNode
  padded?: boolean
  className?: string
}

export function SectionCard({
  title,
  subtitle,
  action,
  children,
  padded = true,
  className = '',
}: SectionCardProps) {
  return (
    <div className={`card ${className}`}>
      {(title || action) && (
        <div className="hairline flex items-center justify-between border-b px-5 py-4">
          <div>
            {title && <div className="text-[15px] font-extrabold text-ink-900">{title}</div>}
            {subtitle && <div className="mt-0.5 text-[13px] text-ink-500">{subtitle}</div>}
          </div>
          {action}
        </div>
      )}
      <div className={padded ? 'p-5' : ''}>{children}</div>
    </div>
  )
}
