// Avatar — initials с детерминированной палитрой по hash имени.

interface AvatarProps {
  name?: string
  size?: number
  src?: string
}

const PALETTE = [
  '#16C97A',
  '#2A2BE2',
  '#F4B73A',
  '#8B5CF6',
  '#0F8F55',
  '#3F47F0',
  '#E5484D',
  '#0F1424',
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
