// Receipt stamp logo — общий брендовый glyph для Sidebar (свёрнутый + развёрнутый).
// Source-of-truth — references/layout.jsx + admin-panel/design-tokens-admin.md §7.

interface LogoProps {
  size?: number
}

export function Logo({ size = 22 }: LogoProps) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size}>
      <path
        d="M16 12a3 3 0 0 1 3-3h26a3 3 0 0 1 3 3v40l-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3-4-3-4 3z"
        fill="#FFFFFF"
        stroke="#16C97A"
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <rect x="22" y="32" width="20" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55" />
      <rect x="22" y="38" width="14" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55" />
      <rect x="22" y="44" width="17" height="2.5" rx="1.2" fill="#16C97A" opacity="0.55" />
      <g transform="translate(32 14)">
        <circle r="11" fill="#2A2BE2" />
        <rect x="-1.5" y="-7" width="3" height="14" rx="0.8" fill="#FFFFFF" />
        <rect x="-7" y="-1.5" width="14" height="3" rx="0.8" fill="#FFFFFF" />
      </g>
    </svg>
  )
}
