// CommandPalette — admin §11.4
// ⌘K / Ctrl+K (или клик на topbar search) → top-aligned 12vh, max-w 560.
// Фильтрует: все 12 разделов + top-8 продуктов + top-6 аптек.

import { useEffect, useState, type ReactNode } from 'react'
import {
  SECTIONS,
  PRODUCT_LIBRARY,
  PHARMACY_LIST,
  formatKzt,
  type SectionId,
} from '@/mocks/fixtures'
import { IconBox, IconPharmacy, IconSearch } from '@/ui/icons'
import { useT } from '@/i18n'

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  onNav: (id: SectionId) => void
}

type ItemKind = 'section' | 'product' | 'pharmacy'

interface Item {
  kind: ItemKind
  id: string
  label: string
  sub: string
  icon: ReactNode
}

export function CommandPalette({ open, onClose, onNav }: CommandPaletteProps) {
  const t = useT()
  const [q, setQ] = useState('')

  useEffect(() => {
    if (open) setQ('')
  }, [open])

  if (!open) return null

  const items: Item[] = []
  SECTIONS.forEach((s) => {
    const Icon = s.Icon
    items.push({
      kind: 'section',
      id: s.id,
      label: t(`nav.${s.id}`),
      sub: t(`group.${s.group}`),
      icon: <Icon size={16} />,
    })
  })
  PRODUCT_LIBRARY.slice(0, 8).forEach((p) => {
    items.push({
      kind: 'product',
      id: p.id,
      label: p.name,
      sub: `${p.brand} · ${formatKzt(p.price)}`,
      icon: <IconBox size={16} />,
    })
  })
  PHARMACY_LIST.slice(0, 6).forEach((p) => {
    items.push({
      kind: 'pharmacy',
      id: p.id,
      label: p.name,
      sub: `${p.city} · ${p.addr}`,
      icon: <IconPharmacy size={16} />,
    })
  })

  const filtered = q
    ? items.filter((i) => `${i.label} ${i.sub}`.toLowerCase().includes(q.toLowerCase()))
    : items.slice(0, 12)

  const onPick = (it: Item) => {
    if (it.kind === 'section') onNav(it.id as SectionId)
    onClose()
  }

  const kindLabel: Record<ItemKind, string> = {
    section: t('palette.kind.section'),
    product: t('palette.kind.product'),
    pharmacy: t('palette.kind.pharmacy'),
  }

  return (
    <div
      className="scrim fixed inset-0 z-[85] flex items-start justify-center px-4 pt-[12vh]"
      onClick={onClose}
    >
      <div
        className="card slide-in w-full max-w-[560px] overflow-hidden shadow-elevated"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="hairline flex h-12 items-center gap-2 border-b px-3">
          <IconSearch size={16} className="text-ink-400" />
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t('palette.search')}
            className="flex-1 bg-transparent text-sm font-semibold text-ink-900 outline-none"
          />
          <span className="kbd">esc</span>
        </div>
        <div className="scrollbar-thin max-h-[50vh] overflow-auto py-2">
          {filtered.length === 0 ? (
            <div className="py-8 text-center text-[13px] text-ink-400">{t('palette.empty')}</div>
          ) : (
            filtered.map((it) => (
              <button
                key={`${it.kind}:${it.id}`}
                onClick={() => onPick(it)}
                className="flex w-full items-center gap-3 px-3 py-2 text-left hover:bg-paper-hover"
              >
                <span className="flex h-7 w-7 flex-none items-center justify-center rounded-md bg-ink-100 text-ink-500">
                  {it.icon}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-bold text-ink-900">{it.label}</div>
                  <div className="truncate text-[12px] text-ink-500">{it.sub}</div>
                </div>
                <span className="text-[11px] font-bold uppercase text-ink-400">
                  {kindLabel[it.kind]}
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
