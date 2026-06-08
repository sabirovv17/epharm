// Sparkline — admin §10.3 — SVG-line + optional fill, inline в таблицах / KPI
interface SparklineProps {
  values: number[]
  color?: string
  width?: number
  height?: number
  fill?: boolean
}

export function Sparkline({
  values,
  color = '#16C97A',
  width = 120,
  height = 36,
  fill = true,
}: SparklineProps) {
  if (!values || values.length === 0) return null

  const max = Math.max(...values)
  const min = Math.min(...values)
  const rng = max - min || 1
  const stepX = width / (values.length - 1 || 1)
  const pts = values.map(
    (v, i) => [i * stepX, height - ((v - min) / rng) * (height - 4) - 2] as const,
  )
  const d = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
  const area = `${d} L ${width},${height} L 0,${height} Z`

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {fill && <path d={area} fill={color} opacity="0.12" />}
      <path
        d={d}
        stroke={color}
        strokeWidth="1.8"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
