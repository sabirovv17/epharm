// ProgressBar — admin §10 — inline progress, обычно h=6, pharmacy teal primary.
interface ProgressBarProps {
  value: number
  max?: number
  color?: string
  height?: number
}

export function ProgressBar({ value, max = 100, color = '#139F94', height = 6 }: ProgressBarProps) {
  const pct = Math.min(100, (value / max) * 100)
  return (
    <div className="w-full overflow-hidden rounded-full bg-ink-100" style={{ height }}>
      <div
        style={{
          width: `${pct}%`,
          height: '100%',
          background: color,
          borderRadius: 999,
          transition: 'width 240ms ease',
        }}
      />
    </div>
  )
}
