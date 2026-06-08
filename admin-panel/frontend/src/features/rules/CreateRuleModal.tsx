// CreateRuleModal — 3-step wizard для создания нового правила.
// Шаг 1: тип (substitution | crosssell). Шаг 2: BuilderForm. Шаг 3: RulePreview.
//
// Этап 3.2: вместо локального state-генератора правила, шлём CreateRuleRequest
// на backend через useCreateRule и пробрасываем созданный RuleDto в onCreate.

import { Fragment, useEffect, useState } from 'react'
import { Button, Field, Modal } from '@/ui'
import { IconCheck, IconChevLeft, IconChevRight, IconStack, IconSwap } from '@/ui/icons'
import type { Rule, RuleType } from '@/mocks/fixtures'
import type { CreateRuleRequest } from '@/lib/api-types'
import { BuilderForm, RulePreview } from './RuleBuilder'
import { useCreateRule } from '@/lib/queries/rules'
import { useT } from '@/i18n'

/**
 * Локальный «черновик» — пока не отправлен на backend. Имеет все поля Rule,
 * id/createdAt/updatedAt — синтетические заглушки, заменятся серверной парой.
 */
const initialDraft = (): Rule => ({
  id: 'draft',
  type: 'substitution',
  status: 'draft',
  trigger: { kind: 'product', value: '' },
  recommend: '',
  bonus: 300,
  impressions: 0,
  accepts: 0,
  convRate: 0,
  revenue: 0,
  payout: 0,
  script: '',
  advantages: [],
  abTest: null,
  card: null,
  createdBy: '',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  pharmacies: 0,
})

function buildRequest(draft: Rule): CreateRuleRequest {
  return {
    type: draft.type,
    status: 'active',
    trigger: draft.trigger,
    recommend: draft.recommend,
    bonus: draft.bonus,
    script: draft.script,
    // Чистим пустые строки и trim — в textarea они допускаются для удобства
    // набора, но на backend шлём санитизированный массив. См. BuilderForm
    // комментарий про cursor jump.
    advantages: draft.advantages.map((a) => a.trim()).filter((a) => a.length > 0),
    abTest: draft.abTest,
    card: buildCard(draft.card),
  }
}

/** Богатая карточка (Фаза 2): чистим пустые строки сравнения; пустую карточку не шлём. */
function buildCard(c: Rule['card']): CreateRuleRequest['card'] {
  const cleanComparison = (c?.comparison ?? []).filter((r) => r.label.trim().length > 0)
  const hasContent =
    cleanComparison.length > 0 ||
    !!c?.partnerLabel?.trim() ||
    !!c?.goalLabel?.trim() ||
    c?.goalTarget != null ||
    c?.goalBonus != null
  if (!hasContent) return undefined
  return {
    partnerLabel: c?.partnerLabel?.trim() || null,
    comparison: cleanComparison,
    goalLabel: c?.goalLabel?.trim() || null,
    goalTarget: c?.goalTarget ?? null,
    goalBonus: c?.goalBonus ?? null,
  }
}

interface CreateRuleModalProps {
  open: boolean
  onClose: () => void
  onCreate: (rule: Rule) => void
  onError?: (message: string) => void
}

export function CreateRuleModal({ open, onClose, onCreate, onError }: CreateRuleModalProps) {
  const t = useT()
  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [draft, setDraft] = useState<Rule>(() => initialDraft())
  const create = useCreateRule()

  useEffect(() => {
    if (open) {
      setStep(1)
      setDraft(initialDraft())
      create.reset()
    }
  }, [open]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async () => {
    try {
      const created = await create.mutateAsync(buildRequest(draft))
      onCreate(created)
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } }
      onError?.(e.response?.data?.message ?? t('rules.createErr'))
    }
  }

  const setType = (type: RuleType) => setDraft((d) => ({ ...d, type }))

  const valid =
    draft.recommend !== '' &&
    draft.trigger.value !== '' &&
    !(Array.isArray(draft.trigger.value) && draft.trigger.value.length === 0)

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('rules.newRule')}
      subtitle={t('rules.modalSub')}
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          {step > 1 && (
            <Button
              variant="outline"
              onClick={() => setStep((s) => (s === 3 ? 2 : 1))}
              leading={<IconChevLeft size={14} />}
            >
              {t('rules.back')}
            </Button>
          )}
          {step < 3 ? (
            <Button
              onClick={() => setStep((s) => (s === 1 ? 2 : 3))}
              trailing={<IconChevRight size={14} />}
              disabled={step === 2 && !valid}
            >
              {t('rules.next')}
            </Button>
          ) : (
            <Button
              variant="primary"
              onClick={submit}
              leading={<IconCheck size={14} />}
              disabled={!valid || create.isPending}
            >
              {create.isPending ? t('rules.saving') : t('rules.create')}
            </Button>
          )}
        </>
      }
    >
      {/* Step indicator */}
      <div className="mb-5 flex items-center gap-1.5">
        {[1, 2, 3].map((n) => (
          <Fragment key={n}>
            <span
              className={`flex h-8 w-8 items-center justify-center rounded-full text-[13px] font-extrabold ${
                step >= n ? 'bg-brand-green-600 text-white' : 'bg-ink-100 text-ink-400'
              }`}
            >
              {n}
            </span>
            {n < 3 && (
              <span className={`h-0.5 flex-1 ${step > n ? 'bg-brand-green-600' : 'bg-ink-100'}`} />
            )}
          </Fragment>
        ))}
      </div>

      {step === 1 && (
        <div className="flex flex-col gap-3">
          <Field label={t('rules.fldType')}>
            <div className="grid grid-cols-2 gap-3">
              {(
                [
                  {
                    v: 'substitution' as const,
                    label: t('rules.tReplace'),
                    desc: t('rules.tReplaceDesc'),
                    Icon: IconSwap,
                  },
                  {
                    v: 'crosssell' as const,
                    label: t('rules.tabCross'),
                    desc: t('rules.tCrossDesc'),
                    Icon: IconStack,
                  },
                ] as const
              ).map((opt) => {
                const isActive = draft.type === opt.v
                return (
                  <button
                    key={opt.v}
                    type="button"
                    onClick={() => setType(opt.v)}
                    className={`rounded-xl border p-4 text-left ${
                      isActive
                        ? 'border-brand-green-600 bg-brand-green-50'
                        : 'border-ink-200 hover:bg-paper-hover'
                    }`}
                  >
                    <div className="mb-1.5 flex items-center gap-2">
                      <opt.Icon size={18} />
                      <span className="text-sm font-extrabold text-ink-900">{opt.label}</span>
                      {isActive && (
                        <span className="ml-auto text-brand-green-600">
                          <IconCheck size={16} />
                        </span>
                      )}
                    </div>
                    <div className="text-[12px] font-semibold text-ink-500">{opt.desc}</div>
                  </button>
                )
              })}
            </div>
          </Field>
        </div>
      )}

      {step === 2 && <BuilderForm value={draft} onChange={setDraft} />}

      {step === 3 && (
        <div className="flex flex-col gap-3">
          <div className="mb-1 text-sm font-extrabold text-ink-900">{t('rules.checkRule')}</div>
          <RulePreview rule={draft} />
        </div>
      )}
    </Modal>
  )
}
