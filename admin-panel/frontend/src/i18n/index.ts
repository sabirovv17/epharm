// i18n — лёгкий хук перевода поверх Zustand-store (без внешних зависимостей).
//
//   const t = useT()
//   t('nav.dashboard')            → строка на текущем языке
//   t('greet', { name: 'Амир' })  → интерполяция {name}
//
// Lookup: dict[lang][key] → fallback dict.ru[key] → fallback сам key.

import { useUiStore } from '@/app/store'
import { dict, type Lang } from './dict'

export type { Lang }
export { dict }

export function translate(lang: Lang, key: string, vars?: Record<string, string | number>): string {
  const raw = dict[lang]?.[key] ?? dict.ru[key] ?? key
  if (!vars) return raw
  return raw.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? `{${k}}`))
}

export function useT(): (key: string, vars?: Record<string, string | number>) => string {
  const lang = useUiStore((s) => s.language)
  return (key, vars) => translate(lang, key, vars)
}
