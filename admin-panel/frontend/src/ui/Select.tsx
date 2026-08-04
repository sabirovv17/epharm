// Select — admin §8.3 (shell of .inp + chevron, native <select>)
import { IconChevDown } from './icons'

export type SelectOption = string | { value: string; label: string }

interface SelectProps {
  value: string | undefined
  onChange: (next: string) => void
  options: SelectOption[]
  placeholder?: string
  className?: string
  ariaLabel?: string
}

export function Select({
  value,
  onChange,
  options,
  placeholder = 'Выбрать',
  className = '',
  ariaLabel,
}: SelectProps) {
  return (
    <div className={`relative ${className}`}>
      <select
        aria-label={ariaLabel}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="inp appearance-none cursor-pointer pr-9"
      >
        {!value && (
          <option value="" disabled>
            {placeholder}
          </option>
        )}
        {options.map((o) => {
          const v = typeof o === 'string' ? o : o.value
          const l = typeof o === 'string' ? o : o.label
          return (
            <option key={v} value={v}>
              {l}
            </option>
          )
        })}
      </select>
      <span className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-2.5 text-ink-400">
        <IconChevDown size={16} />
      </span>
    </div>
  )
}
