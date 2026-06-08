// ComingSoonBanner — admin §13 — full-width blue-tinted баннер
import { IconInfo } from './icons'

interface ComingSoonBannerProps {
  title?: string
  body?: string
}

export function ComingSoonBanner({ title = 'В разработке', body }: ComingSoonBannerProps) {
  return (
    <div className="card flex items-center gap-3 border-brand-blue-200/60 bg-brand-blue-100/40 p-4">
      <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-blue-100 text-brand-blue-600">
        <IconInfo size={18} />
      </span>
      <div className="flex-1">
        <div className="text-[13px] font-extrabold text-ink-900">{title}</div>
        {body && <div className="text-[12px] text-ink-500">{body}</div>}
      </div>
    </div>
  )
}
