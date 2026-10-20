import { describe, expect, it } from 'vitest'
import { staffAuthStatusLabel } from './staffAuthStatus'

describe('staffAuthStatusLabel', () => {
  it.each([
    'authenticated', 'login_visible', 'login_required', 'initializing', 'local_disabled',
    'device_auth_required', 'forbidden', 'server_disabled', 'unsupported_protocol',
    'endpoint_unavailable', 'network_unavailable', 'enabled', 'no_process',
    'process_unavailable', 'ambiguous_process', 'process_changing', 'database_disabled',
    'database_unavailable', 'no_session', 'ambiguous_session', 'unknown_workstation',
    'unsupported_session_time', 'future_session_time', 'stale_session', 'invalid_session_time',
    'unsupported_process_time', 'session_predates_process',
  ])('объясняет известную причину %s без сырого кода', (code) => {
    const label = staffAuthStatusLabel(code)
    expect(label).toMatch(/^Вход РПС: /)
    expect(label).not.toContain('нет диагностики')
    expect(label).not.toContain(code)
  })

  it.each([undefined, null, '', 'unknown_future_reason', '__proto__', 'constructor', '<img src=x onerror=alert(1)>'])('не раскрывает неизвестное значение %s', (status) => {
    expect(staffAuthStatusLabel(status)).toBe('Вход РПС: нет диагностики (обновите POSM)')
  })

  it('отличает ошибку базы от отсутствующей или устаревшей сессии', () => {
    expect(staffAuthStatusLabel('database_unavailable')).toBe('Вход РПС: нет доступа к базе Стандарт‑Н')
    expect(staffAuthStatusLabel('no_session')).toBe('Вход РПС: сессия Стандарт‑Н не найдена')
    expect(staffAuthStatusLabel('stale_session')).toBe('Вход РПС: данные сессии устарели')
  })
})
