// Metric — admin §10.1 — KPI tile (4-up grid на дашборде)
import type { ReactNode } from 'react'
import { IconArrowUp, IconArrowDown } from './icons'

type Accent = 'green' | 'blue' | 'amber' | 'purple' | 'ink'

interface MetricProps {
  label: string
  value: ReactNode
  sub?: ReactNode
  delta?: number
  icon?: ReactNode
  accent?: Accent
  meta?: ReactNode
}

const TINT: Record<Accent, { bg: string; fg: string }> = {
  // Бренд-плитки — коралл (бывшие зелёная/синяя). amber/purple — семантика, без изменений.
  green: { bg: '#F8E7DD', fg: '#BE5A38' },
  blue: { bg: '#F8E7DD', fg: '#BE5A38' },
  amber: { bg: '#FEF3C7', fg: '#B45309' },
  purple: { bg: '#F3E8FF', fg: '#7C3AED' },
  ink: { bg: '#EFEAE2', fg: '#221C16' },
}

export function Metric({ label, value, sub, delta, icon, accent = 'green', meta }: MetricProps) {
  const tint = TINT[accent]
  return (
    <div className="card flex flex-col gap-3 p-5">
      <div className="flex items-start justify-between">
        <span className="text-[13px] font-semibold text-ink-500">{label}</span>
        {icon && (
          <span
            className="flex h-9 w-9 items-center justify-center rounded-xl"
            style={{ background: tint.bg, color: tint.fg }}
          >
            {icon}
          </span>
        )}
      </div>
      <div className="flex items-baseline gap-2">
        <span className="num text-[28px] font-extrabold leading-none tracking-tight text-ink-900">
          {value}
        </span>
        {sub && <span className="text-sm font-semibold text-ink-500">{sub}</span>}
      </div>
      <div className="flex items-center justify-between">
        {meta && <span className="text-[12px] font-semibold text-ink-500">{meta}</span>}
        {delta != null && (
          <span className={`chip ml-auto ${delta >= 0 ? 'chip-green' : 'chip-red'}`}>
            {delta >= 0 ? <IconArrowUp size={11} /> : <IconArrowDown size={11} />}
            {Math.abs(delta).toFixed(1)}%
          </span>
        )}
      </div>
    </div>
  )
}
