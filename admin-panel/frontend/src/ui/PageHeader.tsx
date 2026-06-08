// PageHeader — admin §14 — единый шаблон шапки секции.
// 24/800 title + 14/500 subtitle (макс ширина 680px) + правый блок actions.
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
        <h1 className="text-[24px] font-extrabold leading-tight text-ink-900">{title}</h1>
        {subtitle && (
          <p className="mt-1 max-w-[680px] text-sm leading-snug text-ink-500">{subtitle}</p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
