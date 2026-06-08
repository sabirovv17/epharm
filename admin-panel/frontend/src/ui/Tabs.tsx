// Tabs — admin §8.5 (underline-style)
import type { ReactNode } from 'react'

export interface TabItem<V extends string = string> {
  value: V
  label: string
  count?: number
}

interface TabsProps<V extends string> {
  items: TabItem<V>[]
  value: V
  onChange: (v: V) => void
  trailing?: ReactNode
}

export function Tabs<V extends string>({ items, value, onChange, trailing }: TabsProps<V>) {
  return (
    // Bug K fix: gap-4 (вместо gap-7) + min-w-0 на trailing, flex-none + whitespace-nowrap
    // на tabs — иначе длинные русские лейблы («Кросс-сейл») переносятся на 2 строки,
    // когда trailing-блок (search+filter) занимает много места.
    <div className="hairline flex items-end justify-between gap-4 border-b">
      <div className="flex flex-none items-center gap-6">
        {items.map((it) => (
          <button
            key={it.value}
            className={`tab whitespace-nowrap ${value === it.value ? 'active' : ''}`}
            onClick={() => onChange(it.value)}
          >
            <span>{it.label}</span>
            {typeof it.count === 'number' && (
              <span
                className={`inline-flex h-[18px] items-center rounded-full px-1.5 text-[11px] font-bold ${
                  value === it.value
                    ? 'bg-brand-green-100 text-brand-green-700'
                    : 'bg-ink-100 text-ink-500'
                }`}
              >
                {it.count}
              </span>
            )}
          </button>
        ))}
      </div>
      {trailing && <div className="min-w-0 pb-2">{trailing}</div>}
    </div>
  )
}
