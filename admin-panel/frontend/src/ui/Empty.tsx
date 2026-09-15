// Empty — admin §13 — пустое состояние секции
import type { ReactNode } from 'react'

interface EmptyProps {
  title: string
  body?: string
  action?: ReactNode
  icon?: ReactNode
}

export function Empty({ title, body, action, icon }: EmptyProps) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-10 text-center">
      {icon && (
        <span className="flex h-12 w-12 items-center justify-center rounded-lg border border-ink-200 bg-ink-50 text-ink-500">
          {icon}
        </span>
      )}
      <div className="text-sm font-bold text-ink-900">{title}</div>
      {body && <div className="max-w-[340px] text-[13px] text-ink-500">{body}</div>}
      {action}
    </div>
  )
}
