// PageHeader — admin §14 — единый шаблон шапки секции.
// 22/700 title + 13/500 subtitle (макс ширина 720px) + правый блок actions.
// Правило: не больше 2 кнопок в actions; остальное — через «Ещё ▾» dropdown.

import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  subtitle?: string
  actions?: ReactNode
}

export function PageHeader({ title, subtitle, actions }: PageHeaderProps) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-[22px] font-bold leading-7 tracking-[-0.01em] text-ink-900">
          {title}
        </h1>
        {subtitle && (
          <p className="mt-0.5 max-w-[720px] text-[13px] leading-5 text-ink-500">{subtitle}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
