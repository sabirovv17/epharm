// Input + SearchInput + Field — admin §8.2
import type { InputHTMLAttributes, ReactNode } from 'react'
import { IconSearch } from './icons'

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  leading?: ReactNode
  trailing?: ReactNode
}

export function Input({ leading, trailing, className = '', ...rest }: InputProps) {
  if (!leading && !trailing) {
    return <input className={`inp ${className}`} {...rest} />
  }
  return (
    <div className="relative">
      {leading && (
        <span className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3 text-ink-400">
          {leading}
        </span>
      )}
      <input
        className={`inp ${leading ? 'inp-search' : ''} ${trailing ? 'pr-10' : ''} ${className}`}
        {...rest}
      />
      {trailing && (
        <span className="absolute inset-y-0 right-0 flex items-center pr-2">{trailing}</span>
      )}
    </div>
  )
}

interface SearchInputProps {
  value: string
  onChange: (next: string) => void
  placeholder?: string
  className?: string
}

export function SearchInput({
  value,
  onChange,
  placeholder = 'Поиск',
  className = '',
}: SearchInputProps) {
  return (
    <Input
      leading={<IconSearch size={16} />}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className={className}
    />
  )
}

interface FieldProps {
  label?: ReactNode
  hint?: ReactNode
  error?: ReactNode
  optional?: boolean
  children: ReactNode
}

export function Field({ label, hint, error, optional, children }: FieldProps) {
  return (
    <label className="block">
      {label && (
        <span className="mb-1.5 block text-[12px] font-semibold text-ink-700">
          {label}
          {optional && <span className="ml-1 font-medium text-ink-400">— необязательно</span>}
        </span>
      )}
      {children}
      {hint && !error && <span className="mt-1 block text-[12px] text-ink-400">{hint}</span>}
      {error && <span className="mt-1 block text-[12px] text-accent-danger">{error}</span>}
    </label>
  )
}
