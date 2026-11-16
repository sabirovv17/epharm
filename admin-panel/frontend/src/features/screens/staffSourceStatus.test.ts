import { describe, expect, it } from 'vitest'
import { staffSourceStatusLabel } from './staffSourceStatus'

describe('native cashier context diagnostic', () => {
  it.each([
    'initializing', 'unknown', 'local_disabled', 'service_disabled', 'resolved', 'no_process',
    'process_unavailable', 'ambiguous_process', 'process_changing', 'database_disabled',
    'database_unavailable', 'no_session', 'ambiguous_session', 'unknown_workstation',
    'unsupported_session_time', 'future_session_time', 'stale_session', 'invalid_session_time',
    'unsupported_process_time', 'session_predates_process',
  ])('explains known code %s without leaking the raw code', (code) => {
    expect(staffSourceStatusLabel(code)).toMatch(/^Источник Standard-N: /)
    expect(staffSourceStatusLabel(code)).not.toContain('обновите POSM')
    expect(staffSourceStatusLabel(code)).not.toContain(code)
  })
  it.each([undefined, null, '', 'private unexpected server value'])('safely handles unsupported diagnostics %j', (code) => {
    expect(staffSourceStatusLabel(code)).toBe('Источник Standard-N: нет диагностики контекста (обновите POSM)')
  })
  it('never treats a resolved source as financial confirmation or login', () => {
    expect(staffSourceStatusLabel('resolved')).toContain('личность для бонусов не подтверждена этим статусом')
    expect(staffSourceStatusLabel('resolved')).not.toContain('вход подтверждён')
  })
})
