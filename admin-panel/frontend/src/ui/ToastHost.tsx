// ToastHost — admin §11.3 — Zustand-style toast queue.
// Используем простой React-провайдер вместо global Zustand store, потому что toast'ы
// не нужны вне React-tree (CommandPalette, например, всегда внутри App).

import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

type ToastKind = 'success' | 'error' | 'info'

interface ToastAction {
  label: string
  onClick: () => void
}

interface ToastItem {
  id: string
  msg: string
  kind: ToastKind
  action?: ToastAction
}

interface ToastApi {
  push: (msg: string, opts?: { kind?: ToastKind; action?: ToastAction; duration?: number }) => void
}

const ToastCtx = createContext<ToastApi>({ push: () => {} })

export function useToast(): ToastApi {
  return useContext(ToastCtx)
}

const KIND_COLOR: Record<ToastKind, string> = {
  success: '#16C97A', // семантика «успех» — остаётся зелёным
  error: '#E5484D',
  info: '#BE5A38', // info — бренд-акцент, теперь коралл
}

export function ToastHost({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const push = useCallback<ToastApi['push']>((msg, opts = {}) => {
    const id = Math.random().toString(36).slice(2)
    setItems((s) => [...s, { id, msg, kind: opts.kind ?? 'success', action: opts.action }])
    setTimeout(() => {
      setItems((s) => s.filter((x) => x.id !== id))
    }, opts.duration ?? 3200)
  }, [])

  return (
    <ToastCtx.Provider value={{ push }}>
      {children}
      <div className="fixed bottom-5 left-1/2 z-[90] flex -translate-x-1/2 flex-col items-center gap-2">
        {items.map((it) => (
          <div
            key={it.id}
            className="card slide-in flex min-w-[280px] items-center gap-3 px-4 py-2.5 shadow-elevated"
          >
            <span className="chip-dot" style={{ background: KIND_COLOR[it.kind] }} />
            <span className="flex-1 text-sm font-semibold text-ink-900">{it.msg}</span>
            {it.action && (
              <button
                className="text-[13px] font-bold text-brand-green-700"
                onClick={it.action.onClick}
              >
                {it.action.label}
              </button>
            )}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}
