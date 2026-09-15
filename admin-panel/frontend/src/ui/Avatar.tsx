// Avatar — initials с детерминированной палитрой по hash имени.

interface AvatarProps {
  name?: string
  size?: number
  src?: string
}

// Бренд-стопы переведены в коралл (по канонической таблице Claude orange).
// amber/purple/red — семантика, ink-900 — тёплый нейтраль. Стопы коралла разнесены для разнообразия аватаров.
const PALETTE = [
  '#B95336', // коралл 600 (PRIMARY)
  '#9C4029', // коралл 700
  '#F4B73A',
  '#8B5CF6',
  '#7C3222', // коралл 800 (глубокий)
  '#CC7A5C', // коралл 400 (светлый)
  '#E5484D',
  '#1C1B19', // тёплый ink-900
]

export function Avatar({ name, size = 32, src }: AvatarProps) {
  const initials = (name ?? '??')
    .split(/\s+/)
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase() ?? '')
    .join('')
  const hash = [...(name ?? '')].reduce((a, c) => a + c.charCodeAt(0), 0)
  const bg = PALETTE[hash % PALETTE.length]
  return (
    <span
      className="inline-flex flex-none items-center justify-center rounded-full font-bold text-white"
      style={{
        width: size,
        height: size,
        background: src ? 'transparent' : bg,
        fontSize: size * 0.4,
      }}
    >
      {src ? (
        <img src={src} alt={name} className="h-full w-full rounded-full object-cover" />
      ) : (
        initials
      )}
    </span>
  )
}
