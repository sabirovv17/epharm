import type {
  EventParticipantStatus,
  OfflineEventStatus,
  TrainingAssignmentStatus,
  TrainingFormat,
  TrainingProgramStatus,
  TrainingStageType,
} from '@/lib/api-types'

export const FORMAT_LABEL: Record<TrainingFormat, string> = {
  online: 'Онлайн',
  hybrid: 'Гибрид',
  offline: 'Очно',
}

export const PROGRAM_STATUS_LABEL: Record<TrainingProgramStatus, string> = {
  draft: 'Черновик',
  review: 'На согласовании',
  scheduled: 'Запланирована',
  published: 'Опубликована',
  paused: 'Приостановлена',
  completed: 'Завершена',
  archived: 'Архив',
}

export const ASSIGNMENT_STATUS_LABEL: Record<TrainingAssignmentStatus, string> = {
  scheduled: 'Запланировано',
  not_started: 'Не начато',
  in_progress: 'В процессе',
  waiting_online: 'Онлайн-этап',
  waiting_test: 'Ожидает тест',
  waiting_exam: 'Ожидает экзамен',
  waiting_event_selection: 'Нужно событие',
  waiting_offline: 'Очное обучение',
  waiting_attendance: 'Ожидает отметку',
  waiting_review: 'На проверке',
  retake_required: 'Пересдача',
  completed: 'Завершено',
  overdue: 'Просрочено',
  paused: 'Пауза',
  cancelled: 'Отменено',
}

export const EVENT_STATUS_LABEL: Record<OfflineEventStatus, string> = {
  draft: 'Черновик',
  registration: 'Регистрация',
  scheduled: 'Запланировано',
  in_progress: 'Идёт сейчас',
  completed: 'Завершено',
  cancelled: 'Отменено',
  archived: 'Архив',
}

export const PARTICIPANT_STATUS_LABEL: Record<EventParticipantStatus, string> = {
  registered: 'Зарегистрирован',
  confirmed: 'Подтверждён',
  attended: 'Присутствовал',
  late: 'Опоздал',
  no_show: 'Не явился',
  excused: 'Уважительная причина',
  cancelled: 'Отменено',
  waitlisted: 'Лист ожидания',
}

export const STAGE_TYPE_LABEL: Record<TrainingStageType, string> = {
  material: 'Материалы',
  online_course: 'Онлайн-курс',
  test: 'Тест',
  ai_exam: 'AI-экзамен',
  offline_event: 'Очное событие',
  manual_review: 'Ручная проверка',
}

export function dateTime(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function dateOnly(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(value))
}

export function chipClass(status: string): string {
  if (['published', 'completed', 'attended', 'late', 'valid', 'registration'].includes(status)) {
    return 'chip-green'
  }
  if (['overdue', 'failed', 'cancelled', 'no_show', 'retake_required'].includes(status)) {
    return 'chip-red'
  }
  if (['scheduled', 'waiting_review', 'confirmed', 'in_progress'].includes(status)) {
    return 'chip-blue'
  }
  if (['paused', 'waitlisted', 'review'].includes(status)) return 'chip-amber'
  return 'chip-ink'
}

export function localDateTimeToIso(value: string): string | null {
  if (!value) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

export function isoToLocalDateTime(value: string | null): string {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return ''
  const local = new Date(parsed.getTime() - parsed.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}
