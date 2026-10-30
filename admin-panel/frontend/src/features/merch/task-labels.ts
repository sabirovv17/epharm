const statuses = new Map([
  ['ready', 'Назначено'],
  ['sent', 'Отправлено'],
  ['assigned', 'Назначено'],
  ['accepted', 'Принято в работу'],
  ['in_progress', 'В работе'],
  ['submitted', 'На проверке'],
  ['completed', 'Выполнено'],
  ['approved', 'Одобрено'],
  ['revision_required', 'Нужна доработка'],
  ['draft', 'Черновик'],
  ['cancelled', 'Отменено'],
  ['blocked', 'Есть препятствие'],
  ['overdue', 'Просрочено'],
  ['rejected', 'Отклонено'],
])

const types = new Map([
  ['photo_report', 'Фотоотчёт'],
  ['placement', 'Размещение'],
  ['maintenance', 'Обслуживание'],
  ['inventory', 'Инвентаризация'],
  ['general', 'Общее задание'],
  ['staff_monthly_visit', 'Месячный фотоотчёт'],
])

const priorities = new Map([
  ['low', 'Низкий'],
  ['normal', 'Обычный'],
  ['high', 'Высокий'],
  ['urgent', 'Срочный'],
])

export const taskStatusLabel = (value: string) => statuses.get(value) ?? 'Исторический статус'
export const taskTypeLabel = (value: string) => types.get(value) ?? 'Другой тип задания'
export const taskPriorityLabel = (value: string) => priorities.get(value) ?? 'Приоритет не указан'
