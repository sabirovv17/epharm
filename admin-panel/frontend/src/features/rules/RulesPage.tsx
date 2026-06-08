// RulesPage — главный экран ТЗ §3.2 (Figure 32).
// Layout: PageHeader → SummaryBar → split (Rules list | RuleBuilder).
// Этап 3.2: данные приходят из backend через TanStack Query.
// Все мутации (toggle status / archive / save / create) — через useUpdateRule/Archive/Create.

import { useMemo, useState } from 'react'
import type { Rule, RuleStatus } from '@/mocks/fixtures'
import {
  Button,
  Empty,
  PageHeader,
  SearchInput,
  Select,
  Tabs,
  Modal,
  useToast,
  type SelectOption,
  type TabItem,
} from '@/ui'
import { IconArchive, IconChevDown, IconPlus, IconRules } from '@/ui/icons'
import { useProductLookup } from '@/lib/queries/catalog'
import { useArchiveRule, useDuplicateRule, useRules, useUpdateRule } from '@/lib/queries/rules'
import { SummaryBar, computeMetrics } from './SummaryBar'
import { RuleRow } from './RuleRow'
import { RuleBuilder } from './RuleBuilder'
import { CreateRuleModal } from './CreateRuleModal'
import { describeError } from '@/lib/describeError'
import { useT } from '@/i18n'
import { ruleSummary } from './lib'

type RulesTab = 'substitution' | 'crosssell' | 'archive'
type StatusFilter = 'all' | 'active' | 'paused'

export default function RulesPage() {
  const t = useT()
  const toast = useToast()
  const productById = useProductLookup()

  const STATUS_OPTIONS: SelectOption[] = [
    { value: 'all', label: t('rules.statusAll') },
    { value: 'active', label: t('rules.statusActive') },
    { value: 'paused', label: t('rules.statusPaused') },
  ]

  const { data: rules = [], isLoading, isError, error, refetch } = useRules()
  const hasData = rules.length > 0
  const showWarningBanner = isError && hasData
  const updateRule = useUpdateRule()
  const archiveRule = useArchiveRule()
  const duplicateRule = useDuplicateRule()

  const [tab, setTab] = useState<RulesTab>('substitution')
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<StatusFilter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [confirmDel, setConfirmDel] = useState<Rule | null>(null)

  const { subst, cross, archive } = useMemo(() => {
    const s: Rule[] = []
    const c: Rule[] = []
    const a: Rule[] = []
    for (const r of rules) {
      if (r.status === 'archived') a.push(r)
      else if (r.type === 'substitution') s.push(r)
      else c.push(r)
    }
    return { subst: s, cross: c, archive: a }
  }, [rules])

  const selected = useMemo(
    () => rules.find((r) => r.id === selectedId) ?? null,
    [rules, selectedId],
  )

  const currentList: Rule[] = tab === 'substitution' ? subst : tab === 'crosssell' ? cross : archive

  const filtered = currentList.filter((r) => {
    if (filter !== 'all' && r.status !== filter && tab !== 'archive') return false
    if (!q) return true
    const trigName =
      r.trigger.kind === 'product'
        ? productById(r.trigger.value as string)?.name
        : (r.trigger.value as string)
    const recName = productById(r.recommend)?.name ?? ''
    return `${trigName ?? ''} ${recName}`.toLowerCase().includes(q.toLowerCase())
  })

  // ── Actions ─────────────────────────────────────────────────────────────

  const handleToggleStatus = (r: Rule) => {
    const next: RuleStatus = r.status === 'active' ? 'paused' : 'active'
    updateRule.mutate(
      { id: r.id, patch: { status: next } },
      {
        onSuccess: () =>
          toast.push(next === 'active' ? t('rules.toggleOn') : t('rules.toggleOff'), {
            action: {
              label: t('rules.undo'),
              onClick: () => updateRule.mutate({ id: r.id, patch: { status: r.status } }),
            },
          }),
        onError: () => toast.push(t('rules.toggleErr')),
      },
    )
  }

  const handleSave = (id: string, patch: Partial<Rule>) => {
    // Из формы прилетает весь Rule (включая id, timestamps) — отбираем только
    // редактируемые поля для PATCH-запроса.
    // advantages: чистим пустые строки и trim — пока юзер набирает в textarea они
    // могут быть (см. BuilderForm), но на сервер всё-таки шлём санитизированный массив.
    const cleanAdvantages = patch.advantages?.map((a) => a.trim()).filter((a) => a.length > 0)
    updateRule.mutate(
      {
        id,
        patch: {
          status: patch.status,
          trigger: patch.trigger,
          recommend: patch.recommend,
          bonus: patch.bonus,
          script: patch.script,
          advantages: cleanAdvantages,
          abTest: patch.abTest,
        },
      },
      {
        onSuccess: () => toast.push(t('rules.saved')),
        onError: () => toast.push(t('rules.saveErr')),
      },
    )
  }

  const handleArchive = (r: Rule) => {
    archiveRule.mutate(r.id, {
      onSuccess: () => {
        toast.push(t('rules.archived'))
        setSelectedId(null)
      },
      onError: () => toast.push(t('rules.archiveErr')),
    })
  }

  const handleDuplicate = (r: Rule) => {
    duplicateRule.mutate(r.id, {
      onSuccess: (copy) => {
        toast.push(t('rules.duplicated'))
        setTab(copy.type === 'substitution' ? 'substitution' : 'crosssell')
        setSelectedId(copy.id)
      },
      onError: () => toast.push(t('rules.dupErr')),
    })
  }

  const metrics = useMemo(() => computeMetrics(subst, cross), [subst, cross])

  const tabItems: TabItem<RulesTab>[] = [
    { value: 'substitution', label: t('rules.tabSubst'), count: subst.length },
    { value: 'crosssell', label: t('rules.tabCross'), count: cross.length },
    { value: 'archive', label: t('rules.tabArchive'), count: archive.length },
  ]

  // Когда переключаем таб:
  //  - есть правила в новом табе → выбираем первое
  //  - нет правил → сбрасываем selectedId, иначе builder продолжит показывать
  //    правило из старого таба пока список слева пуст (confusing UX).
  const onChangeTab = (v: RulesTab) => {
    setTab(v)
    const next = v === 'substitution' ? subst[0] : v === 'crosssell' ? cross[0] : archive[0]
    setSelectedId(next?.id ?? null)
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.rules.title')}
        subtitle={t('page.rules.subtitle')}
        actions={
          <>
            <Button variant="outline" size="md" trailing={<IconChevDown size={12} />}>
              {t('rules.more')}
            </Button>
            <Button
              variant="primary"
              size="md"
              leading={<IconPlus size={14} />}
              onClick={() => setCreateOpen(true)}
            >
              {t('rules.newRule')}
            </Button>
          </>
        }
      />

      <SummaryBar metrics={metrics} />

      {showWarningBanner && (
        <div className="hairline flex items-center gap-3 rounded-xl border bg-accent-warning/10 px-4 py-2 text-[12px] font-semibold text-ink-700">
          <span>{t('rules.staleWarn', { e: describeError(error) })}</span>
          <button
            type="button"
            onClick={() => refetch()}
            className="ml-auto font-bold text-brand-green-700 underline"
          >
            {t('common.retry')}
          </button>
        </div>
      )}

      {/* Main work area: list + builder */}
      <div className="grid gap-4" style={{ gridTemplateColumns: 'minmax(0, 1fr) 480px' }}>
        {/* Left — rules list */}
        <div className="card flex min-h-[640px] flex-col">
          <div className="px-5 pt-4">
            <Tabs<RulesTab>
              value={tab}
              onChange={onChangeTab}
              items={tabItems}
              trailing={
                // flex-none — каждый input сохраняет ширину, не сжимается под давлением.
                // Если контейнер слишком узкий, search+select переносятся на следующую
                // строку (flex-wrap), а не схлопываются до нечитаемого огрызка.
                <div className="flex flex-wrap items-center justify-end gap-2">
                  <SearchInput
                    value={q}
                    onChange={setQ}
                    placeholder={t('rules.searchPh')}
                    className="!h-9 !w-[220px] flex-none"
                  />
                  {tab !== 'archive' && (
                    <Select
                      value={filter}
                      onChange={(v) => setFilter(v as StatusFilter)}
                      options={STATUS_OPTIONS}
                      className="w-[170px] flex-none"
                    />
                  )}
                </div>
              }
            />
          </div>

          <div className="scrollbar-thin flex-1 overflow-auto">
            {isLoading && !hasData ? (
              <Empty
                title={t('rules.loading')}
                body={t('rules.loadingBody')}
                icon={<IconRules size={26} />}
              />
            ) : isError && !hasData ? (
              <Empty
                title={t('rules.errTitle')}
                body={describeError(error)}
                icon={<IconRules size={26} />}
                action={
                  <Button onClick={() => refetch()} variant="primary">
                    {t('common.retry')}
                  </Button>
                }
              />
            ) : filtered.length === 0 ? (
              <Empty
                title={t('rules.emptyTitle')}
                body={tab === 'archive' ? t('rules.emptyArchiveBody') : t('rules.emptyBody')}
                icon={<IconRules size={26} />}
                action={
                  tab !== 'archive' && (
                    <Button leading={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                      {t('rules.newRule')}
                    </Button>
                  )
                }
              />
            ) : (
              <ul className="divide-hairline" data-testid="rules-list">
                {filtered.map((r) => (
                  <RuleRow
                    key={r.id}
                    rule={r}
                    selected={selectedId === r.id}
                    onSelect={() => setSelectedId(r.id)}
                    onDuplicate={handleDuplicate}
                    onArchive={(rule) => setConfirmDel(rule)}
                  />
                ))}
              </ul>
            )}
          </div>
        </div>

        {/* Right — builder panel */}
        <div
          className="card sticky top-[88px] flex min-h-[640px] flex-col self-start"
          data-testid="rule-builder-panel"
        >
          {selected ? (
            <RuleBuilder
              rule={selected}
              onSave={(patch) => handleSave(selected.id, patch)}
              onToggle={() => handleToggleStatus(selected)}
            />
          ) : (
            <Empty
              title={t('rules.selectTitle')}
              body={t('rules.selectBody')}
              icon={<IconRules size={26} />}
            />
          )}
        </div>
      </div>

      {/* Modals */}
      <CreateRuleModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreate={(r) => {
          setTab(r.type === 'substitution' ? 'substitution' : 'crosssell')
          setSelectedId(r.id)
          setCreateOpen(false)
          toast.push(t('rules.created'))
        }}
        onError={(msg) => toast.push(msg)}
      />

      <Modal
        open={!!confirmDel}
        onClose={() => setConfirmDel(null)}
        title={t('rules.confirmArchiveTitle')}
        subtitle={
          confirmDel
            ? t('rules.confirmArchiveSub', { summary: ruleSummary(confirmDel, productById) })
            : ''
        }
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDel(null)}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                if (confirmDel) handleArchive(confirmDel)
                setConfirmDel(null)
              }}
              leading={<IconArchive size={14} />}
            >
              {t('rules.toArchive')}
            </Button>
          </>
        }
      >
        <div />
      </Modal>
    </div>
  )
}
