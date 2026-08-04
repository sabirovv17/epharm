import { useEffect, useMemo, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { Button, Field, Input, Modal, SearchInput, Select, useToast } from '@/ui'
import { IconArrowDown, IconArrowUp, IconPlus, IconTrash } from '@/ui/icons'
import type {
  CourseDto,
  CreateTrainingStageRequest,
  DuplicateAssignmentPolicy,
  EventParticipantStatus,
  OfflineEventDto,
  PharmacistDto,
  TrainingFormat,
  TrainingAssignmentDto,
  TrainingAssignmentStageDto,
  TrainingProgramDto,
  TrainingProgramStatus,
  TrainingStageType,
  UpdateTrainingProgramRequest,
} from '@/lib/api-types'
import {
  useCreateOfflineEvent,
  useAssignmentFormatHistory,
  useChangeAssignmentFormat,
  useCreateTrainingAssignments,
  useCreateTrainingProgram,
  useEventParticipants,
  useMarkAttendance,
  useRecordAssessmentResult,
  useTrainingEventQr,
  useUpdateOfflineEvent,
  useUpdateTrainingProgram,
} from '@/lib/queries/lms'
import { describeError } from '@/lib/describeError'
import { formatNum } from '@/mocks/fixtures'
import {
  EVENT_STATUS_LABEL,
  FORMAT_LABEL,
  PARTICIPANT_STATUS_LABEL,
  chipClass,
  dateTime,
  isoToLocalDateTime,
  localDateTimeToIso,
} from './training-ui'

interface CommonModalProps {
  open: boolean
  onClose: () => void
}

interface RouteStageDraft extends CreateTrainingStageRequest {
  draftId: string
  applicableFormats: TrainingFormat[]
}

const STAGE_OPTIONS: Array<{ value: TrainingStageType; label: string }> = [
  { value: 'material', label: 'Учебный материал' },
  { value: 'online_course', label: 'Онлайн-курс' },
  { value: 'test', label: 'Итоговый тест' },
  { value: 'ai_exam', label: 'AI-экзамен' },
  { value: 'offline_event', label: 'Очное мероприятие' },
  { value: 'manual_review', label: 'Ручная аттестация' },
]

function defaultRouteDrafts(
  formats: TrainingFormat[],
  hasCourse: boolean,
  passingScore: number,
  maxAttempts: number,
): RouteStageDraft[] {
  const route: RouteStageDraft[] = []
  const onlineFormats = formats.filter((format) => format === 'online' || format === 'hybrid')
  const offlineFormats = formats.filter((format) => format === 'hybrid' || format === 'offline')
  if (onlineFormats.length > 0) {
    route.push({
      draftId: 'route-online',
      key: 'online',
      type: hasCourse ? 'online_course' : 'material',
      title: hasCourse ? 'Онлайн-курс' : 'Материалы программы',
      order: route.length,
      required: true,
      applicableFormats: onlineFormats,
    })
  }
  if (offlineFormats.length > 0) {
    route.push({
      draftId: 'route-offline',
      key: 'offline',
      type: 'offline_event',
      title: 'Очное обучение',
      order: route.length,
      required: true,
      applicableFormats: offlineFormats,
    })
  }
  route.push({
    draftId: 'route-test',
    key: 'final_test',
    type: 'test',
    title: 'Итоговый тест',
    order: route.length,
    required: true,
    passingScore,
    maxAttempts,
    applicableFormats: formats,
  })
  return route
}

function routeDraftsFromProgram(program: TrainingProgramDto): RouteStageDraft[] {
  return program.stages.map((stage, index) => ({
    draftId: `existing-${stage.id}`,
    key: stage.key,
    type: stage.type,
    title: stage.title,
    order: index,
    required: stage.required,
    unlockAfterKey: stage.unlockAfterKey,
    deadlineDays: stage.deadlineDays,
    passingScore: stage.passingScore,
    maxAttempts: stage.maxAttempts,
    bonus: stage.bonus,
    manualReview: stage.manualReview,
    applicableFormats: [...stage.applicableFormats],
    contentUrl: stage.contentUrl,
  }))
}

function normalizeStage(stage: CreateTrainingStageRequest, index: number) {
  return {
    key: stage.key.trim(),
    type: stage.type,
    title: stage.title.trim(),
    order: index,
    required: stage.required ?? true,
    unlockAfterKey: stage.unlockAfterKey || null,
    deadlineDays: stage.deadlineDays ?? null,
    passingScore: stage.passingScore ?? null,
    maxAttempts: stage.maxAttempts ?? null,
    bonus: stage.bonus ?? 0,
    manualReview: stage.manualReview ?? false,
    applicableFormats: [...(stage.applicableFormats ?? [])].sort(),
    contentUrl: stage.contentUrl?.trim() || null,
  }
}

function hasVersionChanges(
  program: TrainingProgramDto,
  formats: TrainingFormat[],
  courseId: string,
  passingScore: number,
  maxAttempts: number,
  bonus: number,
  route: CreateTrainingStageRequest[] | undefined,
) {
  if ([...program.allowedFormats].sort().join('|') !== [...formats].sort().join('|')) return true
  if ((program.onlineCourseId ?? '') !== courseId) return true
  if (
    program.passingScore !== passingScore ||
    program.maxAttempts !== maxAttempts ||
    program.completionBonus !== bonus
  ) {
    return true
  }
  if (!route) return false
  const previousRoute = program.stages.map((stage, index) => normalizeStage(stage, index))
  const nextRoute = route.map(normalizeStage)
  return JSON.stringify(previousRoute) !== JSON.stringify(nextRoute)
}

export function CreateProgramModal({
  open,
  onClose,
  courses,
  program = null,
  mode = 'create',
}: CommonModalProps & {
  courses: CourseDto[]
  program?: TrainingProgramDto | null
  mode?: 'create' | 'edit' | 'duplicate'
}) {
  const toast = useToast()
  const create = useCreateTrainingProgram()
  const update = useUpdateTrainingProgram()
  const [name, setName] = useState(() =>
    program ? (mode === 'duplicate' ? `${program.name} (копия)` : program.name) : '',
  )
  const [shortDescription, setShortDescription] = useState(() => program?.shortDescription ?? '')
  const [description, setDescription] = useState(() => program?.description ?? '')
  const [coverUrl, setCoverUrl] = useState(() => program?.coverUrl ?? '')
  const [category, setCategory] = useState(() => program?.category ?? '')
  const [manufacturer, setManufacturer] = useState(() => program?.manufacturer ?? '')
  const [brand, setBrand] = useState(() => program?.brand ?? '')
  const [product, setProduct] = useState(() => program?.product ?? '')
  const [language, setLanguage] = useState(() => program?.language ?? 'ru')
  const [startsAt, setStartsAt] = useState(() => isoToLocalDateTime(program?.startsAt ?? null))
  const [endsAt, setEndsAt] = useState(() => isoToLocalDateTime(program?.endsAt ?? null))
  const [tags, setTags] = useState(() => program?.tags.join(', ') ?? '')
  const [formats, setFormats] = useState<TrainingFormat[]>(() =>
    program ? [...program.allowedFormats] : ['online'],
  )
  const [courseId, setCourseId] = useState(() => program?.onlineCourseId ?? '')
  const [normativeDays, setNormativeDays] = useState(() => String(program?.normativeDays ?? 14))
  const [passingScore, setPassingScore] = useState(() => String(program?.passingScore ?? 80))
  const [maxAttempts, setMaxAttempts] = useState(() => String(program?.maxAttempts ?? 3))
  const [bonus, setBonus] = useState(() => String(program?.completionBonus ?? 0))
  const [status, setStatus] = useState<TrainingProgramStatus>(() =>
    mode === 'duplicate' ? 'draft' : (program?.status ?? 'draft'),
  )
  const [customRoute, setCustomRoute] = useState(() => !!program)
  const [stages, setStages] = useState<RouteStageDraft[]>(() =>
    program ? routeDraftsFromProgram(program) : [],
  )
  const [error, setError] = useState<string | null>(null)

  const toggleFormat = (format: TrainingFormat) => {
    setFormats((current) => {
      const next = current.includes(format)
        ? current.filter((item) => item !== format)
        : [...current, format]
      if (customRoute) {
        setStages((route) =>
          route
            .map((stage) => ({
              ...stage,
              applicableFormats: stage.applicableFormats.filter((item) => next.includes(item)),
            }))
            .filter((stage) => stage.applicableFormats.length > 0),
        )
      }
      return next
    })
  }

  const enableCustomRoute = () => {
    setStages(
      defaultRouteDrafts(
        formats,
        !!courseId,
        Math.min(100, Math.max(0, Number(passingScore) || 0)),
        Math.max(1, Number(maxAttempts) || 1),
      ),
    )
    setCustomRoute(true)
  }

  const updateStage = (draftId: string, patch: Partial<RouteStageDraft>) => {
    setStages((route) =>
      route.map((stage) => (stage.draftId === draftId ? { ...stage, ...patch } : stage)),
    )
  }

  const addStage = () => {
    setStages((route) => [
      ...route,
      {
        draftId: `route-${route.length}-${Date.now()}`,
        key: '',
        type: 'material',
        title: 'Новый этап',
        order: route.length,
        required: true,
        applicableFormats: [...formats],
      },
    ])
  }

  const moveStage = (draftId: string, direction: -1 | 1) => {
    setStages((route) => {
      const index = route.findIndex((stage) => stage.draftId === draftId)
      const target = index + direction
      if (index < 0 || target < 0 || target >= route.length) return route
      const next = [...route]
      const [moved] = next.splice(index, 1)
      next.splice(target, 0, moved)
      return next.map((stage, order) => ({ ...stage, order }))
    })
  }

  const submit = () => {
    if (!name.trim()) return setError('Введите название программы')
    if (formats.length === 0) return setError('Выберите хотя бы один формат')
    if (customRoute && stages.length === 0) return setError('Добавьте хотя бы один этап маршрута')
    if (
      customRoute &&
      stages.some((stage) => !stage.title.trim() || stage.applicableFormats.length === 0)
    ) {
      return setError('У каждого этапа должны быть название и хотя бы один формат')
    }
    const start = localDateTimeToIso(startsAt)
    const end = localDateTimeToIso(endsAt)
    if (start && end && new Date(end).getTime() <= new Date(start).getTime()) {
      return setError('Дата завершения программы должна быть позже даты начала')
    }
    const route = customRoute
      ? stages.map((stage, index) => ({
          key: stage.key.trim() || `stage_${index + 1}`,
          type: stage.type,
          title: stage.title.trim(),
          order: index,
          required: stage.required ?? true,
          unlockAfterKey: stage.unlockAfterKey ?? null,
          deadlineDays: stage.deadlineDays ?? null,
          passingScore:
            stage.type === 'test' || stage.type === 'ai_exam'
              ? (stage.passingScore ?? Math.min(100, Math.max(0, Number(passingScore) || 0)))
              : null,
          maxAttempts:
            stage.type === 'test' || stage.type === 'ai_exam'
              ? (stage.maxAttempts ?? Math.max(1, Number(maxAttempts) || 1))
              : null,
          bonus: stage.bonus ?? 0,
          manualReview: stage.type === 'manual_review' || stage.manualReview,
          applicableFormats: stage.applicableFormats,
          contentUrl: stage.contentUrl?.trim() || null,
        }))
      : undefined
    if (route) {
      const keys = route.map((stage) => stage.key)
      if (new Set(keys).size !== keys.length)
        return setError('Ключи этапов маршрута должны быть уникальными')
      const invalidUnlock = route.find(
        (stage, index) =>
          stage.unlockAfterKey &&
          !route.slice(0, index).some((previous) => previous.key === stage.unlockAfterKey),
      )
      if (invalidUnlock)
        return setError(`Этап «${invalidUnlock.title}» должен зависеть только от предыдущего этапа`)
      const unresolvedOnlineStage = route.find(
        (stage) => stage.type === 'online_course' && !courseId && !stage.contentUrl,
      )
      if (unresolvedOnlineStage) {
        return setError(
          `Для этапа «${unresolvedOnlineStage.title}» привяжите онлайн-курс или укажите ссылку`,
        )
      }
    }
    const normalizedPassingScore = Math.min(100, Math.max(0, Number(passingScore) || 0))
    const normalizedMaxAttempts = Math.max(1, Number(maxAttempts) || 1)
    const normalizedBonus = Math.max(0, Number(bonus) || 0)
    const normalizedCoverUrl = coverUrl.trim() || null
    const normalizedTags = Array.from(
      new Set(
        tags
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean),
      ),
    )
    const metadata = {
      name: name.trim(),
      shortDescription: shortDescription.trim(),
      description: description.trim(),
      coverUrl: normalizedCoverUrl,
      category: category.trim(),
      manufacturer: manufacturer.trim(),
      brand: brand.trim(),
      product: product.trim(),
      language,
      startsAt: start,
      endsAt: end,
      normativeDays: Math.max(1, Number(normativeDays) || 14),
      tags: normalizedTags,
      status,
    }
    const callbacks = {
      onSuccess: (savedProgram: TrainingProgramDto) => {
        toast.push(
          mode === 'edit'
            ? `Программа «${savedProgram.name}» обновлена`
            : `Программа «${savedProgram.name}» создана`,
        )
        onClose()
      },
      onError: (requestError: unknown) => setError(describeError(requestError)),
    }
    if (mode === 'edit' && program) {
      const patch: UpdateTrainingProgramRequest = {
        ...metadata,
        clearCoverUrl: !normalizedCoverUrl,
        clearStartsAt: !start,
        clearEndsAt: !end,
      }
      if (
        hasVersionChanges(
          program,
          formats,
          courseId,
          normalizedPassingScore,
          normalizedMaxAttempts,
          normalizedBonus,
          route,
        )
      ) {
        Object.assign(patch, {
          allowedFormats: formats,
          onlineCourseId: courseId || null,
          clearOnlineCourseId: !courseId,
          passingScore: normalizedPassingScore,
          maxAttempts: normalizedMaxAttempts,
          completionBonus: normalizedBonus,
          stages: route,
        })
      }
      update.mutate({ id: program.id, patch }, callbacks)
      return
    }
    create.mutate(
      {
        ...metadata,
        managerId: program?.managerId ?? null,
        allowedFormats: formats,
        onlineCourseId: courseId || null,
        passingScore: normalizedPassingScore,
        maxAttempts: normalizedMaxAttempts,
        completionBonus: normalizedBonus,
        stages: route,
        status: mode === 'duplicate' ? 'draft' : status,
      },
      callbacks,
    )
  }

  const pending = create.isPending || update.isPending
  const modalTitle =
    mode === 'edit'
      ? 'Редактировать программу'
      : mode === 'duplicate'
        ? 'Дублировать программу'
        : 'Новая программа обучения'

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={modalTitle}
      subtitle="Для каждого формата используется собственная последовательность этапов"
      width={820}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={pending} onClick={submit}>
            {pending ? 'Сохраняем…' : mode === 'edit' ? 'Сохранить изменения' : 'Создать программу'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <ErrorBanner message={error} />}
        <Field label="Название">
          <Input value={name} onChange={(event) => setName(event.target.value)} autoFocus />
        </Field>
        <Field label="Краткое описание">
          <Input
            value={shortDescription}
            onChange={(event) => setShortDescription(event.target.value)}
          />
        </Field>
        <Field label="Полное описание" optional>
          <textarea
            className="inp min-h-24 resize-y py-2"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </Field>
        <Field label="Обложка" optional hint="Публичная HTTPS-ссылка на изображение программы">
          <Input
            value={coverUrl}
            onChange={(event) => setCoverUrl(event.target.value)}
            placeholder="https://…"
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Категория" optional>
            <Input value={category} onChange={(event) => setCategory(event.target.value)} />
          </Field>
          <Field label="Бренд" optional>
            <Input value={brand} onChange={(event) => setBrand(event.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Производитель" optional>
            <Input value={manufacturer} onChange={(event) => setManufacturer(event.target.value)} />
          </Field>
          <Field label="Продукт" optional>
            <Input value={product} onChange={(event) => setProduct(event.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Язык">
            <Select
              ariaLabel="Язык"
              value={language}
              onChange={setLanguage}
              options={[
                { value: 'ru', label: 'Русский' },
                { value: 'kk', label: 'Қазақша' },
              ]}
            />
          </Field>
          <Field label="Теги" optional hint="Через запятую">
            <Input
              value={tags}
              onChange={(event) => setTags(event.target.value)}
              placeholder="кардиология, запуск продукта"
            />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Начало программы" optional>
            <Input
              type="datetime-local"
              value={startsAt}
              onChange={(event) => setStartsAt(event.target.value)}
            />
          </Field>
          <Field label="Завершение программы" optional>
            <Input
              type="datetime-local"
              value={endsAt}
              onChange={(event) => setEndsAt(event.target.value)}
            />
          </Field>
        </div>
        <Field label="Доступные форматы">
          <div className="flex flex-wrap gap-4 rounded-lg border border-ink-100 px-3 py-2.5">
            {(Object.keys(FORMAT_LABEL) as TrainingFormat[]).map((format) => (
              <label
                key={format}
                className="flex cursor-pointer items-center gap-2 text-[13px] font-semibold text-ink-700"
              >
                <input
                  type="checkbox"
                  checked={formats.includes(format)}
                  onChange={() => toggleFormat(format)}
                />
                {FORMAT_LABEL[format]}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Онлайн-курс" optional hint="Используется в online и hybrid маршрутах">
          <Select
            ariaLabel="Онлайн-курс"
            value={courseId || '__none__'}
            onChange={(value) => setCourseId(value === '__none__' ? '' : value)}
            options={[
              { value: '__none__', label: 'Без привязанного курса' },
              ...courses
                .filter((course) => course.status === 'published')
                .map((course) => ({ value: course.id, label: course.title })),
            ]}
          />
        </Field>
        <Field label="Маршруты по форматам" hint="Этап попадает только в отмеченные форматы">
          {!customRoute ? (
            <div className="flex items-center justify-between gap-4 rounded-lg border border-ink-100 px-4 py-3">
              <div className="text-[12px] text-ink-600">
                Система создаст базовые маршруты: онлайн-материалы, очный этап и итоговый тест.
              </div>
              <Button size="sm" variant="outline" onClick={enableCustomRoute}>
                Настроить
              </Button>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg border border-ink-100">
              <div className="divide-y divide-ink-100">
                {stages.map((stage, index) => {
                  const stageKey = stage.key.trim() || `stage_${index + 1}`
                  const unlockOptions = [
                    { value: '__immediate__', label: 'Сразу' },
                    ...stages.slice(0, index).map((previous, previousIndex) => ({
                      value: previous.key.trim() || `stage_${previousIndex + 1}`,
                      label: previous.title || `Этап ${previousIndex + 1}`,
                    })),
                  ]
                  return (
                    <div key={stage.draftId} className="px-3 py-3">
                      <div className="grid grid-cols-[36px_1fr_180px_96px] gap-3">
                        <div className="num pt-2 text-center text-[12px] font-bold text-ink-500">
                          {index + 1}
                        </div>
                        <div className="flex min-w-0 flex-col gap-2">
                          <Input
                            aria-label={`Название этапа ${index + 1}`}
                            value={stage.title}
                            onChange={(event) =>
                              updateStage(stage.draftId, { title: event.target.value })
                            }
                          />
                          <div className="flex flex-wrap gap-3">
                            {formats.map((format) => (
                              <label
                                key={format}
                                className="flex items-center gap-1.5 text-[11px] font-semibold text-ink-600"
                              >
                                <input
                                  type="checkbox"
                                  checked={stage.applicableFormats.includes(format)}
                                  onChange={() =>
                                    updateStage(stage.draftId, {
                                      applicableFormats: stage.applicableFormats.includes(format)
                                        ? stage.applicableFormats.filter((item) => item !== format)
                                        : [...stage.applicableFormats, format],
                                    })
                                  }
                                />
                                {FORMAT_LABEL[format]}
                              </label>
                            ))}
                          </div>
                        </div>
                        <Select
                          ariaLabel={`Тип этапа ${index + 1}`}
                          value={stage.type}
                          onChange={(value) =>
                            updateStage(stage.draftId, {
                              type: value as TrainingStageType,
                              manualReview: value === 'manual_review' ? true : stage.manualReview,
                            })
                          }
                          options={STAGE_OPTIONS}
                        />
                        <div className="flex items-start justify-end gap-1">
                          <button
                            type="button"
                            title="Переместить выше"
                            aria-label={`Переместить этап ${index + 1} выше`}
                            disabled={index === 0}
                            className="mt-1 grid h-8 w-7 place-items-center text-ink-400 hover:text-ink-800 disabled:opacity-25"
                            onClick={() => moveStage(stage.draftId, -1)}
                          >
                            <IconArrowUp size={15} />
                          </button>
                          <button
                            type="button"
                            title="Переместить ниже"
                            aria-label={`Переместить этап ${index + 1} ниже`}
                            disabled={index === stages.length - 1}
                            className="mt-1 grid h-8 w-7 place-items-center text-ink-400 hover:text-ink-800 disabled:opacity-25"
                            onClick={() => moveStage(stage.draftId, 1)}
                          >
                            <IconArrowDown size={15} />
                          </button>
                          <button
                            type="button"
                            title="Удалить этап"
                            aria-label={`Удалить этап ${index + 1}`}
                            className="mt-1 grid h-8 w-7 place-items-center text-ink-400 hover:text-accent-danger"
                            onClick={() =>
                              setStages((route) =>
                                route.filter((item) => item.draftId !== stage.draftId),
                              )
                            }
                          >
                            <IconTrash size={16} />
                          </button>
                        </div>
                      </div>
                      <div className="ml-12 mt-3 grid grid-cols-2 gap-2 md:grid-cols-4">
                        <Field label="Ключ этапа">
                          <Input
                            aria-label={`Ключ этапа ${index + 1}`}
                            value={stage.key}
                            placeholder={stageKey}
                            onChange={(event) =>
                              updateStage(stage.draftId, { key: event.target.value })
                            }
                          />
                        </Field>
                        <Field label="Открыть после">
                          <Select
                            ariaLabel={`Условие открытия этапа ${index + 1}`}
                            value={stage.unlockAfterKey ?? '__immediate__'}
                            onChange={(value) =>
                              updateStage(stage.draftId, {
                                unlockAfterKey: value === '__immediate__' ? null : value,
                              })
                            }
                            options={unlockOptions}
                          />
                        </Field>
                        <Field label="Срок этапа, дней" optional>
                          <Input
                            aria-label={`Срок этапа ${index + 1}`}
                            type="number"
                            min={0}
                            value={stage.deadlineDays ?? ''}
                            onChange={(event) =>
                              updateStage(stage.draftId, {
                                deadlineDays:
                                  event.target.value === ''
                                    ? null
                                    : Math.max(0, Number(event.target.value)),
                              })
                            }
                          />
                        </Field>
                        <Field label="Бонус, ₸">
                          <Input
                            aria-label={`Бонус этапа ${index + 1}`}
                            type="number"
                            min={0}
                            value={stage.bonus ?? 0}
                            onChange={(event) =>
                              updateStage(stage.draftId, {
                                bonus: Math.max(0, Number(event.target.value) || 0),
                              })
                            }
                          />
                        </Field>
                      </div>
                      <div className="ml-12 mt-2 flex flex-wrap items-end gap-3">
                        <label className="flex h-10 items-center gap-2 text-[12px] font-semibold text-ink-700">
                          <input
                            type="checkbox"
                            checked={stage.required ?? true}
                            onChange={(event) =>
                              updateStage(stage.draftId, { required: event.target.checked })
                            }
                          />
                          Обязательный этап
                        </label>
                        {(stage.type === 'test' || stage.type === 'ai_exam') && (
                          <>
                            <Field label="Проходной балл">
                              <Input
                                aria-label={`Проходной балл этапа ${index + 1}`}
                                className="w-28"
                                type="number"
                                min={0}
                                max={100}
                                value={stage.passingScore ?? passingScore}
                                onChange={(event) =>
                                  updateStage(stage.draftId, {
                                    passingScore: Number(event.target.value),
                                  })
                                }
                              />
                            </Field>
                            <Field label="Попыток">
                              <Input
                                aria-label={`Попыток этапа ${index + 1}`}
                                className="w-24"
                                type="number"
                                min={1}
                                value={stage.maxAttempts ?? maxAttempts}
                                onChange={(event) =>
                                  updateStage(stage.draftId, {
                                    maxAttempts: Math.max(1, Number(event.target.value) || 1),
                                  })
                                }
                              />
                            </Field>
                            <label className="flex h-10 items-center gap-2 text-[12px] font-semibold text-ink-700">
                              <input
                                type="checkbox"
                                checked={stage.manualReview ?? false}
                                onChange={(event) =>
                                  updateStage(stage.draftId, { manualReview: event.target.checked })
                                }
                              />
                              Ручная проверка
                            </label>
                          </>
                        )}
                      </div>
                      {(stage.type === 'material' || stage.type === 'online_course') && (
                        <div className="ml-12 mt-2">
                          <Input
                            aria-label={`Ссылка этапа ${index + 1}`}
                            placeholder="Ссылка на материал (необязательно)"
                            value={stage.contentUrl ?? ''}
                            onChange={(event) =>
                              updateStage(stage.draftId, { contentUrl: event.target.value })
                            }
                          />
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
              <div className="hairline flex justify-between border-t px-3 py-2">
                <Button
                  size="sm"
                  variant="ghost"
                  leading={<IconPlus size={14} />}
                  onClick={addStage}
                >
                  Добавить этап
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setCustomRoute(false)
                    setStages([])
                  }}
                >
                  Вернуть базовый маршрут
                </Button>
              </div>
            </div>
          )}
        </Field>
        <div className="grid grid-cols-4 gap-3">
          <Field label="Норматив, дней">
            <Input
              type="number"
              min={1}
              value={normativeDays}
              onChange={(event) => setNormativeDays(event.target.value)}
            />
          </Field>
          <Field label="Проходной балл">
            <Input
              type="number"
              min={0}
              max={100}
              value={passingScore}
              onChange={(event) => setPassingScore(event.target.value)}
            />
          </Field>
          <Field label="Попыток">
            <Input
              type="number"
              min={1}
              value={maxAttempts}
              onChange={(event) => setMaxAttempts(event.target.value)}
            />
          </Field>
          <Field label="Бонус, ₸">
            <Input
              type="number"
              min={0}
              value={bonus}
              onChange={(event) => setBonus(event.target.value)}
            />
          </Field>
        </div>
        <Field label="Статус программы">
          <Select
            ariaLabel="Статус программы"
            value={status}
            onChange={(value) => setStatus(value as TrainingProgramStatus)}
            options={[
              { value: 'draft', label: 'Черновик' },
              { value: 'review', label: 'На согласовании' },
              { value: 'published', label: 'Опубликовать' },
            ]}
          />
        </Field>
      </div>
    </Modal>
  )
}

export function CreateEventModal({
  open,
  onClose,
  programs,
  event = null,
}: CommonModalProps & { programs: TrainingProgramDto[]; event?: OfflineEventDto | null }) {
  const toast = useToast()
  const create = useCreateOfflineEvent()
  const update = useUpdateOfflineEvent()
  const [programId, setProgramId] = useState(() => event?.programId ?? '')
  const [title, setTitle] = useState(() => event?.title ?? '')
  const [startsAt, setStartsAt] = useState(() => isoToLocalDateTime(event?.startsAt ?? null))
  const [endsAt, setEndsAt] = useState(() => isoToLocalDateTime(event?.endsAt ?? null))
  const [registrationDeadline, setRegistrationDeadline] = useState(() =>
    isoToLocalDateTime(event?.registrationDeadline ?? null),
  )
  const [region, setRegion] = useState(() => event?.region ?? '')
  const [city, setCity] = useState(() => event?.city ?? 'Алматы')
  const [address, setAddress] = useState(() => event?.address ?? '')
  const [mapUrl, setMapUrl] = useState(() => event?.mapUrl ?? '')
  const [organizer, setOrganizer] = useState(() => event?.organizer ?? 'INKAR')
  const [capacity, setCapacity] = useState(() => String(event?.capacity ?? 30))
  const [materialsUrl, setMaterialsUrl] = useState(() => event?.materialsUrl ?? '')
  const [comment, setComment] = useState(() => event?.comment ?? '')
  const [status, setStatus] = useState<OfflineEventDto['status']>(
    () => event?.status ?? 'registration',
  )
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    const start = localDateTimeToIso(startsAt)
    const end = localDateTimeToIso(endsAt)
    if (!programId || !title.trim() || !start || !end) {
      return setError('Заполните программу, название, начало и завершение')
    }
    if (new Date(end).getTime() <= new Date(start).getTime()) {
      return setError('Завершение должно быть позже начала')
    }
    const deadline = localDateTimeToIso(registrationDeadline)
    if (deadline && new Date(deadline).getTime() > new Date(start).getTime()) {
      return setError('Регистрация должна завершаться до начала события')
    }
    if (
      event &&
      status === 'cancelled' &&
      event.status !== 'cancelled' &&
      !window.confirm(
        'Отменить событие? Участники получат уведомление, а незавершённые назначения будут отвязаны.',
      )
    ) {
      return
    }

    const payload = {
      title: title.trim(),
      startsAt: start,
      endsAt: end,
      registrationDeadline: deadline,
      region: region.trim(),
      city: city.trim(),
      address: address.trim(),
      mapUrl: mapUrl.trim(),
      organizer: organizer.trim(),
      capacity: Math.max(1, Number(capacity) || 1),
      materialsUrl: materialsUrl.trim(),
      comment: comment.trim(),
      status,
    }
    const callbacks = {
      onSuccess: () => {
        toast.push(event ? 'Событие обновлено' : 'Очное событие создано')
        onClose()
      },
      onError: (requestError: unknown) => setError(describeError(requestError)),
    }
    if (event) {
      update.mutate(
        {
          id: event.id,
          patch: {
            ...payload,
            clearRegistrationDeadline: !deadline,
          },
        },
        callbacks,
      )
    } else {
      create.mutate({ programId, ...payload }, callbacks)
    }
  }

  const pending = create.isPending || update.isPending

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={event ? 'Редактировать событие' : 'Новое очное событие'}
      subtitle="Расписание, место, регистрация и материалы"
      width={720}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={pending} onClick={submit}>
            {pending ? 'Сохраняем…' : event ? 'Сохранить' : 'Создать событие'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <ErrorBanner message={error} />}
        <Field label="Программа">
          {event ? (
            <Input value={event.programName} readOnly aria-label="Программа" />
          ) : (
            <Select
              ariaLabel="Программа"
              value={programId}
              onChange={setProgramId}
              options={programs
                .filter((program) => program.allowedFormats.some((format) => format !== 'online'))
                .map((program) => ({ value: program.id, label: program.name }))}
            />
          )}
        </Field>
        <Field label="Название события">
          <Input value={title} onChange={(event) => setTitle(event.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Начало">
            <Input
              type="datetime-local"
              value={startsAt}
              onChange={(event) => setStartsAt(event.target.value)}
            />
          </Field>
          <Field label="Завершение">
            <Input
              type="datetime-local"
              value={endsAt}
              onChange={(event) => setEndsAt(event.target.value)}
            />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Регистрация до" optional>
            <Input
              type="datetime-local"
              value={registrationDeadline}
              onChange={(event) => setRegistrationDeadline(event.target.value)}
            />
          </Field>
          <Field label="Статус">
            <Select
              ariaLabel="Статус события"
              value={status}
              onChange={(value) => setStatus(value as OfflineEventDto['status'])}
              options={Object.entries(EVENT_STATUS_LABEL).map(([value, label]) => ({
                value,
                label,
              }))}
            />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Область / регион" optional>
            <Input value={region} onChange={(event) => setRegion(event.target.value)} />
          </Field>
          <Field label="Город">
            <Input value={city} onChange={(event) => setCity(event.target.value)} />
          </Field>
        </div>
        <Field label="Адрес">
          <Input value={address} onChange={(event) => setAddress(event.target.value)} />
        </Field>
        <Field label="Ссылка на карту" optional>
          <Input
            value={mapUrl}
            onChange={(event) => setMapUrl(event.target.value)}
            placeholder="https://maps…"
          />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Организатор">
            <Input value={organizer} onChange={(event) => setOrganizer(event.target.value)} />
          </Field>
          <Field label="Вместимость">
            <Input
              type="number"
              min={1}
              value={capacity}
              onChange={(event) => setCapacity(event.target.value)}
            />
          </Field>
        </div>
        <Field label="Материалы для участников" optional>
          <Input
            value={materialsUrl}
            onChange={(event) => setMaterialsUrl(event.target.value)}
            placeholder="https://…"
          />
        </Field>
        <Field label="Комментарий" optional>
          <textarea
            className="inp min-h-20 resize-y py-2"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
        </Field>
      </div>
    </Modal>
  )
}

export function AssignTrainingModal({
  open,
  onClose,
  programs,
  events,
  pharmacists,
  initialPharmacistIds = [],
}: CommonModalProps & {
  programs: TrainingProgramDto[]
  events: OfflineEventDto[]
  pharmacists: PharmacistDto[]
  initialPharmacistIds?: string[]
}) {
  const toast = useToast()
  const assign = useCreateTrainingAssignments()
  const [programId, setProgramId] = useState('')
  const [format, setFormat] = useState<TrainingFormat>('online')
  const [eventId, setEventId] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [dueAt, setDueAt] = useState('')
  const [priority, setPriority] = useState('normal')
  const [required, setRequired] = useState(true)
  const [duplicatePolicy, setDuplicatePolicy] = useState<DuplicateAssignmentPolicy>('skip')
  const [selected, setSelected] = useState<string[]>(() =>
    initialPharmacistIds.filter((id) =>
      pharmacists.some((pharmacist) => pharmacist.id === id && pharmacist.status === 'active'),
    ),
  )
  const [query, setQuery] = useState('')
  const [cityFilter, setCityFilter] = useState('__all__')
  const [pharmacyFilter, setPharmacyFilter] = useState('__all__')
  const [error, setError] = useState<string | null>(null)

  const program = programs.find((item) => item.id === programId)
  const eligibleEvents = events.filter(
    (event) =>
      event.programId === programId &&
      !['cancelled', 'completed', 'archived'].includes(event.status),
  )
  const cityOptions = useMemo(
    () =>
      Array.from(
        new Set(
          pharmacists
            .filter((pharmacist) => pharmacist.status === 'active' && pharmacist.city)
            .map((pharmacist) => pharmacist.city),
        ),
      ).sort(),
    [pharmacists],
  )
  const pharmacyOptions = useMemo(
    () =>
      Array.from(
        new Map(
          pharmacists
            .filter(
              (pharmacist) =>
                pharmacist.status === 'active' &&
                (cityFilter === '__all__' || pharmacist.city === cityFilter),
            )
            .map((pharmacist) => [pharmacist.pharmacyId, pharmacist.pharmacyName]),
        ).entries(),
      ).sort((left, right) => left[1].localeCompare(right[1], 'ru')),
    [cityFilter, pharmacists],
  )
  const visiblePharmacists = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return pharmacists.filter(
      (pharmacist) =>
        pharmacist.status === 'active' &&
        (cityFilter === '__all__' || pharmacist.city === cityFilter) &&
        (pharmacyFilter === '__all__' || pharmacist.pharmacyId === pharmacyFilter) &&
        (!normalized ||
          `${pharmacist.name} ${pharmacist.pharmacyName} ${pharmacist.city}`
            .toLowerCase()
            .includes(normalized)),
    )
  }, [cityFilter, pharmacists, pharmacyFilter, query])

  const selectAllVisible = () => {
    const ids = visiblePharmacists.map((pharmacist) => pharmacist.id)
    setSelected((current) => Array.from(new Set([...current, ...ids])))
  }

  const submit = () => {
    if (!programId || selected.length === 0)
      return setError('Выберите программу и хотя бы одного фармацевта')
    if (format !== 'online' && !eventId)
      return setError('Для очного или гибридного назначения выберите событие')
    const start = localDateTimeToIso(startsAt)
    const due = localDateTimeToIso(dueAt)
    if (start && due && new Date(due).getTime() <= new Date(start).getTime()) {
      return setError('Срок завершения должен быть позже даты начала')
    }
    assign.mutate(
      {
        programId,
        pharmacistIds: selected,
        format,
        eventId: eventId || null,
        startsAt: start,
        dueAt: due,
        priority: priority as 'low' | 'normal' | 'high' | 'critical',
        required,
        duplicatePolicy,
      },
      {
        onSuccess: (result) => {
          toast.push(`Создано: ${result.created}, пропущено дублей: ${result.skipped}`)
          onClose()
        },
        onError: (requestError) => setError(describeError(requestError)),
      },
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Назначить обучение"
      subtitle="Массовое назначение с защитой от дублей"
      width={760}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button disabled={assign.isPending} onClick={submit}>
            Назначить ({selected.length})
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <ErrorBanner message={error} />}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Программа">
            <Select
              ariaLabel="Программа"
              value={programId}
              onChange={(value) => {
                setProgramId(value)
                const next = programs.find((item) => item.id === value)
                const nextFormat = next?.allowedFormats[0] ?? 'online'
                setFormat(nextFormat)
                setEventId('')
              }}
              options={programs
                .filter((item) => ['published', 'scheduled', 'paused'].includes(item.status))
                .map((item) => ({ value: item.id, label: item.name }))}
            />
          </Field>
          <Field label="Формат">
            <Select
              ariaLabel="Формат"
              value={format}
              onChange={(value) => {
                setFormat(value as TrainingFormat)
                if (value === 'online') setEventId('')
              }}
              options={(program?.allowedFormats ?? ['online']).map((value) => ({
                value,
                label: FORMAT_LABEL[value],
              }))}
            />
          </Field>
        </div>
        {format !== 'online' && (
          <Field label="Очное событие">
            <Select
              ariaLabel="Очное событие"
              value={eventId}
              onChange={setEventId}
              options={eligibleEvents.map((event) => ({
                value: event.id,
                label: `${event.title} · ${dateTime(event.startsAt)} · ${event.occupied}/${event.capacity}`,
              }))}
            />
          </Field>
        )}
        <div className="grid grid-cols-3 gap-3">
          <Field label="Начало" optional>
            <Input
              type="datetime-local"
              value={startsAt}
              onChange={(event) => setStartsAt(event.target.value)}
            />
          </Field>
          <Field label="Срок завершения" optional>
            <Input
              type="datetime-local"
              value={dueAt}
              onChange={(event) => setDueAt(event.target.value)}
            />
          </Field>
          <Field label="Приоритет">
            <Select
              ariaLabel="Приоритет"
              value={priority}
              onChange={setPriority}
              options={[
                { value: 'low', label: 'Низкий' },
                { value: 'normal', label: 'Обычный' },
                { value: 'high', label: 'Высокий' },
                { value: 'critical', label: 'Критический' },
              ]}
            />
          </Field>
        </div>
        <div className="grid grid-cols-[1fr_auto] items-end gap-3">
          <Field label="Если активное назначение уже есть">
            <Select
              ariaLabel="Политика дублей"
              value={duplicatePolicy}
              onChange={(value) => setDuplicatePolicy(value as DuplicateAssignmentPolicy)}
              options={[
                { value: 'skip', label: 'Пропустить без изменений' },
                { value: 'update_deadline', label: 'Обновить срок и параметры' },
                { value: 'repeat', label: 'Не создавать второй активный повтор' },
              ]}
            />
          </Field>
          <label className="flex h-10 items-center gap-2 pb-0.5 text-[13px] font-semibold text-ink-700">
            <input
              type="checkbox"
              checked={required}
              onChange={(event) => setRequired(event.target.checked)}
            />
            Обязательное обучение
          </label>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Город" optional>
            <Select
              ariaLabel="Город"
              value={cityFilter}
              onChange={(value) => {
                setCityFilter(value)
                setPharmacyFilter('__all__')
              }}
              options={[
                { value: '__all__', label: 'Все города' },
                ...cityOptions.map((city) => ({ value: city, label: city })),
              ]}
            />
          </Field>
          <Field label="Аптека" optional>
            <Select
              ariaLabel="Аптека"
              value={pharmacyFilter}
              onChange={setPharmacyFilter}
              options={[
                { value: '__all__', label: 'Все аптеки' },
                ...pharmacyOptions.map(([value, label]) => ({ value, label })),
              ]}
            />
          </Field>
        </div>
        <div className="hairline overflow-hidden rounded-lg border">
          <div className="flex items-center justify-between gap-3 border-b border-ink-100 bg-paper-hover px-3 py-2">
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder="Фармацевт, аптека, город"
              className="!h-9 flex-1"
            />
            <button
              type="button"
              onClick={selectAllVisible}
              className="text-[12px] font-bold text-brand-green-700"
            >
              Выбрать найденных
            </button>
          </div>
          <div className="scrollbar-thin max-h-56 divide-y divide-ink-100 overflow-auto">
            {visiblePharmacists.map((pharmacist) => (
              <label
                key={pharmacist.id}
                className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-paper-hover"
              >
                <input
                  type="checkbox"
                  checked={selected.includes(pharmacist.id)}
                  onChange={() =>
                    setSelected((current) =>
                      current.includes(pharmacist.id)
                        ? current.filter((id) => id !== pharmacist.id)
                        : [...current, pharmacist.id],
                    )
                  }
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-bold text-ink-900">
                    {pharmacist.name}
                  </span>
                  <span className="block truncate text-[11px] text-ink-500">
                    {pharmacist.pharmacyName} · {pharmacist.city}
                  </span>
                </span>
              </label>
            ))}
          </div>
        </div>
      </div>
    </Modal>
  )
}

export function AttendanceModal({
  event,
  onClose,
}: {
  event: OfflineEventDto | null
  onClose: () => void
}) {
  const toast = useToast()
  const { data: participants = [], isLoading } = useEventParticipants(event?.id ?? null)
  const mark = useMarkAttendance()
  const [attendanceDetails, setAttendanceDetails] = useState<
    Record<string, { score: string; comment: string }>
  >({})

  const setStatus = (participantId: string, status: EventParticipantStatus) => {
    if (!event) return
    const participant = participants.find((item) => item.id === participantId)
    const details = attendanceDetails[participantId]
    const rawScore = details?.score ?? (participant?.score == null ? '' : String(participant.score))
    const score = rawScore === '' ? null : Number(rawScore)
    if (score != null && (!Number.isFinite(score) || score < 0 || score > 100)) {
      toast.push('Оценка должна быть от 0 до 100')
      return
    }
    mark.mutate(
      {
        eventId: event.id,
        participantId,
        request: {
          status,
          method: 'manual',
          score,
          comment: (details?.comment ?? participant?.trainerComment ?? '').trim() || null,
        },
      },
      {
        onSuccess: () =>
          toast.push(
            status === 'attended' || status === 'late'
              ? 'Посещение подтверждено'
              : 'Статус посещения сохранён',
          ),
        onError: (requestError) => toast.push(describeError(requestError)),
      },
    )
  }

  return (
    <Modal
      open={!!event}
      onClose={onClose}
      title={event?.title ?? 'Участники'}
      subtitle={event ? `${dateTime(event.startsAt)} · ${event.city}, ${event.address}` : undefined}
      width={1040}
    >
      {isLoading ? (
        <div className="py-8 text-center text-[13px] text-ink-500">Загружаем участников…</div>
      ) : participants.length === 0 ? (
        <div className="py-8 text-center text-[13px] text-ink-500">
          На событие пока никто не назначен
        </div>
      ) : (
        <table className="w-full text-[13px]">
          <thead className="hairline border-b text-left text-[11px] font-bold uppercase text-ink-500">
            <tr>
              <th className="py-2 pr-3">Фармацевт</th>
              <th className="px-3 py-2">Аптека</th>
              <th className="px-3 py-2">Статус</th>
              <th className="px-3 py-2">Оценка</th>
              <th className="px-3 py-2">Комментарий тренера</th>
              <th className="py-2 pl-3 text-right">Отметка</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {participants.map((participant) => (
              <tr key={participant.id}>
                <td className="py-2.5 pr-3 font-bold text-ink-900">{participant.pharmacistName}</td>
                <td className="px-3 py-2.5 text-ink-600">{participant.pharmacyName}</td>
                <td className="px-3 py-2.5">
                  <span className={`chip ${chipClass(participant.status)}`}>
                    {PARTICIPANT_STATUS_LABEL[participant.status]}
                  </span>
                </td>
                <td className="w-24 px-3 py-2.5">
                  <Input
                    aria-label={`Оценка ${participant.pharmacistName}`}
                    type="number"
                    min={0}
                    max={100}
                    value={
                      attendanceDetails[participant.id]?.score ??
                      (participant.score == null ? '' : String(participant.score))
                    }
                    onChange={(changeEvent) =>
                      setAttendanceDetails((current) => ({
                        ...current,
                        [participant.id]: {
                          score: changeEvent.target.value,
                          comment:
                            current[participant.id]?.comment ?? participant.trainerComment ?? '',
                        },
                      }))
                    }
                  />
                </td>
                <td className="min-w-52 px-3 py-2.5">
                  <Input
                    aria-label={`Комментарий ${participant.pharmacistName}`}
                    value={
                      attendanceDetails[participant.id]?.comment ?? participant.trainerComment ?? ''
                    }
                    onChange={(changeEvent) =>
                      setAttendanceDetails((current) => ({
                        ...current,
                        [participant.id]: {
                          score:
                            current[participant.id]?.score ??
                            (participant.score == null ? '' : String(participant.score)),
                          comment: changeEvent.target.value,
                        },
                      }))
                    }
                  />
                </td>
                <td className="py-2.5 pl-3">
                  <div className="flex flex-wrap justify-end gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={mark.isPending}
                      onClick={() => setStatus(participant.id, 'excused')}
                    >
                      Уважительная причина
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={mark.isPending}
                      onClick={() => setStatus(participant.id, 'no_show')}
                    >
                      Не явился
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={mark.isPending}
                      onClick={() => setStatus(participant.id, 'late')}
                    >
                      Опоздал
                    </Button>
                    <Button
                      size="sm"
                      disabled={mark.isPending}
                      onClick={() => setStatus(participant.id, 'attended')}
                    >
                      Присутствовал
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Modal>
  )
}

export function AssessmentResultModal({
  target,
  onClose,
}: {
  target: { assignment: TrainingAssignmentDto; stage: TrainingAssignmentStageDto } | null
  onClose: () => void
}) {
  const toast = useToast()
  const record = useRecordAssessmentResult()
  const [score, setScore] = useState('')
  const [passed, setPassed] = useState('auto')
  const [feedback, setFeedback] = useState('')
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setScore('')
    setPassed('auto')
    setFeedback('')
    setError(null)
    onClose()
  }

  const submit = () => {
    if (!target) return
    const numericScore = Number(score)
    if (!Number.isFinite(numericScore) || numericScore < 0 || numericScore > 100) {
      return setError('Укажите результат от 0 до 100')
    }
    record.mutate(
      {
        assignmentId: target.assignment.id,
        stageId: target.stage.id,
        request: {
          score: numericScore,
          passed: passed === 'auto' ? null : passed === 'passed',
          feedback: feedback.trim(),
        },
      },
      {
        onSuccess: (assignment) => {
          toast.push(
            assignment.status === 'completed'
              ? 'Результат сохранён, программа завершена'
              : 'Результат сохранён',
          )
          close()
        },
        onError: (requestError) => setError(describeError(requestError)),
      },
    )
  }

  return (
    <Modal
      open={!!target}
      onClose={close}
      title="Зафиксировать результат"
      subtitle={target ? `${target.assignment.pharmacistName} · ${target.stage.title}` : undefined}
      width={560}
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Отмена
          </Button>
          <Button disabled={record.isPending} onClick={submit}>
            Сохранить результат
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <ErrorBanner message={error} />}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Балл, %">
            <Input
              type="number"
              min={0}
              max={100}
              value={score}
              onChange={(event) => setScore(event.target.value)}
              autoFocus
            />
          </Field>
          <Field label="Итог">
            <Select
              ariaLabel="Итог аттестации"
              value={passed}
              onChange={setPassed}
              options={[
                { value: 'auto', label: 'По проходному баллу' },
                { value: 'passed', label: 'Зачёт' },
                { value: 'failed', label: 'Незачёт' },
              ]}
            />
          </Field>
        </div>
        <Field label="Комментарий" optional>
          <textarea
            className="min-h-24 w-full resize-y rounded-lg border border-ink-200 bg-white px-3 py-2 text-[13px] outline-none focus:border-brand-green-500"
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
          />
        </Field>
        {target?.stage.passingScore != null && (
          <div className="rounded-lg bg-paper-hover px-3 py-2 text-[12px] text-ink-600">
            Проходной балл: <b>{target.stage.passingScore}%</b>. Попытка{' '}
            {target.stage.attemptsUsed + 1} из {target.stage.maxAttempts ?? 'без ограничения'}.
          </div>
        )}
      </div>
    </Modal>
  )
}

export function ChangeAssignmentFormatModal({
  assignment,
  program,
  events,
  onClose,
}: {
  assignment: TrainingAssignmentDto | null
  program: TrainingProgramDto | null
  events: OfflineEventDto[]
  onClose: () => void
}) {
  const toast = useToast()
  const change = useChangeAssignmentFormat()
  const history = useAssignmentFormatHistory(assignment?.id ?? null)
  const [format, setFormat] = useState<TrainingFormat>(assignment?.format ?? 'online')
  const [eventId, setEventId] = useState(assignment?.event?.id ?? '')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    setReason('')
    setError(null)
    onClose()
  }
  const eligibleEvents = events.filter(
    (event) =>
      event.programId === assignment?.programId &&
      !['cancelled', 'completed', 'archived'].includes(event.status),
  )
  const submit = () => {
    if (!assignment) return
    if (!reason.trim()) return setError('Укажите причину изменения формата')
    change.mutate(
      {
        assignmentId: assignment.id,
        request: {
          format,
          eventId: format === 'online' ? null : eventId || null,
          reason: reason.trim(),
        },
      },
      {
        onSuccess: () => {
          toast.push('Формат назначения изменён, завершённый прогресс сохранён')
          close()
        },
        onError: (requestError) => setError(describeError(requestError)),
      },
    )
  }

  return (
    <Modal
      open={!!assignment}
      onClose={close}
      title="Изменить формат назначения"
      subtitle={assignment ? `${assignment.pharmacistName} · ${assignment.programName}` : undefined}
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Отмена
          </Button>
          <Button disabled={change.isPending} onClick={submit}>
            Применить
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error && <ErrorBanner message={error} />}
        <Field label="Новый формат">
          <Select
            ariaLabel="Новый формат"
            value={format}
            onChange={(value) => {
              setFormat(value as TrainingFormat)
              if (value === 'online') setEventId('')
            }}
            options={(program?.allowedFormats ?? [assignment?.format ?? 'online']).map((value) => ({
              value,
              label: FORMAT_LABEL[value],
            }))}
          />
        </Field>
        {format !== 'online' && (
          <Field label="Очное событие" optional hint="Можно назначить позже">
            <Select
              ariaLabel="Очное событие"
              value={eventId}
              onChange={setEventId}
              placeholder="Ожидает выбора события"
              options={eligibleEvents.map((event) => ({
                value: event.id,
                label: `${event.title} · ${dateTime(event.startsAt)}`,
              }))}
            />
          </Field>
        )}
        <Field label="Причина изменения">
          <Input value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div>
          <div className="mb-2 text-[11px] font-bold uppercase text-ink-500">История формата</div>
          {history.isLoading ? (
            <div className="text-[12px] text-ink-500">Загружаем историю…</div>
          ) : history.data?.length ? (
            <div className="divide-y divide-ink-100 rounded-lg border border-ink-100">
              {history.data.map((row) => (
                <div key={row.id} className="px-3 py-2 text-[12px]">
                  <div className="font-bold text-ink-800">
                    {FORMAT_LABEL[row.oldFormat]} → {FORMAT_LABEL[row.newFormat]}
                  </div>
                  <div className="text-ink-500">
                    {dateTime(row.changedAt)} · {row.changedByName} · {row.reason}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-[12px] text-ink-500">Формат назначения ещё не менялся</div>
          )}
        </div>
      </div>
    </Modal>
  )
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="rounded-lg bg-surface-danger px-3 py-2 text-[12px] font-semibold text-accent-danger">
      {message}
    </div>
  )
}

export function EventSummary({ event }: { event: OfflineEventDto }) {
  return (
    <span className={`chip ${chipClass(event.status)}`}>
      {EVENT_STATUS_LABEL[event.status]} · {formatNum(event.occupied)}/{formatNum(event.capacity)}
    </span>
  )
}

export function EventQrModal({
  event,
  onClose,
}: {
  event: OfflineEventDto | null
  onClose: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const toast = useToast()
  const qrQuery = useTrainingEventQr(event?.id ?? null)

  useEffect(() => {
    if (!canvasRef.current || !qrQuery.data?.payload) return
    void QRCode.toCanvas(canvasRef.current, qrQuery.data.payload, {
      width: 320,
      margin: 2,
      errorCorrectionLevel: 'M',
      color: { dark: '#211c18', light: '#ffffff' },
    })
  }, [qrQuery.data?.payload])

  const copyPayload = async () => {
    if (!qrQuery.data?.payload) return
    try {
      await navigator.clipboard.writeText(qrQuery.data.payload)
      toast.push('Ссылка регистрации скопирована')
    } catch {
      toast.push('Не удалось скопировать ссылку')
    }
  }

  return (
    <Modal
      open={event != null}
      onClose={onClose}
      title="QR-код посещаемости"
      subtitle={event ? `${event.title} · ${dateTime(event.startsAt)}` : undefined}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Закрыть
          </Button>
          <Button variant="outline" disabled={!qrQuery.data} onClick={copyPayload}>
            Скопировать ссылку
          </Button>
        </>
      }
    >
      {qrQuery.isLoading ? (
        <div className="py-12 text-center text-[13px] font-semibold text-ink-500">
          Формируем QR-код…
        </div>
      ) : qrQuery.isError ? (
        <ErrorBanner message={describeError(qrQuery.error)} />
      ) : (
        <div className="flex flex-col items-center gap-3">
          <canvas
            ref={canvasRef}
            className="h-80 w-80 max-w-full"
            aria-label="QR-код регистрации на мероприятии"
          />
          <p className="max-w-sm text-center text-[12px] text-ink-500">
            Фармацевт сканирует код в мобильном разделе обучения. Сервер сверяет назначение, событие
            и статус участника до фиксации посещения.
          </p>
        </div>
      )}
    </Modal>
  )
}
