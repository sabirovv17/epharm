import { useEffect, useState } from 'react'
import { Field, Input, Select } from '@/ui'
import { IconCheck, IconSearch } from '@/ui/icons'
import type { AccTriggerOptionDto, PromoTriggerKind, StorefrontProductDto } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { useAccTriggerOptions } from '@/lib/queries/accTriggerOptions'
import { MultiProductPicker } from './PromoProductPicker'

type TaxonomyKind = Exclude<PromoTriggerKind, 'product'>

export function PromoTriggerPicker({
  selectedProductIds,
  selectedScopeKeys,
  onProductPick,
  onScopePick,
}: {
  selectedProductIds: string[]
  selectedScopeKeys: string[]
  onProductPick: (product: StorefrontProductDto) => void
  onScopePick: (kind: TaxonomyKind, option: AccTriggerOptionDto) => void
}) {
  const t = useT()
  const [kind, setKind] = useState<PromoTriggerKind>('product')
  const [rawQuery, setRawQuery] = useState('')
  const [query, setQuery] = useState('')
  const taxonomyKind = kind === 'product' ? null : kind
  const { data, isPending, isError, error, refetch } = useAccTriggerOptions(taxonomyKind, query)

  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(rawQuery.trim()), 300)
    return () => window.clearTimeout(timeout)
  }, [rawQuery])

  return (
    <div className="flex flex-col gap-3">
      <Field label={t('pr.triggerType')}>
        <Select
          value={kind}
          onChange={(next) => {
            setKind(next as PromoTriggerKind)
            setRawQuery('')
            setQuery('')
          }}
          ariaLabel={t('pr.triggerType')}
          options={[
            { value: 'product', label: t('pr.triggerProduct') },
            { value: 'acc_group', label: t('pr.triggerGroup') },
            { value: 'acc_subgroup', label: t('pr.triggerSubgroup') },
            { value: 'acc_mnn', label: t('pr.triggerMnn') },
          ]}
        />
      </Field>

      {kind === 'product' ? (
        <MultiProductPicker selectedIds={selectedProductIds} onPick={onProductPick} />
      ) : (
        <div className="flex flex-col gap-2">
          <p className="text-[12px] text-ink-500">{t('pr.triggerScopeHint')}</p>
          {data?.snapshot && (
            <p className="text-[11px] text-ink-500" data-testid="pr-trigger-snapshot">
              {t('pr.triggerSnapshot', {
                source: data.snapshot.sourceName,
                count: data.snapshot.barcodeCount,
              })}
            </p>
          )}
          <Input
            value={rawQuery}
            onChange={(event) => setRawQuery(event.target.value)}
            placeholder={t('pr.triggerSearch')}
            aria-label={t('pr.triggerSearch')}
            leading={<IconSearch size={15} />}
            autoFocus
          />
          <div className="max-h-64 overflow-y-auto rounded-xl border border-ink-100" data-testid="pr-trigger-options">
            {isError ? (
              <div className="px-3 py-4 text-[12px] text-accent-danger" role="alert">
                {describeError(error)}{' '}
                <button type="button" onClick={() => refetch()} className="font-bold underline">
                  {t('common.retry')}
                </button>
              </div>
            ) : isPending ? (
              <div className="px-3 py-4 text-[12px] text-ink-500">{t('pr.triggerLoading')}</div>
            ) : !data?.snapshot ? (
              <div className="px-3 py-4 text-[12px] text-ink-500">{t('pr.triggerNoSnapshot')}</div>
            ) : !data.options.length ? (
              <div className="px-3 py-4 text-[12px] text-ink-500">{t('common.notFound')}</div>
            ) : (
              data.options.map((option) => {
                const selected = selectedScopeKeys.includes(`${kind}:${option.key}`)
                return (
                  <button
                    key={option.key}
                    type="button"
                    onClick={() => onScopePick(kind, option)}
                    aria-pressed={selected}
                    className="flex w-full items-center gap-3 border-b border-ink-50 px-3 py-2 text-left last:border-b-0 hover:bg-paper-input"
                    data-testid={`pr-trigger-option-${option.key}`}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-bold text-ink-900">{option.label}</span>
                      {option.parentLabel && (
                        <span className="block truncate text-[11px] text-ink-500">
                          {t('pr.triggerParent', { parent: option.parentLabel })}
                        </span>
                      )}
                    </span>
                    <span className="shrink-0 text-[11px] text-ink-500">
                      {t('pr.triggerMembers', { count: option.count })}
                    </span>
                    {selected && <IconCheck size={16} />}
                  </button>
                )
              })
            )}
          </div>
          <p className="text-[11px] text-ink-500">{t('pr.triggerBroadDraftHint')}</p>
        </div>
      )}
    </div>
  )
}
