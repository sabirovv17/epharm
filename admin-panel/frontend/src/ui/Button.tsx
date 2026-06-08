// Button + IconButton — admin §8.1
// Variants: primary | ink | ghost | outline | danger
// Sizes:    sm (h-32) | md (h-38) | lg (h-44) | icon (36×36)

import type { ButtonHTMLAttributes, ReactNode } from 'react'

type Variant = 'primary' | 'ink' | 'ghost' | 'outline' | 'danger'
type Size = 'sm' | 'md' | 'lg'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  leading?: ReactNode
  trailing?: ReactNode
}

export function Button({
  variant = 'primary',
  size = 'md',
  leading,
  trailing,
  children,
  className = '',
  ...rest
}: ButtonProps) {
  return (
    <button className={`btn btn-${size} btn-${variant} ${className}`} {...rest}>
      {leading}
      <span>{children}</span>
      {trailing}
    </button>
  )
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  tip?: string
}

export function IconButton({ tip, children, className = '', ...rest }: IconButtonProps) {
  const body = (
    <button className={`btn btn-md btn-ghost btn-icon ${className}`} {...rest}>
      {children}
    </button>
  )
  if (!tip) return body
  return (
    <span className="tip inline-flex">
      {body}
      <span className="tip-body">{tip}</span>
    </span>
  )
}
