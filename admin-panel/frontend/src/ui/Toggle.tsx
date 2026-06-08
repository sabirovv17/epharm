// Toggle (switch) — admin §8.4 — 36×20 pill.
//
// a11y: рендерим <button>, не <span>, чтобы клавиатурные пользователи могли
// сфокусировать (Tab) и переключить (Space/Enter). WAI-ARIA Authoring Practices
// требуют чтобы role="switch" был на focusable элементе.
interface ToggleProps {
  on: boolean
  onChange: (next: boolean) => void
  label?: string
  disabled?: boolean
}

export function Toggle({ on, onChange, label, disabled }: ToggleProps) {
  const t = (
    <button
      type="button"
      className={`toggle ${on ? 'on' : ''}`}
      role="switch"
      aria-checked={on}
      disabled={disabled}
      onClick={() => onChange(!on)}
    />
  )
  if (!label) return t
  return (
    <label className="inline-flex cursor-pointer select-none items-center gap-2">
      {t}
      <span className="text-[13px] font-semibold text-ink-700">{label}</span>
    </label>
  )
}
