import { useMemo, useState } from 'react'
import {
  ArrowDown,
  ArrowUp,
  BookOpen,
  CalendarClock,
  Clock3,
  FileText,
  Image,
  Paperclip,
  Pencil,
  PlayCircle,
  Plus,
  Radio,
  Trash2,
  Upload,
} from 'lucide-react'
import { Button, Drawer, Empty, Field, IconButton, Input, Modal, Select, useToast } from '@/ui'
import type {
  CourseDto,
  CourseLessonDto,
  CourseLessonKind,
  CourseLivePoll,
  CourseLiveProvider,
  CourseStatus,
} from '@/lib/api-types'
import {
  useCourse,
  useCreateCourseLesson,
  useDeleteCourseLesson,
  useDeleteCourseLessonAttachment,
  useReorderCourseLessons,
  useUpdateCourse,
  useUpdateCourseLesson,
  useUploadCourseLessonVideo,
  useUploadCourseLessonAttachment,
} from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { LiveLessonStudio } from './LiveLessonStudio'

interface CourseEditorDrawerProps {
  courseId: string | null
  fallbackCourse?: CourseDto
  canManage: boolean
  onClose: () => void
}

const lessonKindLabels: Record<CourseLessonKind, string> = {
  text: 'Текстовый материал',
  video: 'Видеоурок',
  pdf: 'PDF',
  presentation: 'Презентация',
  image: 'Изображение',
  audio: 'Аудио',
  link: 'Внешняя ссылка',
  interactive: 'Интерактив',
  quiz: 'Контрольные вопросы',
  test: 'Итоговый тест',
  practice: 'Практическое задание',
  assignment: 'Задание',
  live: 'Прямой эфир',
}

type QuizQuestionDraft = {
  id: string
  prompt: string
  options: string[]
  correctOption: number
  explanation: string
}

function newQuizQuestion(): QuizQuestionDraft {
  return {
    id: `q_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`,
    prompt: '',
    options: ['', ''],
    correctOption: 0,
    explanation: '',
  }
}

function newLivePoll(): CourseLivePoll {
  const suffix = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`
  return {
    id: `poll_${suffix}`,
    prompt: '',
    active: false,
    options: [
      { id: `opt_${suffix}_1`, label: '' },
      { id: `opt_${suffix}_2`, label: '' },
    ],
  }
}

function toDateTimeLocal(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function toIso(value: string) {
  return value ? new Date(value).toISOString() : null
}

export function CourseEditorDrawer({
  courseId,
  fallbackCourse,
  canManage,
  onClose,
}: CourseEditorDrawerProps) {
  const toast = useToast()
  const detail = useCourse(courseId)
  const course = detail.data ?? fallbackCourse
  const updateCourse = useUpdateCourse()
  const removeLesson = useDeleteCourseLesson()
  const reorder = useReorderCourseLessons()
  const [lessonTarget, setLessonTarget] = useState<CourseLessonDto | 'new' | null>(null)
  const [liveStudioTarget, setLiveStudioTarget] = useState<CourseLessonDto | null>(null)
  const [title, setTitle] = useState(fallbackCourse?.title ?? '')
  const [category, setCategory] = useState(fallbackCourse?.category ?? '')
  const [description, setDescription] = useState(fallbackCourse?.description ?? '')
  const [bonus, setBonus] = useState(String(fallbackCourse?.bonus ?? 0))
  const [status, setStatus] = useState<CourseStatus>(fallbackCourse?.status ?? 'draft')
  const [metadataError, setMetadataError] = useState<string | null>(null)

  const editable = canManage && course?.status !== 'archived'
  const lessons = useMemo(
    () => [...(course?.lessonItems ?? [])].sort((a, b) => a.order - b.order),
    [course?.lessonItems],
  )

  const saveMetadata = () => {
    if (!course || !title.trim()) {
      setMetadataError('Введите название курса')
      return
    }
    setMetadataError(null)
    updateCourse.mutate(
      {
        id: course.id,
        patch: {
          title: title.trim(),
          category: category.trim(),
          description: description.trim(),
          bonus: Number(bonus) || 0,
          status,
        },
      },
      {
        onSuccess: () => toast.push('Курс сохранён'),
        onError: (error) => setMetadataError(describeError(error)),
      },
    )
  }

  const moveLesson = (index: number, direction: -1 | 1) => {
    if (!course) return
    const nextIndex = index + direction
    if (nextIndex < 0 || nextIndex >= lessons.length) return
    const lessonIds = lessons.map((lesson) => lesson.id)
    ;[lessonIds[index], lessonIds[nextIndex]] = [lessonIds[nextIndex], lessonIds[index]]
    reorder.mutate(
      { courseId: course.id, lessonIds },
      { onError: (error) => toast.push(describeError(error)) },
    )
  }

  return (
    <>
      <Drawer
        open={!!courseId}
        onClose={onClose}
        width={760}
        title={course?.title ?? 'Онлайн-курс'}
        subtitle={editable ? 'Содержание и настройки курса' : 'Просмотр содержания курса'}
        footer={
          editable ? (
            <Button disabled={updateCourse.isPending} onClick={saveMetadata}>
              {updateCourse.isPending ? 'Сохраняем…' : 'Сохранить курс'}
            </Button>
          ) : undefined
        }
      >
        {!course ? (
          <div className="py-16 text-center text-[13px] text-ink-500">
            {detail.isError ? 'Не удалось загрузить курс' : 'Загружаем курс…'}
          </div>
        ) : (
          <div className="flex flex-col gap-6" data-testid="course-editor">
            <section className="grid gap-3 border-b border-ink-100 pb-6">
              {metadataError && <ErrorBanner message={metadataError} />}
              <Field label="Название">
                <Input
                  value={title}
                  disabled={!editable}
                  onChange={(event) => setTitle(event.target.value)}
                />
              </Field>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <Field label="Категория" optional>
                  <Input
                    value={category}
                    disabled={!editable}
                    onChange={(event) => setCategory(event.target.value)}
                  />
                </Field>
                <Field label="Бонус, ₸">
                  <Input
                    type="number"
                    min={0}
                    value={bonus}
                    disabled={!editable}
                    onChange={(event) => setBonus(event.target.value)}
                  />
                </Field>
                <Field label="Статус">
                  <Select
                    value={status}
                    disabled={!editable}
                    onChange={(value) => setStatus(value as CourseStatus)}
                    options={[
                      { value: 'draft', label: 'Черновик' },
                      { value: 'published', label: 'Опубликован' },
                      ...(status === 'archived' ? [{ value: 'archived', label: 'Архив' }] : []),
                    ]}
                  />
                </Field>
              </div>
              <Field label="Описание" optional>
                <textarea
                  className="inp min-h-24"
                  value={description}
                  disabled={!editable}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </Field>
            </section>

            <section>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h3 className="text-[15px] font-extrabold text-ink-900">Уроки</h3>
                  <div className="mt-0.5 flex gap-3 text-[12px] text-ink-500">
                    <span className="inline-flex items-center gap-1">
                      <BookOpen size={14} /> {course.lessons}
                    </span>
                    <span className="inline-flex items-center gap-1">
                      <Clock3 size={14} /> {course.durationMin} мин.
                    </span>
                  </div>
                </div>
                {editable && (
                  <Button
                    size="sm"
                    variant="outline"
                    leading={<Plus size={15} />}
                    onClick={() => setLessonTarget('new')}
                  >
                    Добавить урок
                  </Button>
                )}
              </div>

              {lessons.length === 0 ? (
                <Empty
                  title="В курсе пока нет уроков"
                  body={
                    course.lessons > 0
                      ? `Старые данные курса содержат счётчик ${course.lessons}, но содержимое ещё не перенесено в редактор.`
                      : 'Добавьте текстовый материал или видеоурок.'
                  }
                  icon={<BookOpen size={26} />}
                  action={
                    editable ? (
                      <Button size="sm" onClick={() => setLessonTarget('new')}>
                        Добавить урок
                      </Button>
                    ) : undefined
                  }
                />
              ) : (
                <div className="divide-y divide-ink-100 border-y border-ink-100">
                  {lessons.map((lesson, index) => (
                    <article key={lesson.id} className="py-4">
                      <div className="flex items-start gap-3">
                        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-paper-hover text-ink-600">
                          {lesson.kind === 'live' ? (
                            <Radio size={18} />
                          ) : lesson.kind === 'video' ? (
                            <PlayCircle size={18} />
                          ) : (
                            <FileText size={18} />
                          )}
                        </div>
                        <button
                          type="button"
                          className="min-w-0 flex-1 text-left"
                          onClick={() => setLessonTarget(lesson)}
                        >
                          <div className="font-bold text-ink-900">
                            {index + 1}. {lesson.title}
                          </div>
                          <div className="mt-0.5 text-[12px] text-ink-500">
                            {lessonKindLabels[lesson.kind]} · {lesson.durationMin} мин.
                          </div>
                          {lesson.description && (
                            <p className="mt-2 line-clamp-2 text-[12px] leading-5 text-ink-600">
                              {lesson.description}
                            </p>
                          )}
                          {(lesson.attachments?.length ?? 0) > 0 && (
                            <div className="mt-2 inline-flex items-center gap-1 text-[12px] font-semibold text-brand-green-700">
                              <Paperclip size={13} /> {lesson.attachments.length} вложений
                            </div>
                          )}
                        </button>
                        <div className="flex shrink-0 items-center gap-0.5">
                          {editable && (
                            <>
                              <IconButton
                                tip="Выше"
                                aria-label={`Переместить урок ${lesson.title} выше`}
                                disabled={index === 0 || reorder.isPending}
                                onClick={() => moveLesson(index, -1)}
                              >
                                <ArrowUp size={16} />
                              </IconButton>
                              <IconButton
                                tip="Ниже"
                                aria-label={`Переместить урок ${lesson.title} ниже`}
                                disabled={index === lessons.length - 1 || reorder.isPending}
                                onClick={() => moveLesson(index, 1)}
                              >
                                <ArrowDown size={16} />
                              </IconButton>
                              <IconButton
                                tip="Редактировать"
                                aria-label={`Редактировать урок ${lesson.title}`}
                                onClick={() => setLessonTarget(lesson)}
                              >
                                <Pencil size={16} />
                              </IconButton>
                              <IconButton
                                tip="Удалить"
                                aria-label={`Удалить урок ${lesson.title}`}
                                disabled={removeLesson.isPending}
                                onClick={() => {
                                  if (!confirm(`Удалить урок «${lesson.title}»?`)) return
                                  removeLesson.mutate(
                                    { courseId: course.id, lessonId: lesson.id },
                                    {
                                      onSuccess: () => toast.push('Урок удалён'),
                                      onError: (error) => toast.push(describeError(error)),
                                    },
                                  )
                                }}
                              >
                                <Trash2 size={16} />
                              </IconButton>
                            </>
                          )}
                        </div>
                      </div>
                      {lesson.videoUrl && (
                        <video
                          controls
                          preload="metadata"
                          className="mt-3 aspect-video w-full rounded-md bg-ink-900"
                          src={lesson.videoUrl}
                        />
                      )}
                      {lesson.content && (
                        <div className="mt-3 whitespace-pre-wrap text-[13px] leading-6 text-ink-700">
                          {lesson.content}
                        </div>
                      )}
                      {lesson.kind === 'live' && (
                        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[#cce8e5] bg-[#f0faf8] px-3 py-2.5">
                          <div className="flex items-center gap-2 text-[12px] font-bold text-[#087b77]">
                            <CalendarClock size={15} />
                            {lesson.liveStartAt
                              ? new Date(lesson.liveStartAt).toLocaleString('ru-RU', { dateStyle: 'medium', timeStyle: 'short' })
                              : 'Дата эфира не указана'}
                          </div>
                          <Button size="sm" variant="outline" leading={<Radio size={14} />} onClick={() => setLiveStudioTarget(lesson)}>
                            Студия эфира
                          </Button>
                        </div>
                      )}
                    </article>
                  ))}
                </div>
              )}
            </section>
          </div>
        )}
      </Drawer>

      {course && lessonTarget && (
        <LessonEditorModal
          key={lessonTarget === 'new' ? `new-${course.id}` : lessonTarget.id}
          course={course}
          lesson={lessonTarget === 'new' ? null : lessonTarget}
          canManage={editable}
          onClose={() => setLessonTarget(null)}
        />
      )}
      {course && liveStudioTarget && (
        <LiveLessonStudio
          courseId={course.id}
          lesson={liveStudioTarget}
          canManage={editable}
          onClose={() => setLiveStudioTarget(null)}
        />
      )}
    </>
  )
}

function LessonEditorModal({
  course,
  lesson,
  canManage,
  onClose,
}: {
  course: CourseDto
  lesson: CourseLessonDto | null
  canManage: boolean
  onClose: () => void
}) {
  const toast = useToast()
  const create = useCreateCourseLesson()
  const update = useUpdateCourseLesson()
  const upload = useUploadCourseLessonVideo()
  const uploadAttachment = useUploadCourseLessonAttachment()
  const deleteAttachment = useDeleteCourseLessonAttachment()
  const [title, setTitle] = useState(lesson?.title ?? '')
  const [description, setDescription] = useState(lesson?.description ?? '')
  const [content, setContent] = useState(lesson?.content ?? '')
  const [kind, setKind] = useState<CourseLessonKind>(lesson?.kind ?? 'text')
  const [externalUrl, setExternalUrl] = useState(lesson?.externalUrl ?? '')
  const [liveProvider, setLiveProvider] = useState<CourseLiveProvider>(lesson?.liveProvider ?? 'custom')
  const liveStatus = lesson?.liveStatus ?? 'scheduled'
  const [liveStartAt, setLiveStartAt] = useState(toDateTimeLocal(lesson?.liveStartAt))
  const [liveEndAt, setLiveEndAt] = useState(toDateTimeLocal(lesson?.liveEndAt))
  const [liveChatEnabled, setLiveChatEnabled] = useState(lesson?.liveChatEnabled ?? true)
  const [liveQaEnabled, setLiveQaEnabled] = useState(lesson?.liveQaEnabled ?? true)
  const [liveReactionsEnabled, setLiveReactionsEnabled] = useState(lesson?.liveReactionsEnabled ?? true)
  const [livePolls, setLivePolls] = useState<CourseLivePoll[]>(() => lesson?.livePolls ?? [])
  const [required, setRequired] = useState(lesson?.required ?? true)
  const [minimumWatchPct, setMinimumWatchPct] = useState(
    String(lesson?.minimumWatchPct ?? 80),
  )
  const [quizPassingScore, setQuizPassingScore] = useState(
    String(lesson?.quizPassingScore ?? 80),
  )
  const [quizQuestions, setQuizQuestions] = useState<QuizQuestionDraft[]>(() =>
    (lesson?.quizQuestions ?? []).map((question) => ({
      id: question.id,
      prompt: question.prompt,
      options: [...question.options],
      correctOption: question.correctOption ?? 0,
      explanation: question.explanation,
    })),
  )
  const [durationMin, setDurationMin] = useState(String(lesson?.durationMin ?? 0))
  const [file, setFile] = useState<File | null>(null)
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([])
  const [persistedId, setPersistedId] = useState(lesson?.id ?? null)
  const [error, setError] = useState<string | null>(null)
  const pending =
    create.isPending ||
    update.isPending ||
    upload.isPending ||
    uploadAttachment.isPending ||
    deleteAttachment.isPending
  const quizMode = kind === 'quiz' || kind === 'test'

  const submit = async () => {
    if (!canManage) return
    if (!title.trim()) return setError('Введите название урока')
    if (file && file.size > 60 * 1024 * 1024)
      return setError('Размер видео не должен превышать 60 МБ')
    if (attachmentFiles.some((attachment) => attachment.size > 25 * 1024 * 1024))
      return setError('Размер каждого вложения не должен превышать 25 МБ')
    if (kind === 'live') {
      if (liveStatus === 'scheduled' && !liveStartAt) return setError('Укажите дату и время начала эфира')
      if (liveStatus === 'live' && !externalUrl.trim()) return setError('Для запуска укажите ссылку на трансляцию')
      if (externalUrl.trim()) {
        try {
          if (new URL(externalUrl.trim()).protocol !== 'https:') {
            return setError('Ссылка на трансляцию должна использовать HTTPS')
          }
        } catch {
          return setError('Укажите корректную HTTPS-ссылку на трансляцию')
        }
      }
      const invalidPoll = livePolls.findIndex(
        (poll) => !poll.prompt.trim() || poll.options.length < 2 || poll.options.some((option) => !option.label.trim()),
      )
      if (invalidPoll >= 0) return setError(`Проверьте интерактив ${invalidPoll + 1} и варианты ответа`)
    }
    if (quizMode) {
      if (quizQuestions.length === 0) return setError('Добавьте хотя бы один вопрос теста')
      const invalidQuestion = quizQuestions.findIndex(
        (question) =>
          !question.prompt.trim() ||
          question.options.length < 2 ||
          question.options.some((option) => !option.trim()) ||
          question.correctOption < 0 ||
          question.correctOption >= question.options.length,
      )
      if (invalidQuestion >= 0) return setError(`Проверьте вопрос ${invalidQuestion + 1} и варианты ответа`)
    }
    setError(null)
    try {
      let lessonId = persistedId
      const payload = {
        title: title.trim(),
        description: description.trim(),
        content: content.trim(),
        kind,
        externalUrl: externalUrl.trim() || null,
        liveProvider: kind === 'live' ? liveProvider : null,
        ...(kind === 'live' && liveStatus === 'scheduled'
          ? { liveStartAt: toIso(liveStartAt), liveEndAt: toIso(liveEndAt) }
          : {}),
        liveChatEnabled,
        liveQaEnabled,
        liveReactionsEnabled,
        livePolls: kind === 'live' ? livePolls : [],
        required,
        minimumWatchPct:
          kind === 'video' ? Math.min(100, Math.max(1, Number(minimumWatchPct) || 80)) : null,
        quizQuestions: quizMode
          ? quizQuestions.map((question) => ({
              ...question,
              prompt: question.prompt.trim(),
              options: question.options.map((option) => option.trim()),
              explanation: question.explanation.trim(),
            }))
          : [],
        quizPassingScore: Math.min(100, Math.max(1, Number(quizPassingScore) || 80)),
        durationMin: Number(durationMin) || 0,
      }
      if (lessonId) {
        await update.mutateAsync({
          courseId: course.id,
          lessonId,
          patch: {
            ...payload,
            clearVideo: kind !== 'video',
            clearExternalUrl: !externalUrl.trim(),
            ...(kind === 'live' && liveStatus !== 'scheduled'
              ? {}
              : { clearLiveSchedule: kind !== 'live' || !liveStartAt }),
            clearLiveSettings: kind !== 'live',
            clearMinimumWatchPct: kind !== 'video',
            clearQuiz: !quizMode,
          },
        })
      } else {
        const saved = await create.mutateAsync({ courseId: course.id, lesson: payload })
        lessonId = saved.lessonItems.at(-1)?.id ?? null
        if (!lessonId) throw new Error('Backend не вернул созданный урок')
        setPersistedId(lessonId)
      }
      if (kind === 'video' && file) {
        await upload.mutateAsync({ courseId: course.id, lessonId, file })
      }
      for (const attachment of attachmentFiles) {
        await uploadAttachment.mutateAsync({
          courseId: course.id,
          lessonId,
          file: attachment,
        })
      }
      toast.push(lesson ? 'Урок сохранён' : 'Урок добавлен')
      onClose()
    } catch (requestError) {
      setError(describeError(requestError))
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      width={620}
      title={canManage ? (lesson ? 'Редактировать урок' : 'Новый урок') : (lesson?.title ?? 'Урок')}
      subtitle={course.title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {canManage ? 'Отмена' : 'Закрыть'}
          </Button>
          {canManage && (
            <Button disabled={pending} onClick={submit}>
              {pending ? 'Сохраняем…' : 'Сохранить урок'}
            </Button>
          )}
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {error && <ErrorBanner message={error} />}
        <Field label="Название">
          <Input
            value={title}
            disabled={!canManage}
            onChange={(event) => setTitle(event.target.value)}
            autoFocus
          />
        </Field>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="Тип урока">
            <Select
              value={kind}
              disabled={!canManage}
              onChange={(value) => {
                const nextKind = value as CourseLessonKind
                setKind(nextKind)
                if ((nextKind === 'quiz' || nextKind === 'test') && quizQuestions.length === 0) {
                  setQuizQuestions([newQuizQuestion()])
                }
                if (nextKind === 'live' && !liveStartAt) {
                  const nextHour = new Date(Date.now() + 60 * 60 * 1000)
                  nextHour.setMinutes(0, 0, 0)
                  setLiveStartAt(toDateTimeLocal(nextHour.toISOString()))
                }
              }}
              options={[
                { value: 'text', label: 'Текстовый материал' },
                { value: 'video', label: 'Видеоурок' },
                { value: 'pdf', label: 'PDF' },
                { value: 'presentation', label: 'Презентация' },
                { value: 'image', label: 'Изображение' },
                { value: 'audio', label: 'Аудио' },
                { value: 'link', label: 'Внешняя ссылка' },
                { value: 'interactive', label: 'Интерактив' },
                { value: 'quiz', label: 'Контрольные вопросы' },
                { value: 'test', label: 'Итоговый тест' },
                { value: 'practice', label: 'Практическое задание' },
                { value: 'assignment', label: 'Задание' },
                { value: 'live', label: 'Прямой эфир' },
              ]}
            />
          </Field>
          <Field label="Длительность, минут">
            <Input
              type="number"
              min={kind === 'video' ? 1 : 0}
              max={kind === 'video' ? 30 : undefined}
              value={durationMin}
              disabled={!canManage}
              onChange={(event) => setDurationMin(event.target.value)}
            />
          </Field>
        </div>
        <label className="flex items-center gap-2 text-[13px] font-semibold text-ink-700">
          <input
            type="checkbox"
            checked={required}
            disabled={!canManage}
            onChange={(event) => setRequired(event.target.checked)}
          />
          Обязательный урок
        </label>
        {(kind === 'link' || kind === 'interactive' || kind === 'live') && (
          <Field label={kind === 'live' ? 'Ссылка на трансляцию' : 'Ссылка'} optional={kind === 'live' && liveStatus === 'scheduled'}>
            <Input
              type="url"
              value={externalUrl}
              disabled={!canManage}
              placeholder={kind === 'live' ? 'https://youtube.com/live/… или ссылка провайдера' : 'https://…'}
              onChange={(event) => setExternalUrl(event.target.value)}
            />
          </Field>
        )}
        {kind === 'live' && (
          <section className="rounded-2xl border border-[#cce8e5] bg-[#f4fbfa] p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2 text-[14px] font-extrabold text-[#075f61]"><Radio size={17} /> Настройки прямого эфира</div>
                <p className="mt-1 text-[11px] leading-5 text-ink-500">Трансляция, чат, вопросы, реакции и быстрые опросы в одном окне.</p>
              </div>
              <span className={`rounded-full px-2.5 py-1 text-[10px] font-extrabold uppercase ${liveStatus === 'live' ? 'bg-red-100 text-red-700' : liveStatus === 'ended' ? 'bg-ink-100 text-ink-600' : 'bg-[#dff4f1] text-[#087b77]'}`}>
                {liveStatus === 'live' ? 'В эфире' : liveStatus === 'ended' ? 'Завершён' : 'Запланирован'}
              </span>
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <Field label="Провайдер">
                <Select
                  value={liveProvider}
                  disabled={!canManage}
                  onChange={(value) => setLiveProvider(value as CourseLiveProvider)}
                  options={[
                    { value: 'youtube', label: 'YouTube Live' },
                    { value: 'vimeo', label: 'Vimeo Live' },
                    { value: 'teams', label: 'Microsoft Teams' },
                    { value: 'zoom', label: 'Zoom' },
                    { value: 'custom', label: 'Другая платформа / HLS' },
                  ]}
                />
              </Field>
              <p className="self-center text-[11px] leading-5 text-ink-500">
                Статус эфира меняется только в студии после сохранения урока.
              </p>
              <Field label="Начало">
                <Input type="datetime-local" value={liveStartAt} disabled={!canManage || liveStatus !== 'scheduled'} onChange={(event) => setLiveStartAt(event.target.value)} />
              </Field>
              <Field label="Окончание" optional>
                <Input type="datetime-local" value={liveEndAt} disabled={!canManage || liveStatus !== 'scheduled'} onChange={(event) => setLiveEndAt(event.target.value)} />
              </Field>
            </div>
            <div className="mt-4 grid gap-2 sm:grid-cols-3">
              {[
                ['Чат участников', liveChatEnabled, setLiveChatEnabled],
                ['Вопросы ведущему', liveQaEnabled, setLiveQaEnabled],
                ['Реакции', liveReactionsEnabled, setLiveReactionsEnabled],
              ].map(([label, checked, setter]) => (
                <label key={String(label)} className="flex items-center gap-2 rounded-xl border border-[#d9ebe9] bg-white px-3 py-2.5 text-[11px] font-bold text-ink-700">
                  <input type="checkbox" checked={Boolean(checked)} disabled={!canManage} onChange={(event) => (setter as (value: boolean) => void)(event.target.checked)} />
                  {String(label)}
                </label>
              ))}
            </div>
            <div className="mt-4 border-t border-[#d9ebe9] pt-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="text-[12px] font-extrabold text-ink-800">Интерактивы во время эфира</div>
                  <div className="text-[10px] text-ink-500">Запускайте и закрывайте опросы прямо в студии.</div>
                </div>
                {canManage && <Button size="sm" variant="outline" leading={<Plus size={14} />} onClick={() => setLivePolls((current) => [...current, newLivePoll()])}>Добавить опрос</Button>}
              </div>
              <div className="mt-3 space-y-3">
                {livePolls.map((poll, pollIndex) => (
                  <article key={poll.id} className="rounded-xl border border-[#dcecea] bg-white p-3">
                    <div className="flex items-start gap-2">
                      <div className="min-w-0 flex-1">
                        <Input value={poll.prompt} disabled={!canManage} placeholder={`Вопрос опроса ${pollIndex + 1}`} onChange={(event) => setLivePolls((current) => current.map((item) => item.id === poll.id ? { ...item, prompt: event.target.value } : item))} />
                      </div>
                      {canManage && <IconButton tip="Удалить опрос" aria-label={`Удалить опрос ${pollIndex + 1}`} onClick={() => setLivePolls((current) => current.filter((item) => item.id !== poll.id))}><Trash2 size={15} /></IconButton>}
                    </div>
                    <div className="mt-2 grid gap-2 sm:grid-cols-2">
                      {poll.options.map((option, optionIndex) => (
                        <div key={option.id} className="flex items-center gap-1.5">
                          <Input value={option.label} disabled={!canManage} placeholder={`Вариант ${optionIndex + 1}`} onChange={(event) => setLivePolls((current) => current.map((item) => item.id === poll.id ? { ...item, options: item.options.map((entry) => entry.id === option.id ? { ...entry, label: event.target.value } : entry) } : item))} />
                          {canManage && poll.options.length > 2 && <IconButton tip="Удалить вариант" aria-label={`Удалить вариант ${optionIndex + 1}`} onClick={() => setLivePolls((current) => current.map((item) => item.id === poll.id ? { ...item, options: item.options.filter((entry) => entry.id !== option.id) } : item))}><Trash2 size={13} /></IconButton>}
                        </div>
                      ))}
                    </div>
                    <div className="mt-2 flex items-center justify-between gap-2">
                      {canManage && poll.options.length < 8 ? <button type="button" className="text-[11px] font-bold text-[#087b77]" onClick={() => setLivePolls((current) => current.map((item) => item.id === poll.id ? { ...item, options: [...item.options, { id: `opt_${Date.now().toString(36)}`, label: '' }] } : item))}>+ Вариант</button> : <span />}
                      <label className="flex items-center gap-2 text-[11px] font-bold text-ink-600"><input type="checkbox" checked={poll.active} disabled={!canManage} onChange={(event) => setLivePolls((current) => current.map((item) => item.id === poll.id ? { ...item, active: event.target.checked } : item))} /> Активен сейчас</label>
                    </div>
                  </article>
                ))}
                {livePolls.length === 0 && <div className="rounded-xl border border-dashed border-[#cfe2df] px-4 py-5 text-center text-[11px] text-ink-500">Опросов пока нет. Их можно добавить сейчас или перед эфиром.</div>}
              </div>
            </div>
          </section>
        )}
        {kind === 'video' && (
          <Field label="Минимальный просмотр, %">
            <Input
              type="number"
              min={1}
              max={100}
              value={minimumWatchPct}
              disabled={!canManage}
              onChange={(event) => setMinimumWatchPct(event.target.value)}
            />
          </Field>
        )}
        {quizMode && (
          <section className="rounded-lg border border-ink-100 bg-paper-hover p-4">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <Field label="Проходной балл, %">
                <Input
                  type="number"
                  min={1}
                  max={100}
                  value={quizPassingScore}
                  disabled={!canManage}
                  onChange={(event) => setQuizPassingScore(event.target.value)}
                />
              </Field>
              {canManage && (
                <Button
                  size="sm"
                  variant="outline"
                  leading={<Plus size={15} />}
                  onClick={() => setQuizQuestions((current) => [...current, newQuizQuestion()])}
                >
                  Добавить вопрос
                </Button>
              )}
            </div>
            <div className="mt-4 space-y-4">
              {quizQuestions.map((question, questionIndex) => (
                <article key={question.id} className="rounded-lg border border-ink-100 bg-white p-4">
                  <div className="flex items-start gap-3">
                    <div className="min-w-0 flex-1">
                      <Field label={`Вопрос ${questionIndex + 1}`}>
                        <Input
                          value={question.prompt}
                          disabled={!canManage}
                          placeholder="Введите текст вопроса"
                          onChange={(event) =>
                            setQuizQuestions((current) =>
                              current.map((item) =>
                                item.id === question.id ? { ...item, prompt: event.target.value } : item,
                              ),
                            )
                          }
                        />
                      </Field>
                    </div>
                    {canManage && quizQuestions.length > 1 && (
                      <IconButton
                        tip="Удалить вопрос"
                        aria-label={`Удалить вопрос ${questionIndex + 1}`}
                        onClick={() =>
                          setQuizQuestions((current) => current.filter((item) => item.id !== question.id))
                        }
                      >
                        <Trash2 size={16} />
                      </IconButton>
                    )}
                  </div>
                  <div className="mt-3 space-y-2">
                    {question.options.map((option, optionIndex) => (
                      <div key={`${question.id}-${optionIndex}`} className="flex items-center gap-2">
                        <input
                          type="radio"
                          name={`correct-${question.id}`}
                          checked={question.correctOption === optionIndex}
                          disabled={!canManage}
                          aria-label={`Правильный ответ ${optionIndex + 1}`}
                          onChange={() =>
                            setQuizQuestions((current) =>
                              current.map((item) =>
                                item.id === question.id ? { ...item, correctOption: optionIndex } : item,
                              ),
                            )
                          }
                        />
                        <Input
                          value={option}
                          disabled={!canManage}
                          placeholder={`Вариант ${optionIndex + 1}`}
                          onChange={(event) =>
                            setQuizQuestions((current) =>
                              current.map((item) => {
                                if (item.id !== question.id) return item
                                const options = [...item.options]
                                options[optionIndex] = event.target.value
                                return { ...item, options }
                              }),
                            )
                          }
                        />
                        {canManage && question.options.length > 2 && (
                          <IconButton
                            tip="Удалить вариант"
                            aria-label={`Удалить вариант ${optionIndex + 1}`}
                            onClick={() =>
                              setQuizQuestions((current) =>
                                current.map((item) => {
                                  if (item.id !== question.id) return item
                                  const options = item.options.filter((_, index) => index !== optionIndex)
                                  const correctOption = item.correctOption === optionIndex
                                    ? 0
                                    : item.correctOption > optionIndex
                                      ? item.correctOption - 1
                                      : item.correctOption
                                  return { ...item, options, correctOption }
                                }),
                              )
                            }
                          >
                            <Trash2 size={14} />
                          </IconButton>
                        )}
                      </div>
                    ))}
                  </div>
                  {canManage && question.options.length < 8 && (
                    <button
                      type="button"
                      className="mt-2 text-[12px] font-bold text-brand-green-700 hover:underline"
                      onClick={() =>
                        setQuizQuestions((current) =>
                          current.map((item) =>
                            item.id === question.id ? { ...item, options: [...item.options, ''] } : item,
                          ),
                        )
                      }
                    >
                      + Добавить вариант
                    </button>
                  )}
                  <Field label="Пояснение после ответа" optional>
                    <textarea
                      className="inp mt-2 min-h-16"
                      value={question.explanation}
                      maxLength={2000}
                      disabled={!canManage}
                      onChange={(event) =>
                        setQuizQuestions((current) =>
                          current.map((item) =>
                            item.id === question.id ? { ...item, explanation: event.target.value } : item,
                          ),
                        )
                      }
                    />
                  </Field>
                </article>
              ))}
            </div>
          </section>
        )}
        <Field label="Краткое описание" optional>
          <textarea
            className="inp min-h-20"
            value={description}
            maxLength={1000}
            disabled={!canManage}
            onChange={(event) => setDescription(event.target.value)}
          />
          <div className="mt-1 text-right text-[11px] text-ink-500">{description.length}/1000</div>
        </Field>
        <Field label="Текст урока" optional>
          <textarea
            className="inp min-h-36"
            value={content}
            maxLength={10000}
            disabled={!canManage}
            onChange={(event) => setContent(event.target.value)}
          />
          <div className="mt-1 text-right text-[11px] text-ink-500">{content.length}/10000</div>
        </Field>
        {kind === 'video' && (
          <Field
            label={lesson?.videoUrl ? 'Заменить видео' : 'Видео'}
            optional={!!lesson?.videoUrl}
            hint="MP4 или WebM, не более 60 МБ"
          >
            <label className="flex min-h-20 cursor-pointer items-center justify-center gap-2 rounded-md border border-dashed border-ink-300 bg-paper-hover px-4 text-[13px] font-semibold text-ink-600 hover:border-brand-green-600">
              <Upload size={18} />
              <span className="truncate">{file?.name ?? 'Выбрать видеофайл'}</span>
              <input
                className="sr-only"
                type="file"
                accept="video/mp4,video/webm,.mp4,.webm"
                disabled={!canManage}
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </label>
          </Field>
        )}
        {lesson?.videoUrl && kind === 'video' && !file && (
          <video
            controls
            preload="metadata"
            className="aspect-video w-full rounded-md bg-ink-900"
            src={lesson.videoUrl}
          />
        )}
        <Field
          label="Материалы урока"
          optional
          hint="До 10 файлов: изображения, аудио, PDF, Word, Excel, PowerPoint или текст; до 25 МБ каждый"
        >
          <label className="flex min-h-20 cursor-pointer items-center justify-center gap-2 rounded-md border border-dashed border-ink-300 bg-paper-hover px-4 text-[13px] font-semibold text-ink-600 hover:border-brand-green-600">
            <Paperclip size={18} />
            <span className="truncate">
              {attachmentFiles.length > 0
                ? `Выбрано файлов: ${attachmentFiles.length}`
                : 'Прикрепить файлы'}
            </span>
            <input
              className="sr-only"
              type="file"
              multiple
              accept="image/*,audio/*,.mp3,.m4a,.aac,.wav,.ogg,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv"
              disabled={!canManage}
              onChange={(event) => setAttachmentFiles(Array.from(event.target.files ?? []).slice(0, 10))}
            />
          </label>
        </Field>
        {attachmentFiles.length > 0 && (
          <div className="space-y-1 rounded-md bg-paper-hover p-3">
            {attachmentFiles.map((attachment) => (
              <div key={`${attachment.name}-${attachment.size}`} className="flex items-center gap-2 text-[12px] text-ink-600">
                {attachment.type.startsWith('image/') ? <Image size={14} /> : <FileText size={14} />}
                <span className="min-w-0 flex-1 truncate">{attachment.name}</span>
                <span>{formatBytes(attachment.size)}</span>
              </div>
            ))}
          </div>
        )}
        {lesson && (lesson.attachments?.length ?? 0) > 0 && (
          <div className="space-y-2">
            <div className="text-[12px] font-bold text-ink-700">Уже прикреплено</div>
            {(lesson.attachments ?? []).map((attachment) => (
              <div key={attachment.id} className="flex items-center gap-2 rounded-md border border-ink-100 px-3 py-2">
                {attachment.kind === 'image' ? <Image size={16} /> : <FileText size={16} />}
                <a
                  className="min-w-0 flex-1 truncate text-[12px] font-semibold text-brand-green-700"
                  href={attachment.mediaUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  {attachment.title}
                </a>
                {canManage && (
                  <IconButton
                    tip="Удалить материал"
                    aria-label={`Удалить материал ${attachment.title}`}
                    disabled={pending}
                    onClick={() =>
                      deleteAttachment.mutate(
                        {
                          courseId: course.id,
                          lessonId: lesson.id,
                          attachmentId: attachment.id,
                        },
                        {
                          onSuccess: () => toast.push('Материал удалён'),
                          onError: (requestError) => setError(describeError(requestError)),
                        },
                      )
                    }
                  >
                    <Trash2 size={15} />
                  </IconButton>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </Modal>
  )
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} КБ`
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-md bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
      {message}
    </div>
  )
}
