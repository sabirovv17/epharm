// Admin icon set — flat 24-grid SVG glyphs, currentColor.
// Source-of-truth: admin-panel/references/icons.jsx.
// Один компонент = один SVG, inline для tree-shaking. NOT replaced with lucide-react
// because the icon set is hand-tuned for design-tokens-admin.md §8 stroke weight.

import type { SVGProps } from 'react'

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'children' | 'stroke'> {
  size?: number
  stroke?: number
}

function Ic({
  children,
  size = 20,
  stroke = 1.7,
  ...rest
}: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      {...rest}
    >
      {children}
    </svg>
  )
}

// ─── Sidebar ──────────────────────────────────────────────────────────────
export const IconDashboard = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="3" width="8" height="10" rx="2" />
    <rect x="13" y="3" width="8" height="6" rx="2" />
    <rect x="13" y="11" width="8" height="10" rx="2" />
    <rect x="3" y="15" width="8" height="6" rx="2" />
  </Ic>
)

export const IconPromo = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 11l16-7v16L3 13z" />
    <path d="M3 11v2" />
    <circle cx="6" cy="12" r="2" />
  </Ic>
)

export const IconPharmacy = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 21V10l9-6 9 6v11" />
    <path d="M12 14v4M10 16h4" />
    <path d="M3 21h18" />
  </Ic>
)

export const IconPharmacist = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21c0-4 4-7 8-7s8 3 8 7" />
  </Ic>
)

export const IconLift = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 17l5-6 4 4 5-7 4 5" />
    <path d="M16 8h4v4" />
  </Ic>
)

export const IconFinance = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="6" width="18" height="13" rx="2" />
    <path d="M3 10h18" />
    <path d="M8 15h3" />
  </Ic>
)

export const IconReconcile = (p: IconProps) => (
  <Ic {...p}>
    <path d="M6 3h9l4 4v14H6z" />
    <path d="M9 11l2 2 4-4" />
    <path d="M9 16h6" />
  </Ic>
)

export const IconAIExam = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 5v2M12 17v2M5 12h2M17 12h2M7 7l1.5 1.5M15.5 15.5L17 17M7 17l1.5-1.5M15.5 8.5L17 7" />
  </Ic>
)

export const IconRules = (p: IconProps) => (
  <Ic {...p}>
    <path d="M4 6h6M4 12h10M4 18h6" />
    <path d="M14 6l3 3 4-5" />
    <circle cx="17" cy="14" r="2" />
    <path d="M17 16v2" />
  </Ic>
)

export const IconScreens = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="4" width="18" height="12" rx="2" />
    <path d="M8 20h8M12 16v4" />
  </Ic>
)

export const IconLMS = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 7l9-4 9 4-9 4z" />
    <path d="M7 10v5c0 1.5 2.5 3 5 3s5-1.5 5-3v-5" />
  </Ic>
)

export const IconSettings = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2l-.4-2.5h-4l-.4 2.5a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12a7 7 0 0 0 .1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2l.4 2.5h4l.4-2.5a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.06-.4.1-.8.1-1.2z" />
  </Ic>
)

// ─── Actions / Generic ────────────────────────────────────────────────────
export const IconSearch = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="11" cy="11" r="7" />
    <path d="M20 20l-4-4" />
  </Ic>
)

export const IconBell = (p: IconProps) => (
  <Ic {...p}>
    <path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2H4.5z" />
    <path d="M10 21a2 2 0 0 0 4 0" />
  </Ic>
)

export const IconChevDown = (p: IconProps) => (
  <Ic {...p}>
    <path d="M6 9l6 6 6-6" />
  </Ic>
)

export const IconChevRight = (p: IconProps) => (
  <Ic {...p}>
    <path d="M9 6l6 6-6 6" />
  </Ic>
)

export const IconChevLeft = (p: IconProps) => (
  <Ic {...p}>
    <path d="M15 6l-6 6 6 6" />
  </Ic>
)

export const IconPlus = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 5v14M5 12h14" />
  </Ic>
)

export const IconClose = (p: IconProps) => (
  <Ic {...p}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Ic>
)

export const IconCheck = (p: IconProps) => (
  <Ic {...p}>
    <path d="M5 12l5 5 9-11" />
  </Ic>
)

export const IconDots = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="5" cy="12" r="1.5" />
    <circle cx="12" cy="12" r="1.5" />
    <circle cx="19" cy="12" r="1.5" />
  </Ic>
)

export const IconFilter = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 5h18l-7 9v6l-4-2v-4z" />
  </Ic>
)

export const IconSort = (p: IconProps) => (
  <Ic {...p}>
    <path d="M8 5v14M4 9l4-4 4 4" />
    <path d="M16 19V5M20 15l-4 4-4-4" />
  </Ic>
)

export const IconArrowRight = (p: IconProps) => (
  <Ic {...p}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Ic>
)

export const IconArrowUp = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 19V5M5 12l7-7 7 7" />
  </Ic>
)

export const IconArrowDown = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 5v14M5 12l7 7 7-7" />
  </Ic>
)

export const IconEdit = (p: IconProps) => (
  <Ic {...p}>
    <path d="M14 4l6 6L8 22H2v-6z" />
    <path d="M14 4l3-3 6 6-3 3" />
  </Ic>
)

export const IconTrash = (p: IconProps) => (
  <Ic {...p}>
    <path d="M4 7h16M10 7V4h4v3M6 7l1 13h10l1-13" />
  </Ic>
)

export const IconDuplicate = (p: IconProps) => (
  <Ic {...p}>
    <rect x="8" y="8" width="13" height="13" rx="2" />
    <path d="M3 16V5a2 2 0 0 1 2-2h11" />
  </Ic>
)

export const IconArchive = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="4" width="18" height="4" rx="1" />
    <path d="M5 8v12h14V8M10 12h4" />
  </Ic>
)

export const IconPlay = (p: IconProps) => (
  <Ic {...p}>
    <path d="M7 5l12 7-12 7z" />
  </Ic>
)

export const IconPause = (p: IconProps) => (
  <Ic {...p}>
    <rect x="6" y="5" width="4" height="14" rx="1" />
    <rect x="14" y="5" width="4" height="14" rx="1" />
  </Ic>
)

export const IconDownload = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 4v12M6 12l6 6 6-6" />
    <path d="M4 20h16" />
  </Ic>
)

export const IconUpload = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 20V8M6 12l6-6 6 6" />
    <path d="M4 4h16" />
  </Ic>
)

export const IconExternal = (p: IconProps) => (
  <Ic {...p}>
    <path d="M9 5h-5v15h15v-5" />
    <path d="M14 4h6v6M20 4L10 14" />
  </Ic>
)

export const IconCalendar = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="5" width="18" height="16" rx="2" />
    <path d="M3 10h18M8 3v4M16 3v4" />
  </Ic>
)

export const IconClock = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </Ic>
)

export const IconDrag = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="9" cy="6" r="1.5" />
    <circle cx="15" cy="6" r="1.5" />
    <circle cx="9" cy="12" r="1.5" />
    <circle cx="15" cy="12" r="1.5" />
    <circle cx="9" cy="18" r="1.5" />
    <circle cx="15" cy="18" r="1.5" />
  </Ic>
)

export const IconRefresh = (p: IconProps) => (
  <Ic {...p}>
    <path d="M20 11a8 8 0 1 0-2.3 5.7L20 19" />
    <path d="M20 14v-3h-3" />
  </Ic>
)

export const IconEye = (p: IconProps) => (
  <Ic {...p}>
    <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" />
    <circle cx="12" cy="12" r="3" />
  </Ic>
)

export const IconShield = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l8 3v6c0 5-3.5 9-8 9s-8-4-8-9V6z" />
    <path d="M9 12l2 2 4-4" />
  </Ic>
)

export const IconAlert = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l10 18H2z" />
    <path d="M12 10v5M12 18v.5" />
  </Ic>
)

export const IconInfo = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v6M12 7v.5" />
  </Ic>
)

export const IconSpark = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l1.6 5L19 9l-4 3.6L16 18l-4-2.5L8 18l1-5.4L5 9l5.4-1z" />
  </Ic>
)

export const IconUsers = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="9" cy="9" r="3" />
    <circle cx="17" cy="10" r="2" />
    <path d="M3 19c0-3 2.5-5 6-5s6 2 6 5" />
    <path d="M21 19c0-2-1.5-3.5-4-4" />
  </Ic>
)

export const IconBox = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 7l9-4 9 4v10l-9 4-9-4z" />
    <path d="M3 7l9 4 9-4M12 11v10" />
  </Ic>
)

export const IconPill = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="8" width="18" height="8" rx="4" transform="rotate(-30 12 12)" />
    <path d="M9 6l6 12" transform="rotate(-30 12 12)" />
  </Ic>
)

export const IconSwap = (p: IconProps) => (
  <Ic {...p}>
    <path d="M7 4L3 8l4 4" />
    <path d="M3 8h14" />
    <path d="M17 20l4-4-4-4" />
    <path d="M21 16H7" />
  </Ic>
)

export const IconStack = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l9 4-9 4-9-4z" />
    <path d="M3 12l9 4 9-4M3 17l9 4 9-4" />
  </Ic>
)

export const IconLink = (p: IconProps) => (
  <Ic {...p}>
    <path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7L11.5 7" />
    <path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7L12.5 17" />
  </Ic>
)

export const IconHistory = (p: IconProps) => (
  <Ic {...p}>
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
    <path d="M3 3v5h5" />
    <path d="M12 7v5l3 2" />
  </Ic>
)

export const IconLayers = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l9 5-9 5-9-5z" />
    <path d="M3 13l9 5 9-5M3 18l9 5 9-5" />
  </Ic>
)

export const IconCommand = (p: IconProps) => (
  <Ic {...p}>
    <path d="M6 9V6a2 2 0 1 1 2 2H6zm12 0V6a2 2 0 1 0-2 2h2zM6 15v3a2 2 0 1 0 2-2H6zm12 0v3a2 2 0 1 1-2-2h2z" />
  </Ic>
)

export const IconLogout = (p: IconProps) => (
  <Ic {...p}>
    <path d="M15 4h4v16h-4" />
    <path d="M10 8l-4 4 4 4" />
    <path d="M6 12h11" />
  </Ic>
)

export const IconStar = (p: IconProps) => (
  <Ic {...p}>
    <path d="M12 3l2.6 6 6.4.6-5 4.5 1.6 6.4L12 17l-5.6 3.5L8 14.1l-5-4.5 6.4-.6z" />
  </Ic>
)

export const IconPlayCircle = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M10 8l6 4-6 4z" fill="currentColor" />
  </Ic>
)

export const IconLock = (p: IconProps) => (
  <Ic {...p}>
    <rect x="5" y="11" width="14" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </Ic>
)

export const IconGlobe = (p: IconProps) => (
  <Ic {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
  </Ic>
)

export const IconReceipt = (p: IconProps) => (
  <Ic {...p}>
    <path d="M5 3h14v18l-3-2-2 2-2-2-2 2-2-2-3 2z" />
    <path d="M8 8h8M8 12h8M8 16h5" />
  </Ic>
)

// Переключатель режима просмотра кампаний (сетка/список).
export const IconGrid = (p: IconProps) => (
  <Ic {...p}>
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </Ic>
)

export const IconList = (p: IconProps) => (
  <Ic {...p}>
    <path d="M8 6h13M8 12h13M8 18h13" />
    <path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01" />
  </Ic>
)
