// Общий stub для секций Этапа 1 — показывает PageHeader-имитацию + ComingSoonBanner.
// Реальные имплементации придут в задаче #24 (Этап 2 в PLAN.md).

import { ComingSoonBanner } from '@/ui'

interface SectionStubProps {
  title: string
  subtitle?: string
  body?: string
}

export function SectionStub({ title, subtitle, body }: SectionStubProps) {
  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="text-h1 text-ink-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-500">{subtitle}</p>}
      </div>
      <ComingSoonBanner
        title="В разработке"
        body={
          body ??
          'Секция переедет из admin-panel/references/sections/ в этот файл на следующем этапе. UI-kit и layout уже готовы.'
        }
      />
    </div>
  )
}
