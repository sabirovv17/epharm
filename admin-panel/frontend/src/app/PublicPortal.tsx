import { useEffect, type ReactNode } from 'react'

/** External task/producer screens are responsive; this CSS boundary grants no application rights. */
export function PublicPortal({ children }: { children: ReactNode }) {
  useEffect(() => {
    document.body.classList.add('public-portal')
    return () => document.body.classList.remove('public-portal')
  }, [])
  return children
}
