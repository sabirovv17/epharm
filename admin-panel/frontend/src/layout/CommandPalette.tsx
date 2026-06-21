// CommandPalette — admin §11.4
// ⌘K / Ctrl+K (или клик на topbar search) → top-aligned 12vh, max-w 560.
// Фильтрует 12 разделов админки. (Поиск по товарам/аптекам был завязан на
// демо-fixtures, которые обнулены, — мёртвую ветку убрали; вернуть можно через
// реальные хуки useProducts/usePharmacies с дебаунсом.)

import { useState, type ReactNode } from 'react'
import { SECTIONS, type SectionId } from '@/mocks/fixtures'
import { IconSearch } from '@/ui/icons'
import { useT } from '@/i18n'

interface CommandPaletteProps {
  onClose: () => void
  onNav: (id: SectionId) => void
}

interface Item {
  id: SectionId
  label: string
  sub: string
  icon: ReactNode
}

// Монтируется ТОЛЬКО когда открыта (см. AppShell), поэтому состояние поиска
// сбрасывается само при каждом открытии — без setState-в-effect (cascading renders).
export function CommandPalette({ onClose, onNav }: CommandPaletteProps) {
  const t = useT()
  const [q, setQ] = useState('')

  const items: Item[] = SECTIONS.map((s) => {
    const Icon = s.Icon
    return {
      id: s.id,
      label: t(`nav.${s.id}`),
      sub: t(`group.${s.group}`),
      icon: <Icon size={16} />,
    }
  })

  const filtered = q
    ? items.filter((i) => `${i.label} ${i.sub}`.toLowerCase().includes(q.toLowerCase()))
    : items.slice(0, 12)

  const onPick = (it: Item) => {
    onNav(it.id)
    onClose()
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
                key={it.id}
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
                  {t('palette.kind.section')}
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
