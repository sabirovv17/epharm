// Витрина / каталог — ТЗ Module 3 ↔ HQ. Read-only список реального каталога товаров
// из Medusa-витрины (через бэкенд-прокси /api/admin/storefront/products). HQ видит тот
// же каталог, что фармацевт в приложении. Поиск и пагинация — серверные.

import { useEffect, useState } from 'react'
import { Button, Empty, PageHeader, SearchInput } from '@/ui'
import { IconBox } from '@/ui/icons'
import { useStorefront } from '@/lib/queries/storefront'
import { describeError } from '@/lib/describeError'
import { formatKzt } from '@/mocks/fixtures'
import { useT } from '@/i18n'
import type { StorefrontProductDto } from '@/lib/api-types'

const PAGE = 50

export default function StorefrontPage() {
  const t = useT()
  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)

  // Дебаунс поиска (300мс) + сброс на первую страницу при новом запросе.
  useEffect(() => {
    const id = setTimeout(() => {
      setQ(qInput)
      setPage(0)
    }, 300)
    return () => clearTimeout(id)
  }, [qInput])

  const { data, isLoading, isError, error, refetch, isFetching } = useStorefront(
    q,
    page * PAGE,
    PAGE,
  )
  const items = data?.items ?? []
  const total = data?.total ?? 0
  const hasData = items.length > 0
  const canPrev = page > 0
  const canNext = (page + 1) * PAGE < total

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title={t('page.storefront.title')} subtitle={t('page.storefront.subtitle')} />

      <div className="card flex min-h-[480px] flex-col">
        <div className="hairline flex flex-wrap items-center gap-3 border-b px-5 py-3">
          <SearchInput
            value={qInput}
            onChange={setQInput}
            placeholder={t('sf.search')}
            className="!h-9 !w-[320px] flex-none"
          />
          {total > 0 && (
            <span className="text-[12px] font-semibold text-ink-500">
              {t('sf.total', { n: total })}
            </span>
          )}
        </div>

        <div className="scrollbar-thin flex-1 overflow-auto">
          {isLoading && !hasData ? (
            <Empty
              title={t('sf.loading')}
              body={t('common.connecting')}
              icon={<IconBox size={26} />}
            />
          ) : isError && !hasData ? (
            <Empty
              title={t('common.loadFailed')}
              body={describeError(error)}
              icon={<IconBox size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : items.length === 0 ? (
            <Empty
              title={q ? t('common.notFound') : t('sf.empty')}
              body={q ? t('common.notFoundBody') : t('sf.emptyBody')}
              icon={<IconBox size={26} />}
            />
          ) : (
            <table className="w-full text-[13px]" data-testid="storefront-table">
              <thead className="hairline border-b">
                <tr className="text-left text-[11px] font-bold uppercase tracking-[0.06em] text-ink-500">
                  <th className="px-5 py-2.5">{t('sf.thName')}</th>
                  <th className="px-3 py-2.5">{t('sf.thBrand')}</th>
                  <th className="px-3 py-2.5">{t('sf.thMnn')}</th>
                  <th className="px-3 py-2.5">{t('sf.thRx')}</th>
                  <th className="px-3 py-2.5">{t('sf.thCategory')}</th>
                  <th className="px-5 py-2.5 text-right">{t('sf.thPrice')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100">
                {items.map((p) => (
                  <tr key={p.id} className="hover:bg-paper-hover" data-testid={`sf-row-${p.id}`}>
                    <td className="px-5 py-2.5">
                      <div className="font-extrabold text-ink-900">{p.name}</div>
                      {p.barcode && <div className="num text-[11px] text-ink-400">{p.barcode}</div>}
                    </td>
                    <td className="px-3 py-2.5 text-ink-700">{p.brand ?? '—'}</td>
                    <td className="px-3 py-2.5 text-ink-700">{p.mnn ?? '—'}</td>
                    <td className="px-3 py-2.5">
                      {p.rxOtc ? (
                        <span
                          className={`chip ${p.rxOtc.toLowerCase() === 'rx' ? 'chip-amber' : 'chip-green'}`}
                        >
                          {p.rxOtc.toLowerCase() === 'rx' ? t('sf.rx') : t('sf.otc')}
                        </span>
                      ) : (
                        <span className="text-ink-400">—</span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 text-ink-700">{p.category ?? '—'}</td>
                    <td className="num px-5 py-2.5 text-right font-semibold">
                      <PriceCell product={p} fallback={t('sf.priceNa')} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {(canPrev || canNext) && (
          <div className="hairline flex items-center justify-between border-t px-5 py-3 text-[12px]">
            <Button
              variant="outline"
              disabled={!canPrev || isFetching}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              {t('sf.prev')}
            </Button>
            <span className="font-semibold text-ink-500">{t('sf.page', { n: page + 1 })}</span>
            <Button
              variant="outline"
              disabled={!canNext || isFetching}
              onClick={() => setPage((p) => p + 1)}
            >
              {t('sf.next')}
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

function PriceCell({ product, fallback }: { product: StorefrontProductDto; fallback: string }) {
  if (product.price == null) return <span className="text-ink-400">{fallback}</span>
  const hasRange =
    product.priceMin != null &&
    product.priceMax != null &&
    product.priceMin > 0 &&
    product.priceMax > 0 &&
    product.priceMin !== product.priceMax

  return (
    <div className="flex flex-col items-end gap-0.5">
      <span>{formatKzt(product.price)}</span>
      {hasRange && (
        <span className="text-[11px] font-semibold text-ink-400">
          {formatKzt(product.priceMin!)}–{formatKzt(product.priceMax!)}
        </span>
      )}
    </div>
  )
}
