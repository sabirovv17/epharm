// Pharmacists — реестр фармацевтов ТЗ §3.2.
// Этап 3.4: подключён реальный backend через usePharmacists.

import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Button,
  Empty,
  Field,
  Input,
  Metric,
  Modal,
  PageHeader,
  SearchInput,
  Select,
  Tabs,
  useToast,
  type TabItem,
} from '@/ui'
import { IconPharmacist, IconUsers } from '@/ui/icons'
import type {
  PharmacistDto,
  PharmacistStatus,
  TrainingFormat,
  TrainingPreferenceDto,
} from '@/lib/api-types'
import {
  useActivatePharmacist,
  useBlockPharmacist,
  usePharmacists,
  useUnblockPharmacist,
} from '@/lib/queries/pharmacists'
import { usePharmacies } from '@/lib/queries/pharmacies'
import {
  useChangeTrainingPreference,
  useMassChangeTrainingPreferences,
  usePharmacistTrainingProfile,
  useTrainingDashboard,
  useTrainingPreferenceHistory,
  useTrainingPreferences,
} from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { formatKzt, formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import { ActivatePharmacistModal } from './ActivatePharmacistModal'
import { ASSIGNMENT_STATUS_LABEL, FORMAT_LABEL, dateTime } from '@/features/lms/training-ui'

type PhTab = 'all' | PharmacistStatus

const TAB_KEYS: Record<PhTab, string> = {
  all: 'phc.tabAll',
  active: 'phc.tabActive',
  pending: 'phc.tabPending',
  blocked: 'phc.tabBlocked',
}

export default function PharmacistsPage() {
  const t = useT()
  const toast = useToast()
  const navigate = useNavigate()
  const [tab, setTab] = useState<PhTab>('all')
  const [q, setQ] = useState('')
  const [activating, setActivating] = useState<PharmacistDto | null>(null)
  const [formatFilter, setFormatFilter] = useState<TrainingFormat | 'all' | 'unset'>('all')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [preferenceTargets, setPreferenceTargets] = useState<PharmacistDto[] | null>(null)
  const [trainingProfileId, setTrainingProfileId] = useState<string | null>(null)

  const { data: pharmacists = [], isLoading, isError, error, refetch } = usePharmacists()
  const { data: pharmacies = [] } = usePharmacies()
  const preferencesQuery = useTrainingPreferences()
  const trainingDashboardQuery = useTrainingDashboard()
  const activatePharmacist = useActivatePharmacist()
  const blockPharmacist = useBlockPharmacist()
  const unblockPharmacist = useUnblockPharmacist()
  const preferenceByPharmacist = useMemo(
    () =>
      new Map(
        (preferencesQuery.data ?? []).map((preference) => [preference.pharmacistId, preference]),
      ),
    [preferencesQuery.data],
  )
  const canManagePreferences =
    trainingDashboardQuery.data?.capabilities.canManagePreferences ?? false
  const canManageAssignments =
    trainingDashboardQuery.data?.capabilities.canManageAssignments ?? false

  const hasData = pharmacists.length > 0
  const showFullError = isError && !hasData
  const showWarningBanner = isError && hasData

  const counts = useMemo(() => {
    return {
      all: pharmacists.length,
      active: pharmacists.filter((p) => p.status === 'active').length,
      pending: pharmacists.filter((p) => p.status === 'pending').length,
      blocked: pharmacists.filter((p) => p.status === 'blocked').length,
    }
  }, [pharmacists])

  const totalBalance = useMemo(() => pharmacists.reduce((a, p) => a + p.balance, 0), [pharmacists])

  const filtered = useMemo<PharmacistDto[]>(() => {
    return pharmacists.filter((p) => {
      if (tab !== 'all' && p.status !== tab) return false
      const preference = preferenceByPharmacist.get(p.id)
      if (formatFilter === 'unset' && preference) return false
      if (
        formatFilter !== 'all' &&
        formatFilter !== 'unset' &&
        preference?.defaultFormat !== formatFilter
      )
        return false
      if (!q) return true
      return `${p.name} ${p.pharmacyName} ${p.city} ${p.iin} ${p.phone}`
        .toLowerCase()
        .includes(q.toLowerCase())
    })
  }, [formatFilter, pharmacists, preferenceByPharmacist, q, tab])

  const tabs: TabItem<PhTab>[] = (['all', 'active', 'pending', 'blocked'] as PhTab[]).map((v) => ({
    value: v,
    label: t(TAB_KEYS[v]),
    count: counts[v],
  }))

  const allVisibleSelected = filtered.length > 0 && filtered.every((p) => selectedIds.has(p.id))
  const selectedPharmacists = pharmacists.filter((p) => selectedIds.has(p.id))
  const assignableSelected = selectedPharmacists.filter((p) => p.status === 'active')
  const toggleSelection = (id: string) => {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  const toggleVisible = () => {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (allVisibleSelected) filtered.forEach((p) => next.delete(p.id))
      else filtered.forEach((p) => next.add(p.id))
      return next
    })
  }

  const handleToggleBlock = (p: PharmacistDto, e: React.MouseEvent) => {
    e.stopPropagation()
    if (p.status === 'blocked') {
      unblockPharmacist.mutate(p.id, {
        onSuccess: () => toast.push(t('phc.unblockedToast', { name: p.name })),
        onError: () => toast.push(t('phc.unblockErr')),
      })
    } else {
      if (!confirm(t('phc.confirmBlock', { name: p.name }))) return
      blockPharmacist.mutate(p.id, {
        onSuccess: () => toast.push(t('phc.blockedToast', { name: p.name })),
        onError: () => toast.push(t('phc.blockErr')),
      })
    }
  }

  const handleActivate = (pharmacyId: string) => {
    if (!activating) return
    activatePharmacist.mutate(
      { id: activating.id, request: { pharmacyId } },
      {
        onSuccess: () => {
          toast.push(t('phc.activatedToast', { name: activating.name }))
          setActivating(null)
        },
        onError: (activationError) =>
          toast.push(`${t('phc.activateErr')}: ${describeError(activationError)}`),
      },
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.pharmacists.title')} subtitle={t('page.pharmacists.subtitle')} />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('phc.mTotal')}
          value={formatNum(counts.all)}
          accent="ink"
          icon={<IconPharmacist size={18} />}
        />
        <Metric label={t('phc.mActive')} value={formatNum(counts.active)} accent="green" />
        <Metric label={t('phc.mPending')} value={formatNum(counts.pending)} accent="amber" />
        <Metric
          label={t('phc.mBalances')}
          value={formatKzt(totalBalance)}
          accent="purple"
          sub={t('phc.toPayout')}
        />
      </div>

      <div className="card flex min-h-[480px] flex-col">
        <div className="hairline flex flex-wrap items-center gap-3 border-b px-5 py-3">
          <Tabs<PhTab> items={tabs} value={tab} onChange={setTab} />
          <SearchInput
            value={q}
            onChange={setQ}
            placeholder={t('phc.search')}
            className="!h-9 !w-[300px] flex-none"
          />
          <Select
            value={formatFilter}
            onChange={(value) => setFormatFilter(value as TrainingFormat | 'all' | 'unset')}
            className="w-[180px]"
            options={[
              { value: 'all', label: 'Все форматы' },
              ...Object.entries(FORMAT_LABEL).map(([value, label]) => ({ value, label })),
              { value: 'unset', label: 'Формат не задан' },
            ]}
          />
          {canManagePreferences && selectedPharmacists.length > 0 && (
            <Button size="sm" onClick={() => setPreferenceTargets(selectedPharmacists)}>
              Изменить формат ({selectedPharmacists.length})
            </Button>
          )}
          {canManageAssignments && assignableSelected.length > 0 && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                const params = new URLSearchParams({
                  action: 'assign',
                  pharmacists: assignableSelected.map((pharmacist) => pharmacist.id).join(','),
                })
                navigate(`/lms?${params.toString()}`)
              }}
            >
              Назначить обучение ({assignableSelected.length})
            </Button>
          )}
        </div>

        {showWarningBanner && (
          <div className="hairline border-b bg-accent-warning/10 px-5 py-2 text-[12px] font-semibold text-ink-700">
            {t('common.staleWarn', { e: describeError(error) })}{' '}
            <button
              type="button"
              onClick={() => refetch()}
              className="font-bold text-brand-green-700 underline"
            >
              {t('common.retry')}
            </button>
          </div>
        )}

        <div className="scrollbar-thin flex-1 overflow-auto">
          {isLoading && !hasData ? (
            <Empty
              title={t('phc.loading')}
              body={t('common.connecting')}
              icon={<IconPharmacist size={26} />}
            />
          ) : showFullError ? (
            <Empty
              title={t('common.loadFailed')}
              body={describeError(error)}
              icon={<IconPharmacist size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : filtered.length === 0 ? (
            <Empty
              title={q || tab !== 'all' ? t('common.notFound') : t('phc.empty')}
              body={q || tab !== 'all' ? t('common.notFoundBody') : t('phc.emptyBody')}
              icon={<IconPharmacist size={26} />}
            />
          ) : (
            <table className="w-full text-[13px]" data-testid="pharmacists-table">
              <thead className="hairline border-b">
                <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                  {canManagePreferences && (
                    <th className="w-10 px-3 py-2.5">
                      <input
                        type="checkbox"
                        aria-label="Выбрать всех видимых фармацевтов"
                        checked={allVisibleSelected}
                        onChange={toggleVisible}
                        className="h-4 w-4 accent-brand-green-700"
                      />
                    </th>
                  )}
                  <th className="px-5 py-2.5">{t('phc.thName')}</th>
                  <th className="px-3 py-2.5">{t('phc.thPharmacy')}</th>
                  <th className="px-3 py-2.5">Формат обучения</th>
                  <th className="px-3 py-2.5">{t('phc.thTier')}</th>
                  <th className="px-3 py-2.5 text-right">{t('phc.thBalance')}</th>
                  <th className="px-3 py-2.5 text-right">{t('phc.thReceipts')}</th>
                  <th className="px-3 py-2.5">{t('phc.thStatus')}</th>
                  <th className="px-5 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {filtered.map((p) => (
                  <tr
                    key={p.id}
                    className="hover:bg-paper-hover"
                    data-testid={`pharmacist-row-${p.id}`}
                  >
                    {canManagePreferences && (
                      <td className="px-3 py-2.5">
                        <input
                          type="checkbox"
                          aria-label={`Выбрать ${p.name}`}
                          checked={selectedIds.has(p.id)}
                          onChange={() => toggleSelection(p.id)}
                          className="h-4 w-4 accent-brand-green-700"
                        />
                      </td>
                    )}
                    <td className="px-5 py-2.5">
                      <div className="flex items-center gap-2">
                        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-ink-100 text-ink-500">
                          <IconUsers size={14} />
                        </span>
                        <div>
                          <div className="font-extrabold text-ink-900">{p.name}</div>
                          <div className="text-[11px] text-ink-500">{p.phone}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-ink-700">
                      <div className={!p.pharmacyName ? 'font-bold text-accent-warning' : ''}>
                        {p.pharmacyName || t('phc.unassigned')}
                      </div>
                      <div className="text-[11px] text-ink-500">{p.city}</div>
                    </td>
                    <td className="px-3 py-2.5">
                      {preferenceByPharmacist.has(p.id) ? (
                        <button
                          type="button"
                          onClick={() => setPreferenceTargets([p])}
                          disabled={!canManagePreferences}
                          className="chip chip-ink disabled:cursor-default"
                        >
                          {FORMAT_LABEL[preferenceByPharmacist.get(p.id)!.defaultFormat]}
                        </button>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setPreferenceTargets([p])}
                          disabled={!canManagePreferences}
                          className="text-[12px] font-semibold text-ink-400 disabled:cursor-default"
                        >
                          Не задан
                        </button>
                      )}
                    </td>
                    <td className="px-3 py-2.5">
                      <span
                        className={`chip ${
                          p.tier === 'Platinum'
                            ? 'chip-purple'
                            : p.tier === 'Gold'
                              ? 'chip-amber'
                              : 'chip-ink'
                        }`}
                      >
                        {p.tier}
                      </span>
                    </td>
                    <td className="num px-3 py-2.5 text-right font-extrabold text-brand-green-700">
                      {formatKzt(p.balance)}
                    </td>
                    <td className="num px-3 py-2.5 text-right">{formatNum(p.receipts30d)}</td>
                    <td className="px-3 py-2.5">
                      <span
                        className={`chip ${
                          p.status === 'active'
                            ? 'chip-green'
                            : p.status === 'pending'
                              ? 'chip-amber'
                              : 'chip-red'
                        }`}
                      >
                        {p.status === 'active'
                          ? t('phc.sActive')
                          : p.status === 'pending'
                            ? t('phc.sPending')
                            : t('phc.sBlocked')}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-right">
                      <div className="flex items-center justify-end gap-3">
                        <button
                          type="button"
                          onClick={() => setTrainingProfileId(p.id)}
                          className="text-[12px] font-bold text-brand-green-700 hover:underline"
                        >
                          Обучение
                        </button>
                        {p.status === 'pending' && (
                          <button
                            type="button"
                            onClick={() => setActivating(p)}
                            disabled={
                              activatePharmacist.isPending &&
                              activatePharmacist.variables?.id === p.id
                            }
                            className="text-[12px] font-bold text-brand-green-700 hover:underline disabled:opacity-50"
                          >
                            {t('phc.activate')}
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={(e) => handleToggleBlock(p, e)}
                          // Блокируем ТОЛЬКО эту строку, пока её мутация в полёте
                          // (variables === p.id) — иначе быстрый двойной клик шлёт
                          // два block/unblock. Остальная таблица остаётся активной.
                          disabled={
                            (blockPharmacist.isPending && blockPharmacist.variables === p.id) ||
                            (unblockPharmacist.isPending && unblockPharmacist.variables === p.id)
                          }
                          className={`text-[12px] font-bold disabled:opacity-50 ${
                            p.status === 'blocked'
                              ? 'text-brand-green-700 hover:underline'
                              : 'text-accent-danger hover:underline'
                          }`}
                        >
                          {p.status === 'blocked' ? t('phc.unblock') : t('phc.block')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {activating && (
        <ActivatePharmacistModal
          key={activating.id}
          pharmacist={activating}
          pharmacies={pharmacies}
          pending={activatePharmacist.isPending}
          onClose={() => setActivating(null)}
          onActivate={handleActivate}
        />
      )}
      {preferenceTargets && (
        <TrainingPreferenceModal
          key={preferenceTargets.map((pharmacist) => pharmacist.id).join(':')}
          pharmacists={preferenceTargets}
          current={
            preferenceTargets.length === 1
              ? (preferenceByPharmacist.get(preferenceTargets[0].id) ?? null)
              : null
          }
          onClose={() => setPreferenceTargets(null)}
          onSaved={() => {
            setPreferenceTargets(null)
            setSelectedIds(new Set())
          }}
        />
      )}
      {trainingProfileId && (
        <PharmacistTrainingProfileModal
          pharmacistId={trainingProfileId}
          onClose={() => setTrainingProfileId(null)}
        />
      )}
    </div>
  )
}

function PharmacistTrainingProfileModal({
  pharmacistId,
  onClose,
}: {
  pharmacistId: string
  onClose: () => void
}) {
  const profile = usePharmacistTrainingProfile(pharmacistId)
  const data = profile.data
  return (
    <Modal
      open
      onClose={onClose}
      title={data?.pharmacistName ?? 'Карточка обучения'}
      subtitle={
        data
          ? `${data.pharmacyName || 'Аптека не назначена'} · ${data.city}`
          : 'Загружаем историю обучения…'
      }
      width={960}
      footer={<Button onClick={onClose}>Закрыть</Button>}
    >
      {profile.isLoading ? (
        <div className="py-12 text-center text-[13px] text-ink-500">Загружаем карточку…</div>
      ) : profile.isError || !data ? (
        <div className="rounded-lg bg-surface-danger px-4 py-3 text-[13px] font-semibold text-accent-danger">
          {describeError(profile.error)}
        </div>
      ) : (
        <div className="flex flex-col gap-5">
          <div className="grid grid-cols-4 gap-3">
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Назначено</div>
              <div className="num mt-1 text-xl font-extrabold">
                {formatNum(data.totalAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Завершено</div>
              <div className="num mt-1 text-xl font-extrabold text-brand-green-700">
                {formatNum(data.completedAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">В работе</div>
              <div className="num mt-1 text-xl font-extrabold">
                {formatNum(data.inProgressAssignments)}
              </div>
            </div>
            <div className="rounded-lg bg-paper-hover px-4 py-3">
              <div className="text-[10px] font-bold uppercase text-ink-500">Бонусы за обучение</div>
              <div className="num mt-1 text-xl font-extrabold text-brand-green-700">
                {formatKzt(data.totalRewards)}
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between rounded-lg border border-ink-100 px-4 py-3 text-[13px]">
            <span className="font-semibold text-ink-600">Формат по умолчанию</span>
            <span className="chip chip-ink">
              {data.defaultFormat ? FORMAT_LABEL[data.defaultFormat] : 'Не задан'}
            </span>
          </div>

          <section>
            <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">
              Назначения и результаты
            </h3>
            {data.assignments.length === 0 ? (
              <div className="rounded-lg border border-ink-100 px-4 py-5 text-center text-[12px] text-ink-500">
                Обучение ещё не назначалось
              </div>
            ) : (
              <div className="max-h-64 overflow-auto rounded-lg border border-ink-100">
                <table className="w-full text-[12px]">
                  <thead className="sticky top-0 bg-white text-left text-[10px] font-bold uppercase text-ink-500">
                    <tr>
                      <th className="px-3 py-2">Программа</th>
                      <th className="px-3 py-2">Формат</th>
                      <th className="px-3 py-2">Статус</th>
                      <th className="px-3 py-2">Прогресс</th>
                      <th className="px-3 py-2">Результат</th>
                      <th className="px-3 py-2">Завершено</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-ink-100">
                    {data.assignments.map((assignment) => (
                      <tr key={assignment.id}>
                        <td className="px-3 py-2.5 font-bold text-ink-800">
                          {assignment.programName}
                        </td>
                        <td className="px-3 py-2.5">{FORMAT_LABEL[assignment.format]}</td>
                        <td className="px-3 py-2.5">
                          {ASSIGNMENT_STATUS_LABEL[assignment.status]}
                        </td>
                        <td className="num px-3 py-2.5">{assignment.progressPct}%</td>
                        <td className="num px-3 py-2.5">
                          {assignment.score == null ? '—' : `${assignment.score}%`}
                        </td>
                        <td className="num whitespace-nowrap px-3 py-2.5 text-ink-500">
                          {assignment.completedAt ? dateTime(assignment.completedAt) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <div className="grid grid-cols-2 gap-4">
            <section>
              <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">Сертификаты</h3>
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {data.certificates.length ? (
                  data.certificates.map((certificate) => (
                    <a
                      key={certificate.id}
                      href={certificate.pdfUrl ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                      className="flex items-center justify-between gap-3 px-3 py-2.5 hover:bg-paper-hover"
                    >
                      <span>
                        <b className="block text-[12px] text-ink-800">{certificate.programName}</b>
                        <span className="num text-[11px] text-ink-500">{certificate.number}</span>
                      </span>
                      <span className="num text-[11px] font-bold text-brand-green-700">
                        {certificate.score == null ? 'PDF' : `${certificate.score}% · PDF`}
                      </span>
                    </a>
                  ))
                ) : (
                  <div className="px-3 py-5 text-center text-[12px] text-ink-500">Пока нет</div>
                )}
              </div>
            </section>
            <section>
              <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">История формата</h3>
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {data.preferenceHistory.length ? (
                  data.preferenceHistory.map((preference) => (
                    <div key={preference.id} className="px-3 py-2.5 text-[12px]">
                      <div className="font-bold text-ink-800">
                        {FORMAT_LABEL[preference.defaultFormat]}
                      </div>
                      <div className="text-[11px] text-ink-500">
                        {dateTime(preference.validFrom)} · {preference.changedByName}
                      </div>
                      {preference.reason && (
                        <div className="mt-0.5 text-ink-600">{preference.reason}</div>
                      )}
                    </div>
                  ))
                ) : (
                  <div className="px-3 py-5 text-center text-[12px] text-ink-500">
                    Формат не менялся
                  </div>
                )}
              </div>
            </section>
          </div>
        </div>
      )}
    </Modal>
  )
}

function TrainingPreferenceModal({
  pharmacists,
  current,
  onClose,
  onSaved,
}: {
  pharmacists: PharmacistDto[] | null
  current: TrainingPreferenceDto | null
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const single = pharmacists?.length === 1 ? pharmacists[0] : null
  const history = useTrainingPreferenceHistory(single?.id ?? null)
  const change = useChangeTrainingPreference()
  const massChange = useMassChangeTrainingPreferences()
  const [format, setFormat] = useState<TrainingFormat>('online')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    if (!pharmacists?.length) return
    if (!reason.trim()) return setError('Укажите причину изменения формата')
    const callbacks = {
      onSuccess: () => {
        toast.push(
          pharmacists.length === 1
            ? 'Формат обучения по умолчанию изменён'
            : `Формат изменён у ${pharmacists.length} фармацевтов`,
        )
        onSaved()
      },
      onError: (requestError: unknown) => setError(describeError(requestError)),
    }
    if (single) {
      change.mutate(
        {
          pharmacistId: single.id,
          request: { defaultFormat: format, reason: reason.trim() },
        },
        callbacks,
      )
    } else {
      massChange.mutate(
        {
          pharmacistIds: pharmacists.map((pharmacist) => pharmacist.id),
          defaultFormat: format,
          reason: reason.trim(),
        },
        callbacks,
      )
    }
  }

  return (
    <Modal
      open={!!pharmacists?.length}
      onClose={onClose}
      title="Формат обучения по умолчанию"
      subtitle={
        single
          ? single.name
          : pharmacists?.length
            ? `Выбрано фармацевтов: ${pharmacists.length}`
            : undefined
      }
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={change.isPending || massChange.isPending} onClick={submit}>
            Сохранить
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {error}
          </div>
        )}
        <Field label="Формат">
          <Select
            value={format}
            onChange={(value) => setFormat(value as TrainingFormat)}
            options={Object.entries(FORMAT_LABEL).map(([value, label]) => ({ value, label }))}
          />
        </Field>
        <Field label="Причина изменения">
          <Input
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Например: новый график обучения"
          />
        </Field>
        {current && (
          <div className="rounded-lg bg-paper-hover px-3 py-2 text-[12px] text-ink-600">
            Сейчас: <b>{FORMAT_LABEL[current.defaultFormat]}</b> · {current.changedByName} ·{' '}
            {dateTime(current.validFrom)}
            {current.reason && <div className="mt-1">{current.reason}</div>}
          </div>
        )}
        {single && (
          <div>
            <div className="mb-2 text-[11px] font-bold uppercase text-ink-500">
              История изменений
            </div>
            {history.isLoading ? (
              <div className="text-[12px] text-ink-500">Загружаем историю…</div>
            ) : history.data?.length ? (
              <div className="max-h-44 divide-y divide-ink-100 overflow-auto rounded-lg border border-ink-100">
                {history.data.map((row) => (
                  <div key={row.id} className="px-3 py-2 text-[12px]">
                    <div className="font-bold text-ink-800">{FORMAT_LABEL[row.defaultFormat]}</div>
                    <div className="text-ink-500">
                      {dateTime(row.validFrom)} · {row.changedByName}
                    </div>
                    {row.reason && <div className="mt-0.5 text-ink-600">{row.reason}</div>}
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-[12px] text-ink-500">Изменений ещё не было</div>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
