import { useEffect, useState } from 'react'
import { Button, Input } from '@/ui'
import { IconCheck, IconSearch } from '@/ui/icons'
import type { AccTriggerOptionDto, PromoTriggerKind } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useAccTriggerOptions } from '@/lib/queries/accTriggerOptions'
import { useT } from '@/i18n'
import { PromoProductPicker, type SelectedProduct } from './PromoProductPicker'

type ScopeKind = Exclude<PromoTriggerKind, 'product'>

export type InitialTrigger =
  | { kind: 'product'; product: SelectedProduct }
  | { kind: ScopeKind; option: AccTriggerOptionDto }

const TRIGGER_KINDS: PromoTriggerKind[] = ['product', 'acc_group', 'acc_subgroup', 'acc_mnn']
const TRIGGER_LABEL_KEYS: Record<PromoTriggerKind, string> = {
  product: 'pr.triggerProduct',
  acc_group: 'pr.triggerGroup',
  acc_subgroup: 'pr.triggerSubgroup',
  acc_mnn: 'pr.triggerMnn',
}

export function PromoInitialTriggerPicker({
  kind,
  value,
  onKindChange,
  onChange,
}: {
  kind: PromoTriggerKind | null
  value: InitialTrigger | null
  onKindChange: (kind: PromoTriggerKind) => void
  onChange: (value: InitialTrigger | null) => void
}) {
  const t = useT()
  const [rawQuery, setRawQuery] = useState('')
  const [query, setQuery] = useState('')
  const [editingScope, setEditingScope] = useState(true)
  const scopeKind = kind && kind !== 'product' ? kind : null
  const { data, isPending, isError, error, refetch } = useAccTriggerOptions(scopeKind, query)

  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(rawQuery.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [rawQuery])

  const chooseKind = (next: PromoTriggerKind) => {
    if (next === kind) return
    setRawQuery('')
    setQuery('')
    setEditingScope(true)
    onKindChange(next)
  }

  const selectedScope = value && value.kind !== 'product' ? value : null
  const scopeLabel = selectedScope?.option.parentLabel
    ? `${selectedScope.option.parentLabel} / ${selectedScope.option.label}`
    : selectedScope?.option.label

  return (
    <div className="space-y-3">
      <div role="radiogroup" aria-label={t('pr.triggerType')} className="grid grid-cols-2 gap-2">
        {TRIGGER_KINDS.map((option) => (
          <label
            key={option}
            className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-[13px] font-semibold transition-colors ${
              kind === option
                ? 'border-brand-green-600 bg-brand-green-50 text-ink-900'
                : 'border-ink-200 bg-paper-card text-ink-700 hover:bg-paper-hover'
            }`}
          >
            <input
              type="radio"
              name="initial-trigger-kind"
              value={option}
              checked={kind === option}
              onChange={() => chooseKind(option)}
            />
            <span>{t(TRIGGER_LABEL_KEYS[option])}</span>
          </label>
        ))}
      </div>

      {kind === 'product' && (
        <PromoProductPicker
          key="initial-trigger-product"
          value={value?.kind === 'product' ? value.product : null}
          onChange={(product) => onChange({ kind: 'product', product })}
          requireQuery
        />
      )}

      {scopeKind && (
        <div className="space-y-2">
          {data?.snapshot && (
            <p className="text-[11px] text-ink-500" data-testid="create-trigger-snapshot">
              {t('pr.triggerSnapshot', {
                source: data.snapshot.sourceName,
                count: data.snapshot.barcodeCount,
              })}
            </p>
          )}
          {selectedScope && !editingScope ? (
            <div className="flex items-center gap-3 rounded-lg border border-brand-green-600/30 bg-brand-green-50 p-3">
              <IconCheck size={17} className="shrink-0 text-brand-green-700" />
              <div className="min-w-0 flex-1">
                <div className="truncate text-[13px] font-bold text-ink-900">{scopeLabel}</div>
                <div className="text-[11px] text-ink-500">
                  {t('pr.triggerMembers', { count: selectedScope.option.count })}
                </div>
              </div>
              <Button
                variant="ghost"
                onClick={() => {
                  onChange(null)
                  setEditingScope(true)
                }}
              >
                {t('pp.change')}
              </Button>
            </div>
          ) : (
            <>
              <Input
                value={rawQuery}
                onChange={(event) => setRawQuery(event.target.value)}
                placeholder={t('pr.triggerSearch')}
                aria-label={t('pr.triggerSearch')}
                leading={<IconSearch size={15} />}
              />
              <div className="max-h-48 overflow-y-auto rounded-lg border border-ink-200 bg-paper-card">
                {isError ? (
                  <div role="alert" className="p-3 text-[12px] text-accent-danger">
                    {describeError(error)}{' '}
                    <button type="button" onClick={() => refetch()} className="font-bold underline">
                      {t('common.retry')}
                    </button>
                  </div>
                ) : isPending ? (
                  <div className="p-3 text-[12px] text-ink-500">{t('pr.triggerLoading')}</div>
                ) : !data?.snapshot ? (
                  <div className="p-3 text-[12px] text-ink-500">{t('pr.triggerNoSnapshot')}</div>
                ) : !data.options.length ? (
                  <div className="p-3 text-[12px] text-ink-500">{t('common.notFound')}</div>
                ) : (
                  data.options.map((option) => (
                    <button
                      type="button"
                      key={option.key}
                      data-testid={`create-trigger-option-${option.key}`}
                      onClick={() => {
                        onChange({ kind: scopeKind, option })
                        setEditingScope(false)
                      }}
                      className="flex w-full items-center gap-3 border-b border-ink-100 px-3 py-2 text-left last:border-b-0 hover:bg-paper-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-green-600"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-semibold text-ink-900">{option.label}</span>
                        {option.parentLabel && (
                          <span className="block truncate text-[11px] text-ink-500">
                            {t('pr.triggerParent', { parent: option.parentLabel })}
                          </span>
                        )}
                      </span>
                      <span className="shrink-0 text-[11px] text-ink-500">
                        {t('pr.triggerMembers', { count: option.count })}
                      </span>
                    </button>
                  ))
                )}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
