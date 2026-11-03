import { useState } from 'react'
import type { PromoTriggerCondition, PromoTriggerKind } from '@/lib/api-types'
import { useT } from '@/i18n'
import { Button } from '@/ui'
import { PromoInitialTriggerPicker } from './PromoInitialTriggerPicker'

const SCOPE_KINDS: PromoTriggerCondition['kind'][] = ['acc_group', 'acc_subgroup', 'acc_mnn']

export function PromoTriggerConditions({
  baseKind,
  value,
  onChange,
  disabled = false,
}: {
  baseKind: PromoTriggerKind
  value: PromoTriggerCondition[]
  onChange: (value: PromoTriggerCondition[]) => void
  disabled?: boolean
}) {
  const t = useT()
  const [adding, setAdding] = useState(false)
  const [kind, setKind] = useState<PromoTriggerKind | null>(null)
  const available = SCOPE_KINDS.filter(
    (scope) => scope !== baseKind && !value.some((item) => item.kind === scope),
  )

  return (
    <div
      className="space-y-2 rounded-lg border border-ink-100 bg-paper-card p-3"
      data-testid="trigger-conditions"
    >
      <p className="text-[12px] font-semibold text-ink-700">{t('pr.conditionsHint')}</p>
      {value.map((condition) => (
        <div
          key={condition.kind}
          className="flex flex-wrap items-center justify-between gap-2 text-[12px] text-ink-900"
        >
          <span className="min-w-0 break-words">
            {t(`pr.triggerKind.${condition.kind}`)}: {condition.label || condition.value}
          </span>
          {!disabled && (
            <Button
              variant="ghost"
              onClick={() => onChange(value.filter((item) => item.kind !== condition.kind))}
              aria-label={t('pr.removeCondition', { label: condition.label || condition.value })}
              className="shrink-0"
            >
              {t('pr.removeConditionAction')}
            </Button>
          )}
        </div>
      ))}
      {!disabled &&
        available.length > 0 &&
        (adding ? (
          <div className="space-y-2">
            <PromoInitialTriggerPicker
              kind={kind}
              value={null}
              kinds={available}
              onKindChange={setKind}
              onChange={(selected) => {
                if (!selected || selected.kind === 'product') return
                onChange([
                  ...value,
                  { kind: selected.kind, value: selected.option.key, label: selected.option.label },
                ])
                setAdding(false)
                setKind(null)
              }}
            />
            <Button
              variant="ghost"
              onClick={() => {
                setAdding(false)
                setKind(null)
              }}
            >
              {t('common.cancel')}
            </Button>
          </div>
        ) : (
          <Button variant="ghost" onClick={() => setAdding(true)}>
            {t('pr.addCondition')}
          </Button>
        ))}
    </div>
  )
}
