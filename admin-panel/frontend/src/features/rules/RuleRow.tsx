// RuleRow — одна строка в RulesList. Триггер → стрелка → Рекомендация + конв. + бонус + статус.
// Hover показывает «⋯»-кнопку action-меню (Дублировать / Архивировать).

import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { formatKzt, type Rule } from '@/mocks/fixtures'
import { StatusChip } from '@/ui'
import { IconArchive, IconArrowRight, IconDots, IconDrag, IconDuplicate } from '@/ui/icons'
import { useProductLookup } from '@/lib/queries/catalog'
import { useT } from '@/i18n'
import { ProductBlock } from './ProductBlock'

interface RuleRowProps {
  rule: Rule
  selected: boolean
  onSelect: () => void
  onArchive?: (rule: Rule) => void
  onDuplicate?: (rule: Rule) => void
  onDragStart?: () => void
  onDragOver?: (e: MouseEvent) => void
  onDragEnd?: () => void
  dragging?: boolean
}

export function RuleRow({
  rule,
  selected,
  onSelect,
  onArchive,
  onDuplicate,
  onDragStart,
  onDragOver,
  onDragEnd,
  dragging,
}: RuleRowProps) {
  const t = useT()
  const productById = useProductLookup()
  const isArchive = rule.status === 'archived'

  const triggerProduct =
    rule.trigger.kind === 'product'
      ? productById(rule.trigger.value as string)
      : rule.trigger.kind === 'product_any'
        ? productById((rule.trigger.value as string[])[0])
        : null

  const recProduct = productById(rule.recommend)

  return (
    <li
      draggable={!isArchive}
      onDragStart={onDragStart}
      onDragOver={(e) => {
        e.preventDefault()
        onDragOver?.(e)
      }}
      onDragEnd={onDragEnd}
      onClick={onSelect}
      className={`group relative flex cursor-pointer items-center gap-3 py-3 pl-3 pr-4 ${
        selected ? 'bg-brand-green-50/60' : 'hover:bg-paper-hover'
      } ${dragging ? 'dragging' : ''}`}
      style={selected ? { boxShadow: 'inset 3px 0 0 #16C97A' } : undefined}
      data-testid={`rule-row-${rule.id}`}
    >
      {!isArchive && (
        <span
          className="drag-handle flex items-center text-ink-200 transition group-hover:text-ink-400"
          onClick={(e) => e.stopPropagation()}
          aria-label="Drag handle"
        >
          <IconDrag size={14} />
        </span>
      )}

      <ProductBlock product={triggerProduct ?? undefined} fallback={rule.trigger} />
      <span className="flex-none self-center text-ink-300">
        <IconArrowRight size={14} />
      </span>
      <ProductBlock product={recProduct ?? undefined} accent />

      <div className="flex flex-none items-center gap-5 pl-2">
        <div className="w-14 text-right">
          <div className="num text-[14px] font-extrabold leading-none text-ink-900">
            {rule.convRate.toFixed(1)}%
          </div>
          <div className="mt-1 text-[10px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('rules.conv')}
          </div>
        </div>
        <div className="w-20 text-right">
          <div className="num text-[13px] font-extrabold leading-none text-brand-green-700">
            +{formatKzt(rule.bonus)}
          </div>
          <div className="mt-1 text-[10px] font-bold uppercase tracking-[0.04em] text-ink-400">
            {t('rules.bonus')}
          </div>
        </div>
        <StatusChip status={rule.status} />
        {(onArchive || onDuplicate) && (
          <RowMenu
            rule={rule}
            onArchive={onArchive}
            onDuplicate={onDuplicate}
            disabledArchive={isArchive}
          />
        )}
      </div>
    </li>
  )
}

// ─────────────────────────────────────────────────────────────────────────
// RowMenu — «⋯» popover на строке. Click outside / Esc закрывают.
// ─────────────────────────────────────────────────────────────────────────

interface RowMenuProps {
  rule: Rule
  onArchive?: (rule: Rule) => void
  onDuplicate?: (rule: Rule) => void
  disabledArchive?: boolean
}

function RowMenu({ rule, onArchive, onDuplicate, disabledArchive }: RowMenuProps) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDoc = (e: globalThis.MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        aria-label={t('rules.rowActions')}
        data-testid={`row-menu-trigger-${rule.id}`}
        onClick={(e) => {
          e.stopPropagation()
          setOpen((o) => !o)
        }}
        className="flex h-7 w-7 items-center justify-center rounded-md text-ink-400 transition hover:bg-paper-hover hover:text-ink-900"
      >
        <IconDots size={16} />
      </button>
      {open && (
        <div
          role="menu"
          className="card slide-in absolute right-0 top-9 z-30 w-[220px] py-1 shadow-elevated"
          onClick={(e) => e.stopPropagation()}
        >
          {onDuplicate && (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false)
                onDuplicate(rule)
              }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] font-semibold text-ink-700 hover:bg-paper-hover"
            >
              <IconDuplicate size={14} />
              {t('rules.duplicate')}
            </button>
          )}
          {onArchive && (
            <button
              type="button"
              role="menuitem"
              disabled={disabledArchive}
              onClick={() => {
                setOpen(false)
                onArchive(rule)
              }}
              className={`flex w-full items-center gap-2 px-3 py-2 text-left text-[13px] font-semibold ${
                disabledArchive
                  ? 'cursor-not-allowed text-ink-300'
                  : 'text-accent-danger hover:bg-paper-hover'
              }`}
            >
              <IconArchive size={14} />
              {disabledArchive ? t('rules.alreadyArchived') : t('rules.toArchive')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
