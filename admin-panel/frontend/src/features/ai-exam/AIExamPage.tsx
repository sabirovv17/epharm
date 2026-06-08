// AI-Exam — банк вопросов + результаты экзаменов.
// ТЗ §3.4 — диалоговый экзамен с AI после прохождения курса.
// Этап 3.6: банк вопросов на real API (useExamQuestions / useCreateExamQuestion).
// Результаты + сертификаты — Этап 4 (exam-flow), остаются Empty.

import { useState } from 'react'
import {
  Button,
  Empty,
  Field,
  Input,
  Metric,
  Modal,
  PageHeader,
  Select,
  Tabs,
  useToast,
  type TabItem,
} from '@/ui'
import { IconAIExam, IconCheck, IconPlus, IconShield, IconTrash, IconUsers } from '@/ui/icons'
import type { ExamQuestionDto, ExamQuestionKind } from '@/lib/api-types'
import {
  useCreateExamQuestion,
  useDeleteExamQuestion,
  useExamQuestions,
} from '@/lib/queries/aiExam'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type ExamTab = 'bank' | 'results' | 'certificates'

const KIND_KEY: Record<ExamQuestionKind, string> = {
  factual: 'ai.kFactual',
  comparative: 'ai.kComparative',
  scenario: 'ai.kScenario',
}

export default function AIExamPage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<ExamTab>('bank')
  const [createOpen, setCreateOpen] = useState(false)

  const { data: questions = [], isLoading, isError, error, refetch } = useExamQuestions()
  const hasData = questions.length > 0

  const tabs: TabItem<ExamTab>[] = [
    { value: 'bank', label: t('ai.tabBank'), count: questions.length },
    { value: 'results', label: t('ai.tabResults'), count: 0 },
    { value: 'certificates', label: t('ai.tabCerts'), count: 0 },
  ]

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.ai_exam.title')}
        subtitle={t('page.ai_exam.subtitle')}
        actions={
          <Button
            variant="primary"
            size="md"
            leading={<IconPlus size={14} />}
            onClick={() => setCreateOpen(true)}
          >
            {t('ai.newQuestion')}
          </Button>
        }
      />

      <div className="grid grid-cols-4 gap-4">
        <Metric
          label={t('ai.mBank')}
          value={formatNum(questions.length)}
          accent="ink"
          icon={<IconAIExam size={16} />}
        />
        <Metric label={t('ai.mPassed')} value={0} accent="green" icon={<IconCheck size={16} />} />
        <Metric label={t('ai.mCerts')} value={0} accent="amber" icon={<IconShield size={16} />} />
        <Metric
          label={t('ai.mAvgTime')}
          value="—"
          accent="blue"
          icon={<IconUsers size={16} />}
          meta={t('ai.minutes')}
        />
      </div>

      <div className="card flex min-h-[440px] flex-col">
        <div className="px-5 pt-4">
          <Tabs<ExamTab> items={tabs} value={tab} onChange={setTab} />
        </div>
        <div className="flex-1 px-5 pb-5 pt-3">
          {tab === 'bank' &&
            (isLoading && !hasData ? (
              <Empty
                title={t('ai.loading')}
                body={t('common.connecting')}
                icon={<IconAIExam size={26} />}
              />
            ) : isError && !hasData ? (
              <Empty
                title={t('ai.errTitle')}
                body={describeError(error)}
                icon={<IconAIExam size={26} />}
                action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
              />
            ) : questions.length === 0 ? (
              <Empty
                title={t('ai.empty')}
                body={t('ai.emptyBody')}
                icon={<IconAIExam size={26} />}
                action={
                  <Button leading={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                    {t('ai.newQuestion')}
                  </Button>
                }
              />
            ) : (
              <ul className="flex flex-col" data-testid="exam-questions">
                {questions.map((q) => (
                  <QuestionRow key={q.id} q={q} />
                ))}
              </ul>
            ))}
          {tab === 'results' && (
            <Empty
              title={t('ai.resultsEmpty')}
              body={t('ai.resultsBody')}
              icon={<IconCheck size={26} />}
            />
          )}
          {tab === 'certificates' && (
            <Empty
              title={t('ai.certsEmpty')}
              body={t('ai.certsBody')}
              icon={<IconShield size={26} />}
            />
          )}
        </div>
      </div>

      <CreateQuestionModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          toast.push(t('ai.addedToast'))
          setCreateOpen(false)
        }}
      />
    </div>
  )
}

function QuestionRow({ q }: { q: ExamQuestionDto }) {
  const t = useT()
  const toast = useToast()
  const del = useDeleteExamQuestion()

  const handleDelete = () => {
    if (!confirm(t('ai.confirmDelete'))) return
    del.mutate(q.id, {
      onSuccess: () => toast.push(t('ai.deletedToast')),
      onError: () => toast.push(t('ai.deleteErr')),
    })
  }

  return (
    <li
      data-testid={`exam-question-${q.id}`}
      className="hairline flex items-start gap-3 border-b py-3 last:border-b-0"
    >
      <span className="chip chip-ink whitespace-nowrap">{t(KIND_KEY[q.kind])}</span>
      <div className="min-w-0 flex-1">
        <div className="text-[13px] font-bold text-ink-900">{q.prompt}</div>
        <div className="mt-0.5 text-[11px] text-ink-500">
          {q.category || t('ai.noCategory')} · {t('ai.difficulty', { d: q.difficulty })}
          {q.keywords.length > 0 && ` · ${t('ai.keywords', { kw: q.keywords.join(', ') })}`}
        </div>
      </div>
      <button
        type="button"
        onClick={handleDelete}
        disabled={del.isPending}
        aria-label={t('ai.delete')}
        data-testid={`exam-question-delete-${q.id}`}
        className="mt-0.5 flex-none text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
      >
        <IconTrash size={15} />
      </button>
    </li>
  )
}

function CreateQuestionModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const t = useT()
  const KIND_OPTS: { value: ExamQuestionKind; label: string }[] = [
    { value: 'factual', label: t('ai.kFactual') },
    { value: 'comparative', label: t('ai.kComparative') },
    { value: 'scenario', label: t('ai.kScenario') },
  ]
  const create = useCreateExamQuestion()
  const [prompt, setPrompt] = useState('')
  const [kind, setKind] = useState<ExamQuestionKind>('factual')
  const [category, setCategory] = useState('')
  const [keywords, setKeywords] = useState('')
  const [difficulty, setDifficulty] = useState('1')
  const [err, setErr] = useState<string | null>(null)

  const reset = () => {
    setPrompt('')
    setKind('factual')
    setCategory('')
    setKeywords('')
    setDifficulty('1')
    setErr(null)
  }

  const submit = () => {
    if (!prompt.trim()) {
      setErr(t('ai.errPromptReq'))
      return
    }
    create.mutate(
      {
        prompt: prompt.trim(),
        kind,
        category: category.trim(),
        keywords: keywords
          .split(',')
          .map((k) => k.trim())
          .filter(Boolean),
        difficulty: Number(difficulty) || 1,
      },
      {
        onSuccess: () => {
          reset()
          onCreated()
        },
        onError: (e) => setErr(describeError(e)),
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={() => {
        reset()
        onClose()
      }}
      title={t('ai.newQuestion')}
      subtitle={t('ai.modalSub')}
      footer={
        <>
          <Button
            variant="ghost"
            onClick={() => {
              reset()
              onClose()
            }}
          >
            {t('common.cancel')}
          </Button>
          <Button variant="primary" disabled={create.isPending} onClick={submit}>
            {create.isPending ? t('ai.saving') : t('ai.add')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {err && (
          <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
            {err}
          </div>
        )}
        <Field label={t('ai.fldPrompt')}>
          <Input
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder={t('ai.promptPh')}
            autoFocus
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('ai.fldType')}>
            <Select
              value={kind}
              onChange={(v) => setKind(v as ExamQuestionKind)}
              options={KIND_OPTS}
            />
          </Field>
          <Field label={t('ai.fldDifficulty')}>
            <Input
              type="number"
              min={1}
              max={5}
              value={difficulty}
              onChange={(e) => setDifficulty(e.target.value)}
            />
          </Field>
        </div>
        <Field label={t('ai.fldCategory')} optional>
          <Input
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            placeholder={t('ai.categoryPh')}
          />
        </Field>
        <Field label={t('ai.fldKeywords')} hint={t('ai.keywordsHint')} optional>
          <Input
            value={keywords}
            onChange={(e) => setKeywords(e.target.value)}
            placeholder={t('ai.keywordsPh')}
          />
        </Field>
      </div>
    </Modal>
  )
}
