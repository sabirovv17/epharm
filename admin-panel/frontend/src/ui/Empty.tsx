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
    <div className="flex flex-col items-center gap-3 px-6 py-12 text-center">
      {icon && (
        <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-ink-100 text-ink-400">
          {icon}
        </span>
      )}
      <div className="text-[15px] font-extrabold text-ink-900">{title}</div>
      {body && <div className="max-w-[340px] text-[13px] text-ink-500">{body}</div>}
      {action}
    </div>
  )
}
