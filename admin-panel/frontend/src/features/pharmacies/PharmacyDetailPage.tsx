// PharmacyDetailPage — полная страница аптеки /pharmacies/:id (паттерн PromoDetail).
// Редактирование описательных полей (PATCH) + удаление (DELETE с guard'ом на
// фармацевтов → backend 409). Метрики (чеки/GMV/lift) read-only — их пишет ETL.

import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Button, Empty, Field, Input, PageHeader, Select, Toggle, useToast } from '@/ui'
import { IconArrowUp, IconPharmacy, IconTrash } from '@/ui/icons'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import type { PharmacyGroup, UpdatePharmacyRequest } from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { useDeletePharmacy, usePharmacy, useUpdatePharmacy } from '@/lib/queries/pharmacies'

interface FormState {
  name: string
  city: string
  district: string
  addr: string
  group: PharmacyGroup
  active: boolean
}

export default function PharmacyDetailPage() {
  const t = useT()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()

  const { data: ph, isLoading, isError, error, refetch } = usePharmacy(id || null)
  const updatePharmacy = useUpdatePharmacy()
  const deletePharmacy = useDeletePharmacy()

  const [form, setForm] = useState<FormState | null>(null)
  const [saveErr, setSaveErr] = useState<string | null>(null)

  useEffect(() => {
    if (ph) {
      setForm({
        name: ph.name,
        city: ph.city,
        district: ph.district,
        addr: ph.addr,
        group: ph.group,
        active: ph.active,
      })
      setSaveErr(null)
    }
  }, [ph])

  const goBack = () => navigate('/pharmacies')

  if (isLoading && !ph) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('phd.loading')}
          body={t('common.connecting')}
          icon={<IconPharmacy size={26} />}
        />
      </Shell>
    )
  }

  if (isError && !ph) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('phd.errTitle')}
          body={describeError(error)}
          icon={<IconPharmacy size={26} />}
          action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
        />
      </Shell>
    )
  }

  if (!ph || !form) {
    return (
      <Shell onBack={goBack}>
        <Empty
          title={t('phd.notFound')}
          body={t('phd.notFoundBody')}
          icon={<IconPharmacy size={26} />}
          action={<Button onClick={goBack}>{t('phd.toList')}</Button>}
        />
      </Shell>
    )
  }

  const pending = updatePharmacy.isPending || deletePharmacy.isPending

  const dirty =
    form.name !== ph.name ||
    form.city !== ph.city ||
    form.district !== ph.district ||
    form.addr !== ph.addr ||
    form.group !== ph.group ||
    form.active !== ph.active

  const setField = (patch: Partial<FormState>) => setForm((f) => (f ? { ...f, ...patch } : f))

  const groupOptions = [
    { value: 'pilot', label: t('ph.tabPilot') },
    { value: 'control', label: t('ph.tabControl') },
    { value: 'rolled', label: t('ph.tabRolled') },
  ]

  const handleSave = () => {
    if (!form.name.trim()) {
      setSaveErr(t('phd.saveErr'))
      return
    }
    const patch: UpdatePharmacyRequest = {
      name: form.name.trim(),
      city: form.city.trim(),
      district: form.district.trim(),
      addr: form.addr.trim(),
      group: form.group,
      active: form.active,
    }
    updatePharmacy.mutate(
      { id: ph.id, patch },
      {
        onSuccess: () => toast.push(t('phd.saved')),
        onError: (e) => setSaveErr(describeError(e)),
      },
    )
  }

  const handleDelete = () => {
    if (!confirm(t('phd.confirmDelete', { name: ph.name }))) return
    deletePharmacy.mutate(ph.id, {
      onSuccess: () => {
        toast.push(t('phd.deletedToast'))
        goBack()
      },
      onError: () => toast.push(t('phd.deleteErr')),
    })
  }

  return (
    <Shell
      onBack={goBack}
      actions={
        <Button
          variant="danger"
          leading={<IconTrash size={14} />}
          disabled={pending}
          onClick={handleDelete}
        >
          {t('phd.delete')}
        </Button>
      }
    >
      <div className="grid grid-cols-3 gap-5" data-testid="pharmacy-detail">
        {/* Левая колонка: редактор */}
        <div className="col-span-2 flex flex-col gap-4">
          {saveErr && (
            <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
              {saveErr}
            </div>
          )}

          <div className="card flex flex-col gap-4 p-5">
            <div className="text-[13px] font-extrabold uppercase tracking-[0.06em] text-ink-500">
              {t('phd.mainData')}
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Field label={t('phd.fldName')}>
                <Input value={form.name} onChange={(e) => setField({ name: e.target.value })} />
              </Field>
              <Field label={t('phd.fldCity')}>
                <Input value={form.city} onChange={(e) => setField({ city: e.target.value })} />
              </Field>
              <Field label={t('phd.fldDistrict')} optional>
                <Input
                  value={form.district}
                  onChange={(e) => setField({ district: e.target.value })}
                />
              </Field>
              <Field label={t('phd.fldAddr')} optional>
                <Input value={form.addr} onChange={(e) => setField({ addr: e.target.value })} />
              </Field>
              <Field label={t('phd.fldGroup')}>
                <Select
                  value={form.group}
                  onChange={(v) => setField({ group: v as PharmacyGroup })}
                  options={groupOptions}
                />
              </Field>
              <Field label={t('phd.fldActive')} hint={t('phd.activeHint')}>
                <Toggle on={form.active} onChange={(v) => setField({ active: v })} />
              </Field>
            </div>
            <div className="hairline flex items-center justify-end gap-2 border-t pt-3">
              <Button variant="ghost" onClick={goBack}>
                {t('common.cancel')}
              </Button>
              <Button
                variant="primary"
                disabled={!dirty || updatePharmacy.isPending}
                onClick={handleSave}
              >
                {updatePharmacy.isPending ? t('phf.creating') : t('common.save')}
              </Button>
            </div>
          </div>
        </div>

        {/* Правая колонка: мета + метрики (read-only) */}
        <div className="card-soft grid grid-cols-2 gap-3 self-start p-4 text-[13px]">
          <Meta label={t('phd.metaChain')} value={ph.chainName} />
          <Meta label={t('phd.metaPharm')} value={`${ph.pharmacists}`} />
          <Meta label={t('phd.metaReceipts')} value={formatNum(ph.receipts30d)} />
          <Meta label={t('phd.metaGmv')} value={formatKzt(ph.gmv30d)} />
          <Meta
            label={t('phd.metaLift')}
            value={ph.liftPct > 0 ? `+${ph.liftPct.toFixed(1)}%` : '—'}
          />
          <Meta label={t('phd.metaId')} value={ph.id} mono wide testId="pharmacy-detail-posm-id" />
        </div>
      </div>
    </Shell>
  )
}

function Shell({
  children,
  onBack,
  actions,
}: {
  children: React.ReactNode
  onBack: () => void
  actions?: React.ReactNode
}) {
  const t = useT()
  return (
    <div className="flex flex-col gap-4">
      <button
        type="button"
        onClick={onBack}
        className="flex w-fit items-center gap-1 text-[13px] font-bold text-ink-500 hover:text-brand-green-700"
      >
        <IconArrowUp size={14} className="-rotate-90" /> {t('phd.back')}
      </button>
      <PageHeader
        title={t('page.pharmacyDetail.title')}
        subtitle={t('page.pharmacyDetail.subtitle')}
        actions={actions}
      />
      {children}
    </div>
  )
}

function Meta({
  label,
  value,
  mono,
  wide,
  testId,
}: {
  label: string
  value: string
  mono?: boolean
  wide?: boolean
  testId?: string
}) {
  return (
    <div
      className={
        wide ? 'col-span-2 min-w-0 rounded-lg border border-ink-100 bg-paper px-3 py-2' : 'min-w-0'
      }
    >
      <div className="text-[11px] font-bold uppercase tracking-[0.04em] text-ink-400">{label}</div>
      <div
        className={`mt-0.5 font-extrabold text-ink-900 ${
          mono ? 'num break-all text-[12px] leading-relaxed' : 'truncate'
        }`}
        title={value}
        data-testid={testId}
      >
        {value}
      </div>
    </div>
  )
}
