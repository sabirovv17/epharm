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

  it('отклоняет пустой идентификатор из нулей, даже с верной суммой', () => {
    expect(isValidIin('000000000000')).toBe(false)
    expect(isValidIin('  000000000000  ')).toBe(false)
  })

  it('неверный формат отклоняется', () => {
    expect(isValidIin(null)).toBe(false)
    expect(isValidIin(undefined)).toBe(false)
    expect(isValidIin('')).toBe(false)
    expect(isValidIin('12345')).toBe(false)
    expect(isValidIin('9001153000130')).toBe(false) // 13 цифр
    expect(isValidIin('90011530001a')).toBe(false) // буква
  })

  // Синтетические номера: префикс 123456 не является датой рождения.
  it.each([
    '123456012344',
    '123456112340',
    '123456212347',
    '123456312343',
    '123456412343',
    '123456512346',
    '123456612342',
    '123456712349',
    '123456812345',
    '123456912341',
  ])('не выводит дату, век или пол из цифр %s', (iin) => {
    expect(isValidIin(iin)).toBe(true)
  })

  it('использует второй набор весов при первой сумме 10', () => {
    expect(isValidIin('123456000405')).toBe(true)
    expect(isValidIin('123456000400')).toBe(false)
  })

  it('отклоняет номер, если оба набора весов дают 10', () => {
    for (let digit = 0; digit <= 9; digit++) {
      expect(isValidIin(`12345600048${digit}`)).toBe(false)
    }
  })

  it('принимает только цифры ASCII без внутренних пробелов', () => {
    expect(isValidIin('１２３４５６０１２３４４')).toBe(false)
    expect(isValidIin('١٢٣٤٥٦٠١٢٣٤٤')).toBe(false)
    expect(isValidIin('123456 12344')).toBe(false)
  })

  it('пробелы обрезаются', () => {
    expect(isValidIin('  900115300013  ')).toBe(true)
  })
})
