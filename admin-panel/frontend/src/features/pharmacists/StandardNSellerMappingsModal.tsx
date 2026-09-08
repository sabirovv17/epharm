import { useMemo, useState } from 'react'
import { Button, Empty, Modal, Select, useToast } from '@/ui'
import { IconUsers } from '@/ui/icons'
import type { PharmacistDto, UnmappedPosmSellerDto } from '@/lib/api-types'
import {
  useRevokeStandardNPharmacistMapping,
  useStandardNPharmacistMappings,
  useUnmappedStandardNSellers,
  useUpsertStandardNPharmacistMapping,
} from '@/lib/queries/pharmacists'
import { describeError } from '@/lib/describeError'

export function StandardNSellerMappingsModal({
  pharmacists,
  onClose,
}: {
  pharmacists: PharmacistDto[]
  onClose: () => void
}) {
  const toast = useToast()
  const mappings = useStandardNPharmacistMappings()
  const unmapped = useUnmappedStandardNSellers()
  const upsert = useUpsertStandardNPharmacistMapping()
  const revoke = useRevokeStandardNPharmacistMapping()
  const [selected, setSelected] = useState<Record<string, string>>({})
  const activeByPharmacy = useMemo(() => {
    const grouped = new Map<string, PharmacistDto[]>()
    pharmacists
      .filter((pharmacist) => pharmacist.status === 'active' && pharmacist.pharmacyId)
      .forEach((pharmacist) => {
        grouped.set(pharmacist.pharmacyId, [
          ...(grouped.get(pharmacist.pharmacyId) ?? []),
          pharmacist,
        ])
      })
    return grouped
  }, [pharmacists])

  const save = (seller: UnmappedPosmSellerDto) => {
    const key = sellerKey(seller)
    const pharmacistId = selected[key]
    if (!pharmacistId) return
    upsert.mutate(
      {
        pharmacyId: seller.pharmacyId,
        externalUserId: seller.externalUserId,
        externalUserName: seller.externalUserName,
        pharmacistId,
      },
      {
        onSuccess: () => toast.push(`USER_ID ${seller.externalUserId} сопоставлен`),
        onError: (error) => toast.push(describeError(error)),
      },
    )
  }

  return (
    <Modal
      open
      onClose={onClose}
      title="Продавцы Standard-N"
      subtitle="Явное сопоставление действует только внутри выбранной аптеки и только для активного фармацевта."
      width={980}
      footer={<Button onClick={onClose}>Закрыть</Button>}
    >
      <div className="flex flex-col gap-6">
        <section>
          <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">
            Требуют сопоставления
          </h3>
          {unmapped.isLoading ? (
            <div className="py-6 text-center text-[13px] text-ink-500">Загружаем продажи…</div>
          ) : unmapped.isError ? (
            <ErrorMessage error={unmapped.error} />
          ) : unmapped.data?.length ? (
            <div className="max-h-72 overflow-auto rounded-lg border border-ink-100">
              <table className="w-full text-[12px]">
                <thead className="sticky top-0 bg-white text-left text-[10px] font-bold uppercase text-ink-500">
                  <tr>
                    <th className="px-3 py-2">Standard-N</th>
                    <th className="px-3 py-2">Аптека</th>
                    <th className="px-3 py-2">Продаж</th>
                    <th className="px-3 py-2">Фармацевт ePharm</th>
                    <th className="px-3 py-2" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-ink-100">
                  {unmapped.data.map((seller) => {
                    const key = sellerKey(seller)
                    const candidates = activeByPharmacy.get(seller.pharmacyId) ?? []
                    return (
                      <tr key={key}>
                        <td className="px-3 py-2.5">
                          <b className="block text-ink-800">USER_ID {seller.externalUserId}</b>
                          <span className="text-[11px] text-ink-500">
                            {seller.externalUserName || 'Имя не передано'}
                          </span>
                        </td>
                        <td className="px-3 py-2.5">{seller.pharmacyName}</td>
                        <td className="num px-3 py-2.5">{seller.salesCount}</td>
                        <td className="min-w-56 px-3 py-2.5">
                          <Select
                            value={selected[key] || undefined}
                            onChange={(value) =>
                              setSelected((current) => ({ ...current, [key]: value }))
                            }
                            options={candidates.map((pharmacist) => ({
                              value: pharmacist.id,
                              label: pharmacist.name,
                            }))}
                            placeholder={
                              candidates.length ? 'Выберите фармацевта' : 'Нет активных профилей'
                            }
                          />
                        </td>
                        <td className="px-3 py-2.5 text-right">
                          <Button
                            size="sm"
                            disabled={!selected[key] || upsert.isPending}
                            onClick={() => save(seller)}
                          >
                            Связать
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <Empty
              title="Все продавцы сопоставлены"
              body="Новых продаж с неизвестным Standard-N USER_ID нет."
              icon={<IconUsers size={24} />}
            />
          )}
        </section>

        <section>
          <h3 className="mb-2 text-[11px] font-bold uppercase text-ink-500">Активные правила</h3>
          {mappings.isLoading ? (
            <div className="py-5 text-center text-[13px] text-ink-500">Загружаем правила…</div>
          ) : mappings.isError ? (
            <ErrorMessage error={mappings.error} />
          ) : mappings.data?.length ? (
            <div className="max-h-56 overflow-auto rounded-lg border border-ink-100">
              <table className="w-full text-[12px]">
                <tbody className="divide-y divide-ink-100">
                  {mappings.data.map((mapping) => (
                    <tr key={mapping.id}>
                      <td className="px-3 py-2.5 font-bold text-ink-800">
                        USER_ID {mapping.externalUserId}
                        {mapping.externalUserName && (
                          <span className="ml-2 font-normal text-ink-500">
                            {mapping.externalUserName}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2.5">{mapping.pharmacyName}</td>
                      <td className="px-3 py-2.5">{mapping.pharmacistName}</td>
                      <td className="px-3 py-2.5 text-right">
                        <button
                          type="button"
                          disabled={revoke.isPending}
                          onClick={() => {
                            if (
                              !window.confirm(
                                `Отключить USER_ID ${mapping.externalUserId} → ${mapping.pharmacistName}? Новые продажи перестанут привязываться к этому профилю.`,
                              )
                            ) {
                              return
                            }
                            revoke.mutate(mapping.id, {
                              onSuccess: () => toast.push('Сопоставление отключено'),
                              onError: (error) => toast.push(describeError(error)),
                            })
                          }}
                          className="font-bold text-accent-danger hover:underline disabled:opacity-50"
                        >
                          Отключить
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="rounded-lg border border-ink-100 px-4 py-5 text-center text-[12px] text-ink-500">
              Правила ещё не созданы
            </div>
          )}
        </section>
      </div>
    </Modal>
  )
}

function sellerKey(seller: UnmappedPosmSellerDto) {
  return `${seller.pharmacyId}:${seller.externalUserId}`
}

function ErrorMessage({ error }: { error: unknown }) {
  return (
    <div className="rounded-lg bg-surface-danger px-4 py-3 text-[13px] font-semibold text-accent-danger">
      {describeError(error)}
    </div>
  )
}
