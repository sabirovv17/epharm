// LMS — каталог курсов для фармацевтов.
// Этап 3.6: подключён backend через useCourses / useCreateCourse.

import { useMemo, useState } from 'react'
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
import { IconLMS, IconPlayCircle, IconPlus, IconTrash, IconUsers } from '@/ui/icons'
import type { CourseDto, CourseStatus } from '@/lib/api-types'
import { useCourses, useCreateCourse, useDeleteCourse } from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'
import { useT } from '@/i18n'

type LMSTab = 'published' | 'draft'

export default function LMSPage() {
  const t = useT()
  const toast = useToast()
  const [tab, setTab] = useState<LMSTab>('published')
  const [createOpen, setCreateOpen] = useState(false)

  const { data: courses = [], isLoading, isError, error, refetch } = useCourses()
  const hasData = courses.length > 0

  const published = useMemo(() => courses.filter((c) => c.status === 'published'), [courses])
  const drafts = useMemo(() => courses.filter((c) => c.status === 'draft'), [courses])
  const visible = tab === 'published' ? published : drafts

  const totalEnrolled = courses.reduce((a, c) => a + c.enrolled, 0)
  const totalCompleted = courses.reduce((a, c) => a + c.completed, 0)

  const tabs: TabItem<LMSTab>[] = [
    { value: 'published', label: t('lms.tabPublished'), count: published.length },
    { value: 'draft', label: t('lms.tabDraft'), count: drafts.length },
  ]

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('page.lms.title')}
        subtitle={t('page.lms.subtitle')}
        actions={
          <Button
            variant="primary"
            size="md"
            leading={<IconPlus size={14} />}
            onClick={() => setCreateOpen(true)}
          >
            {t('lms.newCourse')}
          </Button>
        }
      />

      <div className="grid grid-cols-3 gap-4">
        <Metric
          label={t('lms.mCatalog')}
          value={formatNum(courses.length)}
          accent="ink"
          icon={<IconLMS size={16} />}
        />
        <Metric
          label={t('lms.mStudents')}
          value={formatNum(totalEnrolled)}
          accent="green"
          icon={<IconUsers size={16} />}
        />
        <Metric
          label={t('lms.mCompleted')}
          value={formatNum(totalCompleted)}
          accent="blue"
          icon={<IconPlayCircle size={16} />}
        />
      </div>

      <div className="card flex min-h-[420px] flex-col">
        <div className="px-5 pt-4">
          <Tabs<LMSTab> items={tabs} value={tab} onChange={setTab} />
        </div>
        <div className="flex-1 px-5 pb-5 pt-3">
          {isLoading && !hasData ? (
            <Empty
              title={t('lms.loading')}
              body={t('common.connecting')}
              icon={<IconLMS size={26} />}
            />
          ) : isError && !hasData ? (
            <Empty
              title={t('lms.errTitle')}
              body={describeError(error)}
              icon={<IconLMS size={26} />}
              action={<Button onClick={() => refetch()}>{t('common.retry')}</Button>}
            />
          ) : visible.length === 0 ? (
            <Empty
              title={t('lms.empty')}
              body={t('lms.emptyBody')}
              icon={<IconLMS size={26} />}
              action={
                <Button leading={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                  {t('lms.newCourse')}
                </Button>
              }
            />
          ) : (
            <ul className="grid grid-cols-2 gap-3" data-testid="lms-courses">
              {visible.map((c) => (
                <CourseCard key={c.id} course={c} />
              ))}
            </ul>
          )}
        </div>
      </div>

      <CreateCourseModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(title) => {
          toast.push(t('lms.createdToast', { title }))
          setCreateOpen(false)
        }}
      />
    </div>
  )
}

function CourseCard({ course }: { course: CourseDto }) {
  const t = useT()
  const toast = useToast()
  const del = useDeleteCourse()

  const handleDelete = () => {
    if (!confirm(t('lms.confirmDelete', { title: course.title }))) return
    del.mutate(course.id, {
      onSuccess: () => toast.push(t('lms.deletedToast')),
      onError: () => toast.push(t('lms.deleteErr')),
    })
  }

  return (
    <li
      data-testid={`lms-course-${course.id}`}
      className="hairline flex flex-col gap-2 rounded-xl border p-4"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="text-[14px] font-extrabold text-ink-900">{course.title}</div>
        <div className="flex items-center gap-1.5">
          <span className={`chip ${course.status === 'published' ? 'chip-green' : 'chip-ink'}`}>
            {course.status === 'published' ? t('lms.published') : t('lms.draft')}
          </span>
          <button
            type="button"
            onClick={handleDelete}
            disabled={del.isPending}
            aria-label={t('lms.delete')}
            data-testid={`lms-course-delete-${course.id}`}
            className="text-ink-400 transition-colors hover:text-accent-danger disabled:opacity-40"
          >
            <IconTrash size={15} />
          </button>
        </div>
      </div>
      <div className="text-[12px] text-ink-500">{course.category || t('lms.noCategory')}</div>
      <div className="flex items-center gap-3 text-[12px] text-ink-600">
        <span className="num">{t('lms.lessons', { n: formatNum(course.lessons) })}</span>
        <span className="num">{t('lms.minutes', { n: formatNum(course.durationMin) })}</span>
        <span className="num font-bold text-brand-green-700">
          {t('lms.bonus', { n: formatNum(course.bonus) })}
        </span>
      </div>
      <div className="num text-[11px] text-ink-500">
        {t('lms.enrolledCompleted', {
          e: formatNum(course.enrolled),
          c: formatNum(course.completed),
        })}
      </div>
    </li>
  )
}

function CreateCourseModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: (title: string) => void
}) {
  const t = useT()
  const STATUS_OPTS: { value: CourseStatus; label: string }[] = [
    { value: 'draft', label: t('lms.draft') },
    { value: 'published', label: t('lms.published') },
  ]
  const create = useCreateCourse()
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState('')
  const [lessons, setLessons] = useState('')
  const [durationMin, setDurationMin] = useState('')
  const [bonus, setBonus] = useState('')
  const [status, setStatus] = useState<CourseStatus>('draft')
  const [err, setErr] = useState<string | null>(null)

  const reset = () => {
    setTitle('')
    setCategory('')
    setLessons('')
    setDurationMin('')
    setBonus('')
    setStatus('draft')
    setErr(null)
  }

  const submit = () => {
    if (!title.trim()) {
      setErr(t('lms.errTitleReq'))
      return
    }
    create.mutate(
      {
        title: title.trim(),
        category: category.trim(),
        lessons: Number(lessons) || 0,
        durationMin: Number(durationMin) || 0,
        bonus: Number(bonus) || 0,
        status,
      },
      {
        onSuccess: () => {
          const t = title.trim()
          reset()
          onCreated(t)
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
      title={t('lms.newCourse')}
      subtitle={t('lms.modalSub')}
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
            {create.isPending ? t('lms.creating') : t('lms.create')}
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
        <Field label={t('lms.fldTitle')}>
          <Input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={t('lms.titlePh')}
            autoFocus
          />
        </Field>
        <Field label={t('lms.fldCategory')} optional>
          <Input
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            placeholder={t('lms.categoryPh')}
          />
        </Field>
        <div className="grid grid-cols-3 gap-3">
          <Field label={t('lms.fldLessons')}>
            <Input
              type="number"
              min={0}
              value={lessons}
              onChange={(e) => setLessons(e.target.value)}
            />
          </Field>
          <Field label={t('lms.fldMinutes')}>
            <Input
              type="number"
              min={0}
              value={durationMin}
              onChange={(e) => setDurationMin(e.target.value)}
            />
          </Field>
          <Field label={t('lms.fldBonus')}>
            <Input type="number" min={0} value={bonus} onChange={(e) => setBonus(e.target.value)} />
          </Field>
        </div>
        <Field label={t('lms.fldStatus')}>
          <Select
            value={status}
            onChange={(v) => setStatus(v as CourseStatus)}
            options={STATUS_OPTS}
          />
        </Field>
      </div>
    </Modal>
  )
}
