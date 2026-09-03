import { useMemo, useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  Link2,
  MonitorSmartphone,
  PackageCheck,
  RefreshCw,
} from 'lucide-react'
import {
  Button,
  Empty,
  Field,
  Input,
  Modal,
  PageHeader,
  SearchInput,
  Tabs,
  useToast,
  type TabItem,
} from '@/ui'
import type {
  FulfillmentAction,
  FulfillmentOrderDto,
  FulfillmentPharmacyLinkRequest,
  FulfillmentStatus,
  PharmacyDto,
} from '@/lib/api-types'
import { describeError } from '@/lib/describeError'
import { usePharmacies } from '@/lib/queries/pharmacies'
import {
  useAssignFulfillmentOrder,
  useFulfillmentAction,
  useFulfillmentDevices,
  useFulfillmentFeatureStatus,
  useFulfillmentLinks,
  useFulfillmentOrders,
  useRevokeFulfillmentDevice,
  useSaveFulfillmentLink,
} from '@/lib/queries/fulfillment'

type PageTab = 'orders' | 'links' | 'devices'
type StatusFilter = '' | FulfillmentStatus

const PAGE_SIZE = 50
const STATUS_LABEL: Record<FulfillmentStatus, string> = {
  submitted: 'Новый',
  assembling: 'Собирается',
  ready: 'Готов к выдаче',
  completed: 'Выдан',
  cancelled: 'Отменён',
}

function money(value: number, currency = 'KZT') {
  return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value)} ${currency === 'KZT' ? '₸' : currency}`
}

function dateTime(value: string | null | undefined) {
  if (!value) return '—'
  const valueDate = new Date(value)
  return Number.isNaN(valueDate.getTime()) ? '—' : valueDate.toLocaleString('ru-RU')
}

function paymentLabel(order: FulfillmentOrderDto) {
  if (order.demo) return 'Демо, без оплаты'
  const method: Record<string, string> = {
    cash: 'Наличные',
    card: 'Карта',
    kaspi: 'Kaspi',
    halyk: 'Halyk',
  }
  const state: Record<string, string> = {
    paid: 'оплачено',
    cash_collected: 'наличные получены',
    pending: 'ожидает оплаты',
  }
  return `${method[order.paymentMethod] ?? order.paymentMethod} · ${state[order.paymentStatus] ?? order.paymentStatus}`
}

export default function FulfillmentPage() {
  const [tab, setTab] = useState<PageTab>('orders')
  const statusQ = useFulfillmentFeatureStatus()
  const tabs: TabItem<PageTab>[] = [
    { value: 'orders', label: 'Заказы' },
    { value: 'links', label: 'Связи аптек' },
    { value: 'devices', label: 'Кассы' },
  ]

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Интернет-заказы"
        subtitle="Очередь сборки и выдачи заказов витрины. Статусы синхронизируются с сайтом автоматически."
      />

      {statusQ.data && !statusQ.data.enabled && (
        <div className="flex items-start gap-3 rounded-md border border-accent-warning/35 bg-accent-warning/10 px-4 py-3 text-[13px] text-ink-700">
          <AlertTriangle className="mt-0.5 flex-none text-accent-warning" size={18} />
          <div>
            <div className="font-extrabold text-ink-900">Боевой поток пока выключен</div>
            <div className="mt-0.5">
              Можно подготовить точные связи аптек. Новые заказы и действия касс начнут работать
              после включения серверного флага.
            </div>
          </div>
        </div>
      )}

      <div className="card min-h-[520px] overflow-hidden">
        <div className="px-5 pt-3">
          <Tabs items={tabs} value={tab} onChange={setTab} />
        </div>
        {tab === 'orders' && <OrdersPanel />}
        {tab === 'links' && <LinksPanel />}
        {tab === 'devices' && (
          <DevicesPanel registrationEnabled={statusQ.data?.deviceRegistrationEnabled ?? false} />
        )}
      </div>
    </div>
  )
}

function OrdersPanel() {
  const toast = useToast()
  const [status, setStatus] = useState<StatusFilter>('')
  const [unassigned, setUnassigned] = useState(false)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<FulfillmentOrderDto | null>(null)
  const filter = useMemo(
    () => ({
      status: status || undefined,
      unassigned: unassigned || undefined,
      offset: page * PAGE_SIZE,
      limit: PAGE_SIZE,
    }),
    [page, status, unassigned],
  )
  const ordersQ = useFulfillmentOrders(filter)
  const orders = useMemo(() => ordersQ.data?.items ?? [], [ordersQ.data?.items])
  const selectedOrder = selected
    ? (orders.find((order) => order.orderId === selected.orderId) ?? selected)
    : null

  return (
    <div>
      <div className="hairline flex flex-wrap items-center gap-3 border-b px-5 py-3">
        <label className="text-[12px] font-semibold text-ink-600">
          <span className="mr-2">Статус</span>
          <select
            className="inp !h-9 !w-[190px]"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as StatusFilter)
              setPage(0)
            }}
            aria-label="Фильтр статуса заказа"
          >
            <option value="">Все статусы</option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex h-9 cursor-pointer items-center gap-2 rounded-md border border-ink-200 bg-white px-3 text-[12px] font-semibold text-ink-700">
          <input
            type="checkbox"
            checked={unassigned}
            onChange={(event) => {
              setUnassigned(event.target.checked)
              setPage(0)
            }}
          />
          Только без назначенной аптеки
        </label>
        <Button
          variant="ghost"
          size="sm"
          leading={<RefreshCw size={15} />}
          onClick={() => ordersQ.refetch()}
          disabled={ordersQ.isFetching}
        >
          Обновить
        </Button>
        <span className="ml-auto text-[11px] text-ink-400">Обновление каждые 10 секунд</span>
      </div>

      {ordersQ.isError && orders.length === 0 ? (
        <Empty
          title="Не удалось загрузить заказы"
          body={describeError(ordersQ.error)}
          action={<Button onClick={() => ordersQ.refetch()}>Повторить</Button>}
          icon={<PackageCheck size={26} />}
        />
      ) : ordersQ.isLoading && orders.length === 0 ? (
        <Empty
          title="Загружаем очередь"
          body="Получаем актуальные статусы заказов."
          icon={<PackageCheck size={26} />}
        />
      ) : orders.length === 0 ? (
        <Empty
          title="Заказов по фильтру нет"
          body="Новые заказы появятся здесь после синхронизации витрины."
          icon={<PackageCheck size={26} />}
        />
      ) : (
        <div className="scrollbar-thin overflow-auto">
          <table
            className="w-full min-w-[1050px] text-[13px]"
            data-testid="fulfillment-orders-table"
          >
            <thead className="hairline border-b text-left text-[11px] font-bold uppercase text-ink-500">
              <tr>
                <th className="px-5 py-2.5">Заказ</th>
                <th className="px-3 py-2.5">Аптека</th>
                <th className="px-3 py-2.5">Создан</th>
                <th className="px-3 py-2.5">Оплата</th>
                <th className="px-3 py-2.5">Статус</th>
                <th className="px-5 py-2.5 text-right">Сумма</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {orders.map((order) => (
                <tr
                  key={order.orderId}
                  className="cursor-pointer hover:bg-paper-hover"
                  onClick={() => setSelected(order)}
                >
                  <td className="px-5 py-3">
                    <div className="font-extrabold text-ink-900">{order.number}</div>
                    <div className="mt-0.5 max-w-[220px] truncate font-mono text-[10px] text-ink-400">
                      {order.orderId}
                    </div>
                  </td>
                  <td className="px-3 py-3">
                    <div
                      className={
                        order.pharmacyId
                          ? 'font-semibold text-ink-800'
                          : 'font-bold text-accent-danger'
                      }
                    >
                      {order.pharmacyName ?? 'Не назначена'}
                    </div>
                    <div className="mt-0.5 text-[11px] text-ink-400">
                      {order.pharmacyAddress ?? order.pharmacyExternalId}
                    </div>
                  </td>
                  <td className="px-3 py-3 text-ink-600">{dateTime(order.createdAt)}</td>
                  <td className="px-3 py-3 text-ink-600">{paymentLabel(order)}</td>
                  <td className="px-3 py-3">
                    <OrderStatus status={order.status} />
                  </td>
                  <td className="px-5 py-3 text-right font-extrabold text-ink-900">
                    {money(order.total, order.currency)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="hairline flex items-center justify-between border-t px-5 py-3">
        <span className="text-[12px] text-ink-500">Страница {page + 1}</span>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            Назад
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!ordersQ.data?.hasMore}
            onClick={() => setPage((value) => value + 1)}
          >
            Далее
          </Button>
        </div>
      </div>

      <OrderModal
        key={selectedOrder ? `${selectedOrder.orderId}-${selectedOrder.version}` : 'closed'}
        order={selectedOrder}
        onClose={() => setSelected(null)}
        onChange={setSelected}
        onError={(error) => toast.push(describeError(error), { kind: 'error' })}
      />
    </div>
  )
}

function OrderStatus({ status }: { status: FulfillmentStatus }) {
  const cls =
    status === 'ready' || status === 'completed'
      ? 'bg-accent-success/10 text-accent-success'
      : status === 'cancelled'
        ? 'bg-accent-danger/10 text-accent-danger'
        : status === 'assembling'
          ? 'bg-accent-warning/10 text-ink-800'
          : 'bg-brand-green-100 text-brand-green-700'
  return (
    <span className={`inline-flex rounded-full px-2.5 py-1 text-[11px] font-bold ${cls}`}>
      {STATUS_LABEL[status]}
    </span>
  )
}

function OrderModal({
  order,
  onClose,
  onChange,
  onError,
}: {
  order: FulfillmentOrderDto | null
  onClose: () => void
  onChange: (order: FulfillmentOrderDto) => void
  onError: (error: unknown) => void
}) {
  const pharmaciesQ = usePharmacies()
  const action = useFulfillmentAction()
  const assign = useAssignFulfillmentOrder()
  const [pharmacyId, setPharmacyId] = useState('')
  const [pickupCode, setPickupCode] = useState('')
  const [cashCollected, setCashCollected] = useState(false)
  const [reason, setReason] = useState('')

  if (!order) return null
  const busy = action.isPending || assign.isPending
  const cashConfirmationRequired =
    !order.demo &&
    order.paymentMethod === 'cash' &&
    !['paid', 'cash_collected'].includes(order.paymentStatus)
  const execute = (actionName: FulfillmentAction) => {
    if (
      actionName === 'cancel' &&
      !window.confirm(`Отменить заказ ${order.number}? Это действие нельзя отменить.`)
    )
      return
    action.mutate(
      {
        orderId: order.orderId,
        request: {
          action: actionName,
          expectedVersion: order.version,
          code: actionName === 'issue' ? pickupCode : undefined,
          cashCollected: actionName === 'issue' ? cashCollected : undefined,
          reason: actionName === 'cancel' ? reason.trim() : undefined,
        },
      },
      { onSuccess: onChange, onError },
    )
  }

  return (
    <Modal
      open
      title={`Заказ ${order.number}`}
      subtitle={`Версия ${order.version} · ${dateTime(order.updatedAt)}`}
      onClose={onClose}
      width={780}
    >
      <div className="grid gap-5 md:grid-cols-[1fr_260px]">
        <div>
          <div className="mb-3 flex items-center justify-between">
            <OrderStatus status={order.status} />
            <span className="text-[12px] text-ink-500">{paymentLabel(order)}</span>
          </div>
          <div className="divide-y divide-ink-100 border-y border-ink-100">
            {order.lines.map((line, index) => (
              <div
                key={`${line.productId}-${index}`}
                className="grid grid-cols-[1fr_auto] gap-4 py-3 text-[13px]"
              >
                <div>
                  <div className="font-bold text-ink-900">{line.title}</div>
                  <div className="mt-0.5 font-mono text-[10px] text-ink-400">
                    {line.sku || line.productId}
                  </div>
                </div>
                <div className="text-right">
                  {line.unitPrice == null ? (
                    <div className="text-ink-500">Цена не указана</div>
                  ) : (
                    <>
                      <div>
                        {line.quantity} × {money(line.unitPrice)}
                      </div>
                      <div className="font-extrabold">{money(line.quantity * line.unitPrice)}</div>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
          <div className="mt-3 text-right text-[18px] font-extrabold">
            Итого: {money(order.total, order.currency)}
          </div>
        </div>

        <div className="space-y-4 border-l border-ink-100 pl-5">
          {!order.pharmacyId && (
            <div>
              <Field label="Назначить аптеку">
                <select
                  className="inp"
                  value={pharmacyId}
                  onChange={(event) => setPharmacyId(event.target.value)}
                >
                  <option value="">Выберите аптеку</option>
                  {(pharmaciesQ.data ?? []).map((pharmacy) => (
                    <option key={pharmacy.id} value={pharmacy.id}>
                      {pharmacy.name} · {pharmacy.addr}
                    </option>
                  ))}
                </select>
              </Field>
              <Button
                className="mt-2 w-full"
                disabled={!pharmacyId || busy}
                onClick={() =>
                  assign.mutate(
                    {
                      orderId: order.orderId,
                      request: { pharmacyId, expectedVersion: order.version },
                    },
                    { onSuccess: onChange, onError },
                  )
                }
              >
                Назначить
              </Button>
            </div>
          )}

          {order.status === 'submitted' && (
            <Button
              className="w-full"
              disabled={busy || !order.pharmacyId}
              onClick={() => execute('assemble')}
            >
              Начать сборку
            </Button>
          )}
          {order.status === 'assembling' && (
            <Button className="w-full" disabled={busy} onClick={() => execute('ready')}>
              Заказ собран
            </Button>
          )}
          {order.status === 'ready' && (
            <div className="space-y-2">
              <Field label="Код выдачи">
                <Input
                  inputMode="numeric"
                  maxLength={6}
                  value={pickupCode}
                  onChange={(event) => setPickupCode(event.target.value.replace(/\D/g, ''))}
                  placeholder="6 цифр"
                />
              </Field>
              {cashConfirmationRequired && (
                <label className="flex items-start gap-2 text-[12px] font-semibold text-ink-700">
                  <input
                    className="mt-0.5"
                    type="checkbox"
                    checked={cashCollected}
                    onChange={(event) => setCashCollected(event.target.checked)}
                  />
                  Наличные получены полностью
                </label>
              )}
              <Button
                className="w-full"
                disabled={
                  busy ||
                  pickupCode.length !== 6 ||
                  (cashConfirmationRequired && !cashCollected) ||
                  (order.paymentMethod !== 'cash' && !order.demo && order.paymentStatus !== 'paid')
                }
                onClick={() => execute('issue')}
              >
                Выдать заказ
              </Button>
              {order.pickupLockedUntil && (
                <div className="text-[11px] font-semibold text-accent-danger">
                  Код заблокирован до {dateTime(order.pickupLockedUntil)}
                </div>
              )}
            </div>
          )}

          {['submitted', 'assembling', 'ready'].includes(order.status) && (
            <div className="border-t border-ink-100 pt-4">
              <Field label="Причина отмены">
                <textarea
                  className="inp min-h-[72px] resize-y py-2"
                  maxLength={500}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </Field>
              <Button
                variant="danger"
                className="mt-2 w-full"
                disabled={busy || !reason.trim()}
                onClick={() => execute('cancel')}
              >
                Отменить заказ
              </Button>
            </div>
          )}
        </div>
      </div>
    </Modal>
  )
}

function LinksPanel() {
  const toast = useToast()
  const pharmaciesQ = usePharmacies()
  const linksQ = useFulfillmentLinks()
  const save = useSaveFulfillmentLink()
  const [query, setQuery] = useState('')
  const [form, setForm] = useState<FulfillmentPharmacyLinkRequest>({
    externalId: '',
    pharmacyId: '',
    crmBranchId: '',
    active: true,
  })
  const filtered = (linksQ.data ?? []).filter((link) =>
    `${link.externalId} ${link.pharmacyName} ${link.pharmacyAddress} ${link.crmBranchId ?? ''}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  )

  const submit = () =>
    save.mutate(form, {
      onSuccess: () => {
        toast.push('Связь аптеки сохранена')
        setForm({ externalId: '', pharmacyId: '', crmBranchId: '', active: true })
      },
      onError: (error) => toast.push(describeError(error), { kind: 'error' }),
    })

  return (
    <div className="grid min-h-[480px] md:grid-cols-[340px_1fr]">
      <div className="border-r border-ink-100 p-5">
        <div className="mb-1 text-[15px] font-extrabold text-ink-900">Точное соответствие</div>
        <div className="mb-5 text-[12px] text-ink-500">
          Заказ маршрутизируется только по внешнему ID. Совпадение адреса не используется.
        </div>
        <div className="space-y-3">
          <Field label="ID аптеки на витрине">
            <Input
              value={form.externalId}
              onChange={(event) => setForm((old) => ({ ...old, externalId: event.target.value }))}
              placeholder="Например, ch:84"
            />
          </Field>
          <Field label="Аптека Epharm">
            <select
              className="inp"
              value={form.pharmacyId}
              onChange={(event) => setForm((old) => ({ ...old, pharmacyId: event.target.value }))}
            >
              <option value="">Выберите аптеку</option>
              {(pharmaciesQ.data ?? []).map((pharmacy: PharmacyDto) => (
                <option key={pharmacy.id} value={pharmacy.id}>
                  {pharmacy.name} · {pharmacy.addr}
                </option>
              ))}
            </select>
          </Field>
          <Field label="ID филиала CRM" optional>
            <Input
              value={form.crmBranchId ?? ''}
              onChange={(event) => setForm((old) => ({ ...old, crmBranchId: event.target.value }))}
            />
          </Field>
          <label className="flex items-center gap-2 text-[12px] font-semibold text-ink-700">
            <input
              type="checkbox"
              checked={form.active ?? true}
              onChange={(event) => setForm((old) => ({ ...old, active: event.target.checked }))}
            />
            Связь активна
          </label>
          <Button
            className="w-full"
            leading={<Link2 size={15} />}
            disabled={save.isPending || !form.externalId.trim() || !form.pharmacyId}
            onClick={submit}
          >
            Сохранить связь
          </Button>
        </div>
      </div>
      <div>
        <div className="hairline border-b p-4">
          <SearchInput
            value={query}
            onChange={setQuery}
            placeholder="Поиск по ID, аптеке или адресу"
            className="!h-9 !w-full"
          />
        </div>
        {linksQ.isError ? (
          <Empty
            title="Не удалось загрузить связи"
            body={describeError(linksQ.error)}
            icon={<Link2 size={26} />}
          />
        ) : filtered.length === 0 ? (
          <Empty
            title="Связей пока нет"
            body="Создайте первое точное соответствие аптеки витрины и Epharm."
            icon={<Link2 size={26} />}
          />
        ) : (
          <div className="scrollbar-thin max-h-[520px] overflow-auto divide-y divide-ink-100">
            {filtered.map((link) => (
              <button
                key={link.externalId}
                className="grid w-full grid-cols-[180px_1fr_auto] gap-4 px-5 py-3 text-left hover:bg-paper-hover"
                onClick={() =>
                  setForm({
                    externalId: link.externalId,
                    pharmacyId: link.pharmacyId,
                    crmBranchId: link.crmBranchId,
                    active: link.active,
                  })
                }
              >
                <span className="font-mono text-[11px] font-bold text-ink-700">
                  {link.externalId}
                </span>
                <span>
                  <span className="block text-[13px] font-extrabold text-ink-900">
                    {link.pharmacyName}
                  </span>
                  <span className="block text-[11px] text-ink-500">{link.pharmacyAddress}</span>
                </span>
                <span
                  className={`text-[11px] font-bold ${link.active ? 'text-accent-success' : 'text-ink-400'}`}
                >
                  {link.active ? 'Активна' : 'Выключена'}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function DevicesPanel({ registrationEnabled }: { registrationEnabled: boolean }) {
  const toast = useToast()
  const devicesQ = useFulfillmentDevices()
  const revoke = useRevokeFulfillmentDevice()
  const devices = devicesQ.data ?? []
  return (
    <div>
      <div className="hairline flex items-center justify-between border-b px-5 py-3 text-[12px] text-ink-500">
        <span>
          Токены хранятся только в виде хэша. В интерфейсе видна последняя часть для диагностики.
        </span>
        <span
          className={
            registrationEnabled ? 'font-bold text-accent-success' : 'font-bold text-accent-warning'
          }
        >
          Регистрация {registrationEnabled ? 'открыта' : 'закрыта'}
        </span>
      </div>
      {devicesQ.isError ? (
        <Empty
          title="Не удалось загрузить кассы"
          body={describeError(devicesQ.error)}
          icon={<MonitorSmartphone size={26} />}
        />
      ) : devices.length === 0 ? (
        <Empty
          title="Кассы ещё не зарегистрированы"
          body="Касса появится после первого запуска POSM с модулем заказов."
          icon={<MonitorSmartphone size={26} />}
        />
      ) : (
        <table className="w-full min-w-[850px] text-[13px]">
          <thead className="hairline border-b text-left text-[11px] font-bold uppercase text-ink-500">
            <tr>
              <th className="px-5 py-2.5">Компьютер</th>
              <th className="px-3 py-2.5">Аптека</th>
              <th className="px-3 py-2.5">Последняя связь</th>
              <th className="px-3 py-2.5">Токен</th>
              <th className="px-5 py-2.5" />
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {devices.map((device) => (
              <tr key={device.id}>
                <td className="px-5 py-3 font-extrabold text-ink-900">{device.deviceId}</td>
                <td className="px-3 py-3">
                  <div className="font-semibold">{device.pharmacyName}</div>
                  <div className="font-mono text-[10px] text-ink-400">{device.pharmacyId}</div>
                </td>
                <td className="px-3 py-3 text-ink-600">{dateTime(device.lastSeenAt)}</td>
                <td className="px-3 py-3 font-mono text-[11px]">••••{device.secretHint}</td>
                <td className="px-5 py-3 text-right">
                  {device.active ? (
                    <Button
                      variant="danger"
                      size="sm"
                      disabled={revoke.isPending}
                      onClick={() =>
                        revoke.mutate(device.id, {
                          onSuccess: () => toast.push('Доступ кассы отозван'),
                          onError: (error) => toast.push(describeError(error), { kind: 'error' }),
                        })
                      }
                    >
                      Отозвать
                    </Button>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-[11px] font-bold text-ink-400">
                      <CheckCircle2 size={14} /> Отозван
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
