const labels = new Map<string, string>([
  ['authenticated', 'вход подтверждён'],
  ['login_visible', 'окно входа открыто'],
  ['login_required', 'требуется вход'],
  ['initializing', 'подготовка проверки'],
  ['local_disabled', 'отключён в настройках кассы'],
  ['device_auth_required', 'нужна авторизация кассы'],
  ['forbidden', 'доступ кассе запрещён'],
  ['server_disabled', 'отключён на сервере'],
  ['unsupported_protocol', 'протокол не поддерживается — обновите POSM'],
  ['endpoint_unavailable', 'сервер входа недоступен'],
  ['network_unavailable', 'нет связи с сервером'],
  ['enabled', 'проверка включена'],
  ['no_process', 'Стандарт‑Н не запущен'],
  ['process_unavailable', 'процесс Стандарт‑Н недоступен'],
  ['ambiguous_process', 'найдено несколько процессов Стандарт‑Н'],
  ['process_changing', 'Стандарт‑Н запускается или меняет пользователя'],
  ['database_disabled', 'чтение сессии отключено'],
  ['database_unavailable', 'нет доступа к базе Стандарт‑Н'],
  ['no_session', 'сессия Стандарт‑Н не найдена'],
  ['ambiguous_session', 'найдено несколько сессий Стандарт‑Н'],
  ['unknown_workstation', 'рабочее место не определено'],
  ['unsupported_session_time', 'формат времени сессии не поддерживается'],
  ['future_session_time', 'время сессии опережает часы кассы'],
  ['stale_session', 'данные сессии устарели'],
  ['invalid_session_time', 'некорректное время сессии'],
  ['unsupported_process_time', 'формат времени процесса не поддерживается'],
  ['session_predates_process', 'сессия старше запуска Стандарт‑Н'],
])

export function staffAuthStatusLabel(status?: string | null): string {
  return `Вход РПС: ${labels.get(status ?? '') ?? 'нет диагностики (обновите POSM)'}`
}
