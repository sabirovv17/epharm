// PeriodPicker — выбор отчётного периода (месяц + год) в Topbar.
// Заменяет статичную надпись «Май 2026». По умолчанию — текущий месяц
// (реальная системная дата). Выбор хранится в Zustand-store (period) —
// единая точка для будущей фильтрации данных по месяцу.

import { useEffect, useRef, useState } from 'react'
import { IconCalendar, IconChevDown, IconChevLeft, IconChevRight } from '@/ui/icons'
import { currentPeriod, useUiStore, type Lang, type PeriodValue } from '@/app/store'
import { useT } from '@/i18n'

const MONTHS_BY_LANG: Record<Lang, string[]> = {
  ru: [
    'Январь',
    'Февраль',
    'Март',
    'Апрель',
    'Май',
    'Июнь',
    'Июль',
    'Август',
    'Сентябрь',
    'Октябрь',
    'Ноябрь',
    'Декабрь',
  ],
  kk: [
    'Қаңтар',
    'Ақпан',
    'Наурыз',
    'Сәуір',
    'Мамыр',
    'Маусым',
    'Шілде',
    'Тамыз',
    'Қыркүйек',
    'Қазан',
    'Қараша',
    'Желтоқсан',
  ],
}

const MONTHS_SHORT_BY_LANG: Record<Lang, string[]> = {
  ru: ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'],
  kk: ['Қаң', 'Ақп', 'Нау', 'Сәу', 'Мам', 'Мау', 'Шіл', 'Там', 'Қыр', 'Қаз', 'Қар', 'Жел'],
}

// Совместимость для тестов/легаси-импортов — русские названия по умолчанию.
export const MONTHS = MONTHS_BY_LANG.ru
export const MONTHS_SHORT = MONTHS_SHORT_BY_LANG.ru

/** «Май 2026» — для отображения выбранного периода (по языку). */
export function formatPeriod(p: PeriodValue, lang: Lang = 'ru'): string {
  return `${MONTHS_BY_LANG[lang][p.month]} ${p.year}`
}

export function PeriodPicker() {
  const period = useUiStore((s) => s.period)
  const setPeriod = useUiStore((s) => s.setPeriod)
  const lang = useUiStore((s) => s.language)
  const t = useT()
  const monthsShort = MONTHS_SHORT_BY_LANG[lang]

  const [open, setOpen] = useState(false)
  const [viewYear, setViewYear] = useState(period.year)
  const ref = useRef<HTMLDivElement>(null)

  const today = currentPeriod()

  // При открытии — показываем год выбранного периода.
  useEffect(() => {
    if (open) setViewYear(period.year)
  }, [open, period.year])

  // Click-outside + Esc закрывают дропдаун (паттерн как у RolePill).
  useEffect(() => {
    if (!open) return
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('mousedown', onClick)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onClick)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  const pick = (month: number) => {
    setPeriod({ year: viewYear, month })
    setOpen(false)
  }

  const goCurrent = () => {
    setPeriod(currentPeriod())
    setOpen(false)
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 items-center gap-2 rounded-lg px-3 text-[13px] font-bold text-ink-700 hover:bg-ink-100"
        aria-haspopup="dialog"
        aria-expanded={open}
        data-testid="period-picker"
      >
        <IconCalendar size={15} />
        <span>{formatPeriod(period, lang)}</span>
        <IconChevDown size={13} />
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Выбор периода"
          data-testid="period-dropdown"
          className="card slide-in absolute right-0 top-full z-50 mt-1.5 w-[280px] overflow-hidden p-3 shadow-elevated"
        >
          {/* Навигация по годам */}
          <div className="mb-2 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setViewYear((y) => y - 1)}
              aria-label="Предыдущий год"
              data-testid="period-year-prev"
              className="flex h-8 w-8 items-center justify-center rounded-lg text-ink-500 hover:bg-paper-hover hover:text-ink-900"
            >
              <IconChevLeft size={16} />
            </button>
            <span className="num text-[14px] font-extrabold text-ink-900" data-testid="period-year">
              {viewYear}
            </span>
            <button
              type="button"
              onClick={() => setViewYear((y) => y + 1)}
              aria-label="Следующий год"
              data-testid="period-year-next"
              className="flex h-8 w-8 items-center justify-center rounded-lg text-ink-500 hover:bg-paper-hover hover:text-ink-900"
            >
              <IconChevRight size={16} />
            </button>
          </div>

          {/* Сетка месяцев */}
          <div className="grid grid-cols-3 gap-1.5">
            {monthsShort.map((m, i) => {
              const isSelected = viewYear === period.year && i === period.month
              const isToday = viewYear === today.year && i === today.month
              return (
                <button
                  key={m}
                  type="button"
                  onClick={() => pick(i)}
                  data-testid={`period-month-${i}`}
                  aria-pressed={isSelected}
                  className={[
                    'h-9 rounded-lg text-[13px] font-bold transition',
                    isSelected
                      ? 'bg-brand-green-600 text-white'
                      : isToday
                        ? 'bg-brand-green-50 text-brand-green-700 ring-1 ring-brand-green-200'
                        : 'text-ink-700 hover:bg-paper-hover',
                  ].join(' ')}
                >
                  {m}
                </button>
              )
            })}
          </div>

          {/* Быстрый выбор текущего месяца */}
          <button
            type="button"
            onClick={goCurrent}
            data-testid="period-current"
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-lg border border-ink-200 py-2 text-[12px] font-bold text-ink-600 hover:bg-paper-hover hover:text-brand-green-700"
          >
            <IconCalendar size={13} />
            {t('period.current')} · {formatPeriod(today, lang)}
          </button>
        </div>
      )}
    </div>
  )
}
