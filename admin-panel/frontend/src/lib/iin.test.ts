import { describe, expect, it } from 'vitest'
import { isValidIin } from './iin'

describe('isValidIin', () => {
  it('валидные ИИН проходят', () => {
    for (const iin of [
      '900115300013',
      '850615400016',
      '990303500014',
      '781122300017',
      '951212500015',
    ]) {
      expect(isValidIin(iin), iin).toBe(true)
    }
  })

  it('неверная контрольная сумма отклоняется', () => {
    expect(isValidIin('900115300010')).toBe(false)
    expect(isValidIin('990101300123')).toBe(false) // старый невалидный seed
  })

  it('неверный формат отклоняется', () => {
    expect(isValidIin(null)).toBe(false)
    expect(isValidIin(undefined)).toBe(false)
    expect(isValidIin('')).toBe(false)
    expect(isValidIin('12345')).toBe(false)
    expect(isValidIin('9001153000130')).toBe(false) // 13 цифр
    expect(isValidIin('90011530001a')).toBe(false) // буква
  })

  it('невалидная дата рождения отклоняется', () => {
    expect(isValidIin('901315300013')).toBe(false) // месяц 13
    expect(isValidIin('900132300013')).toBe(false) // день 32
    expect(isValidIin('900229300013')).toBe(false) // 1990 не високосный
  })

  it('невалидная цифра века-пола отклоняется', () => {
    expect(isValidIin('900115000013')).toBe(false) // 0
    expect(isValidIin('900115700013')).toBe(false) // 7
  })

  it('пробелы обрезаются', () => {
    expect(isValidIin('  900115300013  ')).toBe(true)
  })
})
