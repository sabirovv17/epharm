const labels = new Map<string, string>([
  ['initializing', 'проверка контекста кассира'],
  ['unknown', 'контекст кассира пока не определён'],
  ['local_disabled', 'чтение контекста отключено на кассе'],
  ['service_disabled', 'сервис контекста отключён'],
  ['resolved', 'контекст кассира прочитан; личность для бонусов не подтверждена этим статусом'],
  ['no_process', 'Стандарт‑Н не запущен'],
  ['process_unavailable', 'процесс Стандарт‑Н недоступен'],
  ['ambiguous_process', 'найдено несколько процессов Стандарт‑Н'],
  ['process_changing', 'процесс Стандарт‑Н меняется'],
  ['database_disabled', 'чтение базы отключено'],
  ['database_unavailable', 'нет доступа к базе Стандарт‑Н'],
  ['no_session', 'сессия кассира не найдена'],
  ['ambiguous_session', 'найдено несколько сессий кассира'],
  ['unknown_workstation', 'рабочее место не определено'],
  ['unsupported_session_time', 'формат времени сессии не поддерживается'],
  ['future_session_time', 'время сессии опережает часы кассы'],
  ['stale_session', 'данные сессии устарели'],
  ['invalid_session_time', 'некорректное время сессии'],
  ['unsupported_process_time', 'формат времени процесса не поддерживается'],
  ['session_predates_process', 'сессия старше запуска Стандарт‑Н'],
])

export function staffSourceStatusLabel(status?: string | null): string {
  return `Источник Standard-N: ${labels.get(status ?? '') ?? 'нет диагностики контекста (обновите POSM)'}`
}
