import type { ComponentType } from 'react'
import {
  IconCalendar,
  IconCheck,
  IconDashboard,
  IconLayers,
  IconLift,
  IconList,
  IconPlayCircle,
  IconSettings,
  IconShield,
  IconUsers,
} from '@/ui/icons'

export type TrainingTab =
  | 'overview'
  | 'programs'
  | 'courses'
  | 'events'
  | 'assignments'
  | 'attendance'
  | 'results'
  | 'certificates'
  | 'analytics'
  | 'settings'

type TrainingNavigationIcon = ComponentType<{ size?: number; className?: string }>

export interface TrainingNavigationItem {
  value: TrainingTab
  label: string
  sidebarLabel: string
  title: string
  subtitle: string
  group: 'Обзор' | 'Контент' | 'Прохождение' | 'Отчётность' | 'Система'
  Icon: TrainingNavigationIcon
}

/**
 * Единый контракт навигации учебного workspace.
 * Порядок повторяет прежние вкладки LMS, чтобы перенос в sidebar не менял ментальную модель.
 */
export const TRAINING_NAVIGATION: readonly TrainingNavigationItem[] = [
  {
    value: 'overview',
    label: 'Дашборд',
    sidebarLabel: 'Дашборд',
    title: 'Дашборд обучения',
    subtitle: 'Ключевые показатели программ, прохождения, посещаемости и вознаграждений',
    group: 'Обзор',
    Icon: IconDashboard,
  },
  {
    value: 'programs',
    label: 'Программы',
    sidebarLabel: 'Программы',
    title: 'Программы',
    subtitle: 'Учебные маршруты, форматы, сроки и правила прохождения',
    group: 'Контент',
    Icon: IconLayers,
  },
  {
    value: 'courses',
    label: 'Онлайн-курсы',
    sidebarLabel: 'Онлайн-курсы',
    title: 'Онлайн-курсы',
    subtitle: 'Уроки, видео, материалы и содержание дистанционного обучения',
    group: 'Контент',
    Icon: IconPlayCircle,
  },
  {
    value: 'events',
    label: 'Офлайн-мероприятия',
    sidebarLabel: 'Офлайн-мероприятия',
    title: 'Офлайн-мероприятия',
    subtitle: 'Расписание очных событий, площадки, тренеры и регистрация',
    group: 'Контент',
    Icon: IconCalendar,
  },
  {
    value: 'assignments',
    label: 'Назначения',
    sidebarLabel: 'Назначения',
    title: 'Назначения',
    subtitle: 'Назначенное обучение, сроки и статусы прохождения фармацевтов',
    group: 'Прохождение',
    Icon: IconList,
  },
  {
    value: 'attendance',
    label: 'Посещаемость',
    sidebarLabel: 'Посещаемость',
    title: 'Посещаемость',
    subtitle: 'Регистрация участников и подтверждение участия в очных событиях',
    group: 'Прохождение',
    Icon: IconUsers,
  },
  {
    value: 'results',
    label: 'Результаты и экзамены',
    sidebarLabel: 'Результаты и экзамены',
    title: 'Результаты и экзамены',
    subtitle: 'Оценки, попытки и результаты контрольных этапов обучения',
    group: 'Прохождение',
    Icon: IconCheck,
  },
  {
    value: 'certificates',
    label: 'Сертификаты',
    sidebarLabel: 'Сертификаты',
    title: 'Сертификаты',
    subtitle: 'Выданные сертификаты, сроки действия и статусы документов',
    group: 'Прохождение',
    Icon: IconShield,
  },
  {
    value: 'analytics',
    label: 'Аналитика',
    sidebarLabel: 'Аналитика',
    title: 'Аналитика обучения',
    subtitle: 'Сводная динамика эффективности и выгрузка результатов',
    group: 'Отчётность',
    Icon: IconLift,
  },
  {
    value: 'settings',
    label: 'Настройки',
    sidebarLabel: 'Настройки обучения',
    title: 'Настройки обучения',
    subtitle: 'Параметры уведомлений, сроков и вознаграждений учебного процесса',
    group: 'Система',
    Icon: IconSettings,
  },
]

export const TRAINING_NAVIGATION_GROUPS = [
  'Обзор',
  'Контент',
  'Прохождение',
  'Отчётность',
  'Система',
] as const

const trainingItemByValue = new Map(TRAINING_NAVIGATION.map((item) => [item.value, item]))

export function isTrainingTab(value: string | null): value is TrainingTab {
  return value !== null && trainingItemByValue.has(value as TrainingTab)
}

export function trainingItem(tab: TrainingTab): TrainingNavigationItem {
  return trainingItemByValue.get(tab) ?? TRAINING_NAVIGATION[0]
}

export function trainingTabFromSearch(
  search: string | URLSearchParams,
  assignmentShortcut = false,
): TrainingTab {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search
  const hasAssignmentTargets = (params.get('pharmacists') ?? '')
    .split(',')
    .some((id) => id.trim().length > 0)
  if (assignmentShortcut && params.get('action') === 'assign' && hasAssignmentTargets) {
    return 'assignments'
  }
  const requested = params.get('tab')
  return isTrainingTab(requested) ? requested : 'overview'
}

export function trainingPath(tab: TrainingTab): string {
  return tab === 'overview' ? '/lms' : `/lms?tab=${encodeURIComponent(tab)}`
}
