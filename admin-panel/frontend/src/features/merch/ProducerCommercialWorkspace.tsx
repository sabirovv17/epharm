import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Button, Field, Input, Modal, Select } from '@/ui'
import { saveReport } from '@/features/reports/ledgerReports'
import type { PlacementGateway, Portion } from './placements-api'
import { usePlacementRead } from './usePlacementRead'
import CommercialDraftRecovery from './CommercialDraftRecovery'
import PlacementPlanningSelector from './PlacementPlanningSelector'
import {
  placementTaxonomy,
  planMonths,
  type CommercialActivity,
  type CommercialGateway,
  type CommercialMember,
  type CommercialPlan,
  type CommercialPlanData,
  type CommercialProduct,
  type CommercialSettings,
  type CommercialWorkspace,
  type CommercialCsvPreview,
  type CommercialKzt,
} from './producer-commercial-api'

const roles = [
  { value: 'head', label: 'Руководитель' },
  { value: 'planner', label: 'Планирование' },
  { value: 'finance', label: 'Финансы' },
  { value: 'viewer', label: 'Просмотр' },
]
const activities = [
  { value: 'placement', label: 'Размещение' },
  { value: 'promotion', label: 'Промоакция' },
  { value: 'training', label: 'Обучение' },
  { value: 'motivation', label: 'Мотивация' },
]
const uuid = () => crypto.randomUUID()
const amount = (value: CommercialKzt | null | undefined) => {
  if (value == null) return 'Не подтверждено'
  if ((typeof value === 'number' && !Number.isSafeInteger(value)) || !/^-?\d+$/.test(String(value)))
    return 'Недостаточно точности — скачайте Excel'
  return `${new Intl.NumberFormat('ru-RU').format(BigInt(value))} ₸`
}
const errorText = (error: unknown) => {
  const body = error as {
    status?: number
    response?: { status?: number; data?: { message?: string } }
    message?: string
  }
  const status = body?.status ?? body?.response?.status
  if (status === 413)
    return 'Объём слишком большой. Разделите план или уменьшите фотографии; данные не усечены.'
  if (status === 429) return 'Другой файл ещё обрабатывается. Повторите скачивание позже.'
  if ([401, 403].includes(status ?? 0))
    return 'Нет действующего доступа к этим данным. Обновите сессию.'
  return body?.response?.data?.message ?? body?.message ?? 'Не удалось выполнить действие.'
}
const monthNow = () => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Almaty',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date())
  return `${parts.find((part) => part.type === 'year')!.value}-${parts.find((part) => part.type === 'month')!.value}`
}
function emptyPlan(): CommercialPlanData {
  const month = `${monthNow()}-01`
  return {
    title: '',
    startMonth: month,
    endMonth: month,
    budgetLimit: 0,
    status: 'draft',
    notes: '',
    planningMode: 'products',
    productBriefs: [],
    shelfSelection: [],
    extraActivities: [],
    items: [],
  }
}

export default function ProducerCommercialWorkspace({
  gateway,
  placements,
  ownProducerId,
}: {
  gateway: CommercialGateway
  placements: PlacementGateway
  ownProducerId?: string
}) {
  const [producerId, setProducerId] = useState(ownProducerId ?? '')
  const [offset, setOffset] = useState(0)
  const [editing, setEditing] = useState<{ id: string; value: CommercialPlan | null } | null>(null)
  const [detailId, setDetailId] = useState('')
  const options = usePlacementRead(
    useCallback((signal) => placements.producers(signal), [placements]),
  )
  const own = usePlacementRead(
    useCallback(
      (signal) =>
        gateway.realm === 'producer' ? gateway.workspace(signal) : Promise.resolve(null),
      [gateway],
    ),
  )
  const list = usePlacementRead(
    useCallback(
      (signal) => gateway.list(producerId || undefined, offset, signal),
      [gateway, producerId, offset],
    ),
  )
  const [tab, setTab] = useState('plans')
  const [companyVisited, setCompanyVisited] = useState(false)
  const sectionId = useId()
  const changed = () => {
    list.reload()
    own.reload()
  }
  return (
    <section className="card min-w-0 space-y-4 p-4 sm:p-6" aria-label="Планирование производителя">
      <h2 className="text-xl font-extrabold">Планирование на период</h2>
      <p className="text-sm text-ink-500">
        Товары, места и бюджет на 1–12 месяцев. Оценка бюджета, заявка и оплата учитываются
        отдельно.
      </p>
      {gateway.realm === 'producer' && (
        <nav aria-label="Разделы планирования" className="flex flex-wrap gap-2">
          <Button
            id={`${sectionId}-plans-button`}
            aria-expanded={tab === 'plans'}
            aria-controls={`${sectionId}-plans`}
            variant={tab === 'plans' ? 'primary' : 'outline'}
            onClick={() => setTab('plans')}
          >
            Планы и бюджет
          </Button>
          <Button
            variant={tab === 'company' ? 'primary' : 'outline'}
            id={`${sectionId}-company-button`}
            aria-expanded={tab === 'company'}
            aria-controls={`${sectionId}-company`}
            onClick={() => {
              setCompanyVisited(true)
              setTab('company')
            }}
          >
            Команда и условия
          </Button>
        </nav>
      )}
      {own.error != null && <p role="alert">{errorText(own.error)}</p>}
      <div id={`${sectionId}-company`} hidden={tab !== 'company'}>
        {companyVisited && gateway.realm === 'producer' && own.data && (
          <CompanyWorkspace
            key={own.data.settings.revision}
            value={own.data}
            gateway={gateway}
            onChanged={own.reload}
          />
        )}
        {tab === 'company' && own.pending && <p role="status">Загружаем команду и условия…</p>}
      </div>
      <div id={`${sectionId}-plans`} hidden={tab !== 'plans'} className="min-w-0 space-y-4">
        <div className="flex min-w-0 flex-wrap items-end gap-3 [&>label]:min-w-0 [&>label]:w-full sm:[&>label]:w-auto">
          {gateway.realm === 'admin' && (
            <Field label="Производитель бюджета">
              <Select
                ariaLabel="Производитель бюджета"
                value={producerId}
                options={[
                  { value: '', label: 'Выберите производителя' },
                  ...(options.data ?? [])
                    .filter((p) => p.active)
                    .map((p) => ({ value: p.id, label: p.name ?? 'Компания не указана' })),
                ]}
                onChange={(id) => {
                  setProducerId(id)
                  setOffset(0)
                  setDetailId('')
                }}
              />
            </Field>
          )}
          <Button
            disabled={!producerId || list.error != null}
            onClick={() => setEditing({ id: uuid(), value: null })}
          >
            Новый план на период
          </Button>
          <Button variant="outline" onClick={changed}>
            Обновить планы
          </Button>
        </div>
        {gateway.realm === 'admin' && options.error != null && (
          <div role="alert">
            <p>{errorText(options.error)}</p>
            <Button variant="outline" onClick={options.reload}>
              Повторить загрузку производителей
            </Button>
          </div>
        )}
        {gateway.realm === 'admin' && producerId && (
          <CategorySettings key={producerId} id={producerId} gateway={gateway} />
        )}
        {list.pending && <p role="status">Загружаем бюджетные планы…</p>}
        {list.error != null && <p role="alert">{errorText(list.error)}</p>}
        {list.data && (
          <>
            <div
              className="max-w-full overflow-x-auto"
              role="region"
              aria-label="Бюджетные планы — горизонтальная прокрутка"
              tabIndex={0}
            >
              <table className="w-full min-w-[720px] text-left text-sm">
                <caption className="sr-only">Планы на период, оценка бюджета и заявки</caption>
                <thead>
                  <tr>
                    {['Название', 'Период', 'Статус', 'Оценка', 'Бюджет', 'Заявок', ''].map(
                      (name) => (
                        <th key={name} className="p-2">
                          {name}
                        </th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.map((plan) => (
                    <tr key={plan.id} className="border-t border-paper-line">
                      <td className="p-2">{plan.plan.title}</td>
                      <td className="p-2">
                        {plan.plan.startMonth.slice(0, 7)} — {plan.plan.endMonth.slice(0, 7)}
                      </td>
                      <td className="p-2">
                        {plan.requestIds.length
                          ? 'Отправлен'
                          : plan.plan.status === 'archived'
                            ? 'В архиве'
                            : 'Черновик'}
                      </td>
                      <td className="p-2">{amount(plan.estimatedTotal)}</td>
                      <td className="p-2">{amount(plan.plan.budgetLimit)}</td>
                      <td className="p-2">
                        {plan.requestIds.length}
                        {!!plan.unresolvedRequestIds?.length && (
                          <p className="text-amber-700">
                            Не подтверждено связей: {plan.unresolvedRequestIds.length}
                          </p>
                        )}
                      </td>
                      <td className="p-2">
                        <Button variant="outline" onClick={() => setDetailId(plan.id)}>
                          Открыть план
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {list.data.items.length === 0 && <p className="p-4">Сохранённых планов нет.</p>}
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <Button
                variant="outline"
                disabled={offset === 0}
                onClick={() => setOffset((n) => Math.max(0, n - 50))}
              >
                Предыдущие планы
              </Button>
              <span>Всего: {list.data.total}</span>
              <Button
                variant="outline"
                disabled={offset + 50 >= list.data.total}
                onClick={() => setOffset((n) => n + 50)}
              >
                Следующие планы
              </Button>
            </div>
          </>
        )}
      </div>
      {editing && (
        <CommercialEditor
          key={editing.id}
          id={editing.id}
          initial={editing.value}
          producerId={producerId}
          gateway={gateway}
          placements={placements}
          onClose={() => setEditing(null)}
          onSaved={(plan) => {
            setEditing(null)
            setDetailId(plan.id)
            changed()
          }}
        />
      )}
      {detailId && !editing && (
        <CommercialDetail
          key={detailId}
          id={detailId}
          gateway={gateway}
          onClose={() => setDetailId('')}
          onEdit={(plan) => setEditing({ id: plan.id, value: plan })}
          onChanged={changed}
          onCopied={(plan) => {
            setDetailId(plan.id)
            changed()
          }}
        />
      )}
    </section>
  )
}

function CategorySettings({ id, gateway }: { id: string; gateway: CommercialGateway }) {
  const read = usePlacementRead(
    useCallback((signal) => gateway.categorySettings(id, signal), [gateway, id]),
  )
  const tiers = usePlacementRead(
    useCallback(
      (signal) => (read.data?.canConfigureCategory ? gateway.pricing(signal) : Promise.resolve([])),
      [gateway, read.data?.canConfigureCategory],
    ),
  )
  const [category, setCategory] = useState('')
  const [failure, setFailure] = useState('')
  const [busy, setBusy] = useState(false)
  if (!read.data?.canConfigureCategory) return null
  return (
    <div className="rounded-lg border border-paper-line p-3">
      <p className="text-sm">
        Тарифная категория: {read.data.settings.supplierCategory ?? 'Не подтверждена'}
      </p>
      <div className="mt-2 flex flex-wrap items-end gap-3">
        <Field label="Подтверждённая категория">
          <Select
            value={category}
            onChange={setCategory}
            options={[
              { value: '', label: 'Выберите категорию' },
              ...(tiers.data ?? []).map((tier) => ({ value: tier.category, label: tier.label })),
            ]}
          />
        </Field>
        <Button
          disabled={!category || busy}
          onClick={async () => {
            setBusy(true)
            setFailure('')
            try {
              await gateway.setCategory(id, read.data!.settings.revision, category)
              read.reload()
            } catch (error) {
              setFailure(errorText(error))
            } finally {
              setBusy(false)
            }
          }}
        >
          Подтвердить категорию
        </Button>
      </div>
      {failure && <p role="alert">{failure}</p>}
    </div>
  )
}

function CommercialDetail({
  id,
  gateway,
  onClose,
  onEdit,
  onChanged,
  onCopied,
}: {
  id: string
  gateway: CommercialGateway
  onClose: () => void
  onEdit: (plan: CommercialPlan) => void
  onChanged: () => void
  onCopied: (plan: CommercialPlan) => void
}) {
  const read = usePlacementRead(useCallback((signal) => gateway.view(id, signal), [gateway, id]))
  const [failure, setFailure] = useState('')
  const [busy, setBusy] = useState(false)
  const [copyMonth, setCopyMonth] = useState(monthNow())
  const [exportFrom, setExportFrom] = useState('')
  const [exportTo, setExportTo] = useState('')
  const controller = useRef<AbortController | null>(null)
  const copyRequest = useRef<{ key: string; id: string } | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const act = async (work: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    setFailure('')
    try {
      await work()
    } catch (error) {
      setFailure(errorText(error))
    } finally {
      setBusy(false)
    }
  }
  const plan = read.data
  return (
    <Modal
      open
      width={1000}
      title={plan?.plan.title ?? 'План на период'}
      onClose={() => {
        if (!busy) onClose()
      }}
    >
      {read.pending && <p role="status">Загружаем план на период…</p>}
      {(!!failure || read.error != null) && <p role="alert">{failure || errorText(read.error)}</p>}
      {plan && !failure.startsWith('Нет действующего доступа') && (
        <div className="space-y-4">
          <p>
            {plan.plan.startMonth.slice(0, 7)} — {plan.plan.endMonth.slice(0, 7)} ·{' '}
            {plan.requestIds.length ? 'Отправлен' : 'Черновик'} · ревизия {plan.revision}
          </p>
          <p>
            Предварительная оценка: {amount(plan.estimatedTotal)}. Бюджет:{' '}
            {amount(plan.plan.budgetLimit)}.
          </p>
          {!!plan.unresolvedRequestIds?.length && (
            <p role="status" className="rounded-lg border border-amber-300 p-3">
              Исторические связи заявок требуют проверки HQ: {plan.unresolvedRequestIds.length}.
              Исходные ID сохранены; отсутствующие или неподтверждённые заявки не восстановлены
              автоматически. Помесячные фактические данные неполны, повторная отправка запрещена. В
              Excel неполные итоги оставлены пустыми.
            </p>
          )}
          <p className="text-sm text-ink-500">
            Стоимость заявки пересчитывается по действующему тарифу и доступности. Оплата не
            подключена. Дополнительные активности — бюджетная оценка; учебные события и начисления
            этим планом не создаются.
          </p>
          <p>
            Мест: {plan.plan.shelfSelection?.length ?? 0}; товаров: {plan.plan.productBriefs.length}
            ; бюджетных строк: {plan.plan.items.length}.
          </p>
          <div className="flex flex-wrap gap-2">
            {plan.canEdit && (
              <Button variant="outline" disabled={busy} onClick={() => onEdit(plan)}>
                Продолжить черновик
              </Button>
            )}
            {plan.canSubmit && (
              <Button
                disabled={busy}
                onClick={() =>
                  void act(async () => {
                    await gateway.submit(id, plan.revision)
                    read.reload()
                    onChanged()
                  })
                }
              >
                Отправить весь период на подтверждение
              </Button>
            )}
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <Field label="Excel с месяца">
              <Input
                type="month"
                min={plan.plan.startMonth.slice(0, 7)}
                max={plan.plan.endMonth.slice(0, 7)}
                value={exportFrom || plan.plan.startMonth.slice(0, 7)}
                onChange={(e) => setExportFrom(e.target.value)}
              />
            </Field>
            <Field label="Excel по месяц">
              <Input
                type="month"
                min={plan.plan.startMonth.slice(0, 7)}
                max={plan.plan.endMonth.slice(0, 7)}
                value={exportTo || plan.plan.endMonth.slice(0, 7)}
                onChange={(e) => setExportTo(e.target.value)}
              />
            </Field>
            <Button
              variant="outline"
              disabled={
                !plan.canExport ||
                busy ||
                (exportFrom || plan.plan.startMonth.slice(0, 7)) >
                  (exportTo || plan.plan.endMonth.slice(0, 7))
              }
              onClick={() =>
                void act(async () => {
                  controller.current?.abort()
                  const next = new AbortController()
                  controller.current = next
                  const blob = await gateway.download(id, next.signal, {
                    from: `${exportFrom || plan.plan.startMonth.slice(0, 7)}-01`,
                    to: `${exportTo || plan.plan.endMonth.slice(0, 7)}-01`,
                  })
                  if (!next.signal.aborted) saveReport(blob, 'media-kit.xlsx')
                })
              }
            >
              Медиакит Excel
            </Button>
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="Первый месяц копии">
              <Input
                type="month"
                value={copyMonth}
                onChange={(e) => setCopyMonth(e.target.value)}
              />
            </Field>
            <Button
              variant="outline"
              disabled={busy || !copyMonth || plan.canCopy !== true}
              onClick={() =>
                void act(async () => {
                  const key = `${id}:${plan.revision}:${copyMonth}`
                  if (copyRequest.current?.key !== key) copyRequest.current = { key, id: uuid() }
                  onCopied(
                    await gateway.copy(
                      id,
                      plan.revision,
                      copyRequest.current.id,
                      `${copyMonth}-01`,
                    ),
                  )
                })
              }
            >
              Создать отдельную копию
            </Button>
          </div>
          {plan.monthlyRequests.length > 0 && (
            <div
              className="max-w-full overflow-x-auto"
              role="region"
              aria-label="Помесячные заявки — горизонтальная прокрутка"
              tabIndex={0}
            >
              <h3 className="font-bold">Помесячные заявки и решения</h3>
              <table className="w-full min-w-[680px] text-left text-sm">
                <thead>
                  <tr>
                    {[
                      'Месяц',
                      'Статус',
                      'Сумма заявки',
                      'Подтверждено',
                      'Ожидает решения',
                      'Резерв до',
                    ].map((value) => (
                      <th key={value} className="p-2">
                        {value}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {plan.monthlyRequests.map((request) => (
                    <tr key={request.id}>
                      <td className="p-2">{request.placementMonth.slice(0, 7)}</td>
                      <td className="p-2">{request.status}</td>
                      <td className="p-2">{amount(request.totalAmount)}</td>
                      <td className="p-2">{amount(request.approvedAmount)}</td>
                      <td className="p-2">{amount(request.pendingAmount)}</td>
                      <td className="p-2">
                        {request.heldUntil
                          ? new Date(request.heldUntil).toLocaleString('ru-RU', {
                              timeZone: 'Asia/Almaty',
                            })
                          : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </Modal>
  )
}

function CommercialEditor({
  id,
  initial,
  producerId,
  gateway,
  placements,
  onClose,
  onSaved,
}: {
  id: string
  initial: CommercialPlan | null
  producerId: string
  gateway: CommercialGateway
  placements: PlacementGateway
  onClose: () => void
  onSaved: (plan: CommercialPlan) => void
}) {
  const [draft, setDraft] = useState<CommercialPlanData>(() =>
    initial ? structuredClone(initial.plan) : emptyPlan(),
  )
  const [selectedProduct, setSelectedProduct] = useState('')
  const [branch, setBranch] = useState('')
  const [catalogOffset, setCatalogOffset] = useState(0)
  const [catalogMonth, setCatalogMonth] = useState(draft.startMonth)
  const [csv, setCsv] = useState('')
  const [preview, setPreview] = useState<CommercialCsvPreview | null>(null)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const months = planMonths(draft.startMonth, draft.endMonth)
  const catalog = usePlacementRead(
    useCallback(
      (signal) =>
        placements.catalog(producerId, catalogMonth, catalogOffset, signal, branch || undefined),
      [placements, producerId, catalogMonth, catalogOffset, branch],
    ),
  )
  const patch = (value: Partial<CommercialPlanData>) => setDraft((old) => ({ ...old, ...value }))
  const updateProduct = (index: number, value: CommercialProduct) =>
    setDraft((old) => ({
      ...old,
      productBriefs: old.productBriefs.map((p, i) => (i === index ? value : p)),
      shelfSelection:
        old.shelfSelection?.map((row) => ({
          ...row,
          products: row.products.map((p) => (p.id === value.id ? value : p)),
        })) ?? null,
    }))
  const product = draft.productBriefs.find((p) => p.id === selectedProduct)
  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setFailure('')
    try {
      await action()
    } catch (error) {
      setFailure(errorText(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      width={1200}
      title={initial ? 'Продолжение плана' : 'Новый план на период'}
      onClose={() => {
        if (!busy) onClose()
      }}
    >
      <form
        className="space-y-5"
        onSubmit={(event) => {
          event.preventDefault()
          void run(async () =>
            onSaved(
              await gateway.save(
                id,
                initial?.producerId ?? producerId,
                initial?.revision ?? 0,
                draft,
              ),
            ),
          )
        }}
      >
        {failure && <p role="alert">{failure}</p>}
        <CommercialDraftRecovery
          initial={initial}
          draft={draft}
          gateway={gateway}
          onRecover={setDraft}
        />
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="Название плана">
            <Input
              value={draft.title}
              required
              maxLength={180}
              onChange={(e) => patch({ title: e.target.value })}
            />
          </Field>
          <Field label="Первый месяц">
            <Input
              type="month"
              value={draft.startMonth.slice(0, 7)}
              required
              onChange={(e) => {
                const start = `${e.target.value}-01`
                patch({ startMonth: start })
                setCatalogMonth(start)
              }}
            />
          </Field>
          <Field label="Последний месяц">
            <Input
              type="month"
              value={draft.endMonth.slice(0, 7)}
              required
              onChange={(e) => patch({ endMonth: `${e.target.value}-01` })}
            />
          </Field>
          <Field label="Лимит бюджета, ₸">
            <Input
              inputMode="numeric"
              pattern="[0-9]*"
              value={draft.budgetLimit ?? ''}
              required
              onChange={(e) =>
                patch({ budgetLimit: e.target.value === '' ? null : e.target.value })
              }
            />
          </Field>
        </div>
        {!months.length && (
          <p role="alert">
            Выберите от 1 до 12 полных месяцев. Изменение периода не удаляет ранее выбранные месяцы:
            проверьте строки перед сохранением.
          </p>
        )}
        <Field label="Способ планирования">
          <Select
            value={draft.planningMode}
            onChange={(value) =>
              patch({ planningMode: value as CommercialPlanData['planningMode'] })
            }
            options={[
              { value: 'products', label: 'От товаров' },
              { value: 'shelves', label: 'От мест' },
            ]}
          />
        </Field>
        <div className="space-y-3">
          <h3 className="font-bold">Товары и месяцы</h3>
          {draft.productBriefs.map((p, index) => (
            <div key={p.id} className="rounded-lg border border-paper-line p-3">
              <div className="grid gap-3 sm:grid-cols-4">
                <Field label={`Товар ${index + 1}`}>
                  <Input
                    value={p.productName}
                    maxLength={180}
                    onChange={(e) => updateProduct(index, { ...p, productName: e.target.value })}
                  />
                </Field>
                <Field label={`SKU товара ${index + 1}`}>
                  <Input
                    value={p.sku ?? ''}
                    maxLength={80}
                    onChange={(e) => updateProduct(index, { ...p, sku: e.target.value })}
                  />
                </Field>
                <Field label={`Категория товара ${index + 1}`}>
                  <Select
                    value={p.productCategory}
                    onChange={(value) =>
                      updateProduct(index, { ...p, productCategory: value, productSubcategory: '' })
                    }
                    options={[
                      { value: '', label: 'Выберите категорию' },
                      ...placementTaxonomy.map((item) => ({ value: item.id, label: item.label })),
                    ]}
                  />
                </Field>
                <Field label={`Подкатегория товара ${index + 1}`}>
                  <Select
                    value={p.productSubcategory}
                    onChange={(value) => updateProduct(index, { ...p, productSubcategory: value })}
                    options={[
                      { value: '', label: 'Выберите подкатегорию' },
                      ...(
                        placementTaxonomy.find((item) => item.id === p.productCategory)?.children ??
                        []
                      ).map(([value, label]) => ({ value, label })),
                    ]}
                  />
                </Field>
                <Field label={`Фейсинги товара ${index + 1}`}>
                  <Input
                    type="number"
                    min={1}
                    max={100}
                    value={p.facingCount}
                    onChange={(e) =>
                      updateProduct(index, { ...p, facingCount: Number(e.target.value) })
                    }
                  />
                </Field>
              </div>
              <MonthChecks
                months={months}
                selected={p.activeMonths}
                onChange={(values) => updateProduct(index, { ...p, activeMonths: values })}
                label={`Месяцы товара ${index + 1}`}
              />
              <Button
                type="button"
                variant="ghost"
                onClick={() =>
                  patch({ productBriefs: draft.productBriefs.filter((row) => row.id !== p.id) })
                }
              >
                Удалить товар из списка планирования
              </Button>
            </div>
          ))}
          <Button
            type="button"
            variant="outline"
            disabled={draft.productBriefs.length >= 20}
            onClick={() =>
              patch({
                productBriefs: [
                  ...draft.productBriefs,
                  {
                    id: uuid(),
                    productName: '',
                    sku: '',
                    productCategory: '',
                    productSubcategory: '',
                    facingCount: 1,
                    activeMonths: months,
                    shelfCategories: [],
                  },
                ],
              })
            }
          >
            Добавить товар
          </Button>
        </div>
        <h3 className="font-bold">Выбор мест</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Товар для выбранных мест">
            <Select
              value={selectedProduct}
              onChange={setSelectedProduct}
              options={[
                { value: '', label: 'Выберите товар явно' },
                ...draft.productBriefs.map((p, i) => ({
                  value: p.id,
                  label: p.productName || `Товар ${i + 1}`,
                })),
              ]}
            />
          </Field>
          <Field label="Месяц проверки доступности">
            <Select
              value={catalogMonth}
              onChange={setCatalogMonth}
              options={months.map((value) => ({ value, label: value.slice(0, 7) }))}
            />
          </Field>
          <Field label="Аптека для выбора мест">
            <Select
              value={branch}
              onChange={setBranch}
              options={[
                { value: '', label: 'Выберите аптеку' },
                ...(catalog.data?.branches ?? []).map((b) => ({
                  value: b.id,
                  label: `${b.city} · ${b.name}`,
                })),
              ]}
            />
          </Field>
        </div>
        <PlacementPlanningSelector
          key={`${producerId}:${catalogMonth}:${product?.id ?? ''}`}
          catalog={catalog.data ?? null}
          gateway={placements}
          producerId={producerId}
          month={catalogMonth}
          product={product}
          selected={draft.shelfSelection ?? []}
          branchId={branch}
          onBranch={setBranch}
          onChoose={(rows) => patch({ shelfSelection: [...(draft.shelfSelection ?? []), ...rows] })}
          disabled={busy}
        />
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={catalogOffset === 0}
            onClick={() => {
              setBranch('')
              setCatalogOffset((n) => Math.max(0, n - 50))
            }}
          >
            Предыдущие аптеки
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={!catalog.data || catalogOffset + 50 >= catalog.data.total}
            onClick={() => {
              setBranch('')
              setCatalogOffset((n) => n + 50)
            }}
          >
            Следующие аптеки
          </Button>
        </div>
        {catalog.error != null && <p role="alert">{errorText(catalog.error)}</p>}
        {(catalog.data?.branches ?? [])
          .filter((b) => b.id === branch)
          .map((b) => (
            <div key={b.id} className="grid gap-2 sm:grid-cols-3">
              {b.items.map((place) => (
                <div key={place.shelfId} className="rounded-lg border border-paper-line p-2">
                  <p className="font-bold">{place.code}</p>
                  {(['whole', 'left', 'right'] as Portion[]).map((portion) => {
                    const offer = place[portion]
                    const chosen = draft.shelfSelection?.some(
                      (row) => row.shelfId === place.shelfId && row.portion === portion,
                    )
                    return (
                      offer && (
                        <Button
                          key={portion}
                          type="button"
                          variant={chosen ? 'primary' : 'outline'}
                          disabled={
                            !product ||
                            !product.activeMonths.includes(catalogMonth) ||
                            !offer.available ||
                            offer.price === null ||
                            !b.canCreate
                          }
                          onClick={() => {
                            if (chosen)
                              patch({
                                shelfSelection: draft.shelfSelection!.filter(
                                  (row) =>
                                    !(row.shelfId === place.shelfId && row.portion === portion),
                                ),
                              })
                            else
                              patch({
                                shelfSelection: [
                                  ...(draft.shelfSelection ?? []),
                                  {
                                    id: uuid(),
                                    shelfId: place.shelfId,
                                    pharmacyId: b.id,
                                    portion,
                                    placementMonths: [catalogMonth],
                                    products: [structuredClone(product!)],
                                    price: offer.price,
                                    code: place.code,
                                    pharmacyName: b.name,
                                    city: b.city,
                                    address: b.address,
                                  },
                                ],
                              })
                          }}
                        >
                          {portion === 'whole'
                            ? 'Целиком'
                            : portion === 'left'
                              ? 'Левая половина'
                              : 'Правая половина'}{' '}
                          · {amount(offer.price)}
                        </Button>
                      )
                    )
                  })}
                </div>
              ))}
            </div>
          ))}
        <p className="text-sm text-ink-500">
          Здесь показана доступность выбранного месяца. При отправке весь период перепроверяется
          одной операцией; при конфликте частичной брони не будет.
        </p>
        {(draft.shelfSelection ?? []).map((row, index) => (
          <div
            key={row.id || `${row.shelfId}:${row.portion}`}
            className="rounded-lg border border-paper-line p-3"
          >
            <p className="font-bold">
              {row.pharmacyName ?? row.pharmacyId} · {row.code ?? row.shelfId} · {row.portion}
            </p>
            <p>
              {row.products
                .map((p) => `${p.productName || 'Товар не заполнен'}: ${p.facingCount}`)
                .join('; ')}
            </p>
            <MonthChecks
              months={months}
              selected={row.placementMonths}
              label={`Месяцы места ${index + 1}`}
              onChange={(values) =>
                patch({
                  shelfSelection: draft.shelfSelection!.map((r, i) =>
                    i === index ? { ...r, placementMonths: values } : r,
                  ),
                })
              }
            />
            <Button
              type="button"
              variant="outline"
              disabled={!product || row.products.some((p) => p.id === product.id)}
              onClick={() =>
                patch({
                  shelfSelection: draft.shelfSelection!.map((r, i) =>
                    i === index
                      ? { ...r, products: [...r.products, structuredClone(product!)] }
                      : r,
                  ),
                })
              }
            >
              Добавить выбранный товар на место
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() =>
                patch({ shelfSelection: draft.shelfSelection!.filter((_, i) => i !== index) })
              }
            >
              Убрать место из черновика
            </Button>
          </div>
        ))}
        <details className="rounded-lg border border-paper-line p-3">
          <summary>Импорт мест из CSV с проверкой строк</summary>
          <p className="my-2 text-sm">
            Обязательные колонки: branch_code;shelf_code;month. Укажите явные коды аптеки и полки,
            месяц ГГГГ-ММ. Ни бронь, ни отправка при проверке не создаются.
          </p>
          <div className="mb-2 flex flex-wrap gap-3">
            <Button
              type="button"
              variant="outline"
              onClick={() =>
                saveReport(
                  new Blob(
                    [
                      `\uFEFFкод филиала;код места;месяц\r\nКОД_АПТЕКИ;КОД_МЕСТА;${draft.startMonth.slice(0, 7)}\r\n`,
                    ],
                    { type: 'text/csv;charset=utf-8' },
                  ),
                  'placement-import-template.csv',
                )
              }
            >
              Скачать шаблон CSV
            </Button>
            <label className="text-sm">
              Открыть CSV-файл
              <input
                aria-label="Открыть CSV-файл"
                type="file"
                accept=".csv,text/csv"
                onChange={async (event) => {
                  const file = event.target.files?.[0]
                  if (!file) return
                  if (file.size > 500_000) {
                    setFailure('CSV превышает 500 КБ. Разделите файл; строки не усечены.')
                    return
                  }
                  try {
                    setCsv(await file.text())
                    setPreview(null)
                  } catch {
                    setFailure('Не удалось прочитать CSV-файл.')
                  }
                }}
              />
            </label>
          </div>
          <textarea
            aria-label="CSV мест"
            className="inp min-h-28 w-full"
            value={csv}
            onChange={(e) => {
              setCsv(e.target.value)
              setPreview(null)
            }}
          />
          <Button
            type="button"
            variant="outline"
            disabled={!csv || busy}
            onClick={() => void run(async () => setPreview(await gateway.preview(csv, producerId)))}
          >
            Проверить CSV
          </Button>
          {preview && (
            <>
              <ul>
                {preview.rows.map((row) => (
                  <li key={row.line}>
                    Строка {row.line}: {row.branchCode} / {row.shelfCode} / {row.month ?? '—'} —{' '}
                    {row.error ?? 'Проверено'}
                  </li>
                ))}
              </ul>
              <Button
                type="button"
                disabled={!preview.valid || !product}
                onClick={() => {
                  if (
                    preview.placements.some((row) =>
                      row.placementMonths.some((m) => !product!.activeMonths.includes(m)),
                    )
                  ) {
                    setFailure('Совместите месяцы выбранного товара и CSV перед переносом.')
                    return
                  }
                  patch({
                    shelfSelection: [
                      ...(draft.shelfSelection ?? []),
                      ...preview.placements.map((row) => ({
                        ...row,
                        products: [structuredClone(product!)],
                      })),
                    ],
                  })
                  setPreview(null)
                  setCsv('')
                }}
              >
                Перенести проверенные строки в черновик
              </Button>
            </>
          )}
        </details>
        <h3 className="font-bold">Сохранённые бюджетные строки и дополнительные активности</h3>
        {(['items', 'extraActivities'] as const).map((field) => (
          <div key={field} className="space-y-3">
            {draft[field].map((activity, index) => (
              <ActivityEditor
                key={activity.id ?? `${field}:${index}`}
                activity={activity}
                index={index}
                months={months}
                includePlacement={field === 'items'}
                onChange={(value) =>
                  patch({ [field]: draft[field].map((a, i) => (i === index ? value : a)) })
                }
                onRemove={() => patch({ [field]: draft[field].filter((_, i) => i !== index) })}
              />
            ))}
            <Button
              type="button"
              variant="outline"
              onClick={() =>
                patch({
                  [field]: [
                    ...draft[field],
                    {
                      id: uuid(),
                      productName: '',
                      sku: '',
                      activityType: field === 'items' ? 'placement' : 'promotion',
                      activeMonths: months.slice(0, 1),
                      pharmacyCount: 1,
                      facingCount: 1,
                      peopleCount: 0,
                      unitPrice: null,
                      note: '',
                    },
                  ],
                })
              }
            >
              {field === 'items'
                ? 'Добавить бюджетную строку'
                : 'Добавить дополнительную активность'}
            </Button>
          </div>
        ))}
        <Field label="Комментарий к плану">
          <textarea
            className="inp min-h-20 w-full"
            value={draft.notes ?? ''}
            maxLength={2000}
            onChange={(e) => patch({ notes: e.target.value })}
          />
        </Field>
        <div className="flex flex-wrap gap-3">
          <Button type="submit" disabled={busy || !months.length}>
            {busy ? 'Сохраняем план…' : 'Сохранить черновик плана'}
          </Button>
          <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
            Закрыть без сохранения
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function MonthChecks({
  months,
  selected,
  onChange,
  label,
}: {
  months: string[]
  selected: string[]
  onChange: (values: string[]) => void
  label: string
}) {
  const outside = selected.filter((month) => !months.includes(month))
  return (
    <fieldset className="my-2 flex flex-wrap gap-3">
      <legend className="text-sm font-semibold">{label}</legend>
      {[...months, ...outside].map((month) => (
        <label key={month} className="text-sm">
          <input
            type="checkbox"
            checked={selected.includes(month)}
            onChange={() =>
              onChange(
                selected.includes(month)
                  ? selected.filter((m) => m !== month)
                  : [...selected, month].sort(),
              )
            }
          />{' '}
          {month.slice(0, 7)}
          {outside.includes(month) ? ' · вне периода' : ''}
        </label>
      ))}
    </fieldset>
  )
}

function ActivityEditor({
  activity,
  months,
  onChange,
  onRemove,
  index,
  includePlacement,
}: {
  activity: CommercialActivity
  months: string[]
  onChange: (value: CommercialActivity) => void
  onRemove: () => void
  index: number
  includePlacement: boolean
}) {
  return (
    <div className="rounded-lg border border-paper-line p-3">
      <div className="grid gap-3 sm:grid-cols-4">
        <Field label={`Название активности ${index + 1}`}>
          <Input
            value={activity.productName}
            maxLength={180}
            onChange={(e) => onChange({ ...activity, productName: e.target.value })}
          />
        </Field>
        <Field label="Тип активности">
          <Select
            value={activity.activityType}
            options={activities.filter((item) => includePlacement || item.value !== 'placement')}
            onChange={(value) =>
              onChange({ ...activity, activityType: value as CommercialActivity['activityType'] })
            }
          />
        </Field>
        <Field label="Предварительная цена за единицу, ₸">
          <Input
            inputMode="numeric"
            pattern="[0-9]*"
            value={activity.unitPrice ?? ''}
            onChange={(e) =>
              onChange({ ...activity, unitPrice: e.target.value === '' ? null : e.target.value })
            }
          />
        </Field>
        <Field label="Аптек / событий">
          <Input
            type="number"
            min={1}
            max={10000}
            value={activity.pharmacyCount}
            onChange={(e) => onChange({ ...activity, pharmacyCount: Number(e.target.value) })}
          />
        </Field>
        <Field label="Фейсингов">
          <Input
            type="number"
            min={1}
            max={100}
            value={activity.facingCount}
            onChange={(e) => onChange({ ...activity, facingCount: Number(e.target.value) })}
          />
        </Field>
        <Field label="Участников">
          <Input
            type="number"
            min={0}
            max={100000}
            value={activity.peopleCount}
            onChange={(e) => onChange({ ...activity, peopleCount: Number(e.target.value) })}
          />
        </Field>
      </div>
      <MonthChecks
        months={months}
        selected={activity.activeMonths}
        label={`Месяцы активности ${index + 1}`}
        onChange={(value) => onChange({ ...activity, activeMonths: value })}
      />
      <Button type="button" variant="ghost" onClick={onRemove}>
        Убрать бюджетную строку
      </Button>
    </div>
  )
}

function CompanyWorkspace({
  value,
  gateway,
  onChanged,
}: {
  value: CommercialWorkspace
  gateway: CommercialGateway
  onChanged: () => void
}) {
  const [settings, setSettings] = useState<CommercialSettings>(value.settings)
  const [member, setMember] = useState<CommercialMember>({
    id: uuid(),
    revision: 0,
    fullName: '',
    email: '',
    position: '',
    accessRole: 'planner',
    active: true,
  })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const run = async (work: () => Promise<void>) => {
    setBusy(true)
    setError('')
    try {
      await work()
      onChanged()
    } catch (failure) {
      setError(errorText(failure))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="space-y-4">
      <p className="text-sm">
        Категория: {value.pricing?.label ?? value.settings.supplierCategory ?? 'Не подтверждена HQ'}
        . Фейсинг в месяц: {amount(value.pricing?.facingMonthPrice)}. Назначение категории не меняет
        ваши данные входа.
      </p>
      {!value.pricing && (
        <p role="status">
          Тариф ещё не подтверждён. Сохранение черновиков доступно; отправка товарного плана требует
          явного назначения категории HQ.
        </p>
      )}
      {error && <p role="alert">{error}</p>}
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          void run(async () => {
            await gateway.settings(settings)
          })
        }}
      >
        <Field label="Бюджет на шесть месяцев, ₸">
          <Input
            inputMode="numeric"
            pattern="[0-9]*"
            value={settings.sixMonthBudget ?? ''}
            onChange={(e) =>
              setSettings({
                ...settings,
                sixMonthBudget: e.target.value === '' ? null : e.target.value,
              })
            }
          />
        </Field>
        <Field label="Роль основного контакта">
          <Input
            maxLength={255}
            value={settings.primaryContactRole ?? ''}
            onChange={(e) => setSettings({ ...settings, primaryContactRole: e.target.value })}
          />
        </Field>
        <Button disabled={busy}>Сохранить условия планирования</Button>
      </form>
      <h3 className="font-bold">Команда компании</h3>
      <p className="text-sm text-ink-500">
        Это справочник участников. Добавление записи не создаёт отдельный вход, административный
        аккаунт или подтверждённую личность.
      </p>
      <ul className="space-y-2">
        {value.members.map((item) => (
          <li
            key={item.id}
            className="flex flex-wrap items-center gap-3 rounded-lg border border-paper-line p-3"
          >
            <span>
              {item.fullName} · {item.email} · {item.accessRole} ·{' '}
              {item.active ? 'Активен' : 'Архив'}
            </span>
            <Button variant="outline" disabled={busy} onClick={() => setMember(item)}>
              Изменить участника
            </Button>
          </li>
        ))}
      </ul>
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          void run(async () => {
            await gateway.member(member)
            setMember({
              id: uuid(),
              revision: 0,
              fullName: '',
              email: '',
              position: '',
              accessRole: 'planner',
              active: true,
            })
          })
        }}
      >
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label="Имя участника">
            <Input
              value={member.fullName}
              required
              minLength={2}
              maxLength={160}
              onChange={(e) => setMember({ ...member, fullName: e.target.value })}
            />
          </Field>
          <Field label="Email участника">
            <Input
              type="email"
              value={member.email}
              required
              onChange={(e) => setMember({ ...member, email: e.target.value })}
            />
          </Field>
          <Field label="Должность участника">
            <Input
              value={member.position ?? ''}
              maxLength={255}
              onChange={(e) => setMember({ ...member, position: e.target.value })}
            />
          </Field>
          <Field label="Роль в команде">
            <Select
              value={member.accessRole}
              options={roles}
              onChange={(value) =>
                setMember({ ...member, accessRole: value as CommercialMember['accessRole'] })
              }
            />
          </Field>
        </div>
        <label className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={member.active}
            onChange={(e) => setMember({ ...member, active: e.target.checked })}
          />{' '}
          Участник активен; снятие отметки сохранит его в архиве
        </label>
        <Button disabled={busy}>Сохранить участника</Button>
      </form>
    </div>
  )
}
