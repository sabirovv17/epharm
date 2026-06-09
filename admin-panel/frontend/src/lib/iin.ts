// Валидация ИИН РК (физлицо) — формат + дата рождения + контрольная сумма.
// Зеркало backend kz.epharm.shared.IinUtil и mobile lib/core/validation/iin.dart.
// Структура (12 цифр): [0..5] ГГММДД, [6] век+пол (1,2→XIX; 3,4→XX; 5,6→XXI),
// [7..10] порядковый, [11] контрольная цифра.

const WEIGHTS_1 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
const WEIGHTS_2 = [3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2]

export function isValidIin(raw: string | null | undefined): boolean {
  const iin = (raw ?? '').trim()
  if (!/^\d{12}$/.test(iin)) return false
  if (!hasValidBirthDate(iin)) return false

  let control = checksum(iin, WEIGHTS_1)
  if (control === 10) control = checksum(iin, WEIGHTS_2)
  if (control === 10) return false
  return control === Number(iin[11])
}

function checksum(iin: string, weights: number[]): number {
  let sum = 0
  for (let i = 0; i < 11; i++) sum += Number(iin[i]) * weights[i]
  return sum % 11
}

function hasValidBirthDate(iin: string): boolean {
  const yy = Number(iin.slice(0, 2))
  const mm = Number(iin.slice(2, 4))
  const dd = Number(iin.slice(4, 6))
  const centuryDigit = Number(iin[6])

  let century: number
  if (centuryDigit === 1 || centuryDigit === 2) century = 1800
  else if (centuryDigit === 3 || centuryDigit === 4) century = 1900
  else if (centuryDigit === 5 || centuryDigit === 6) century = 2000
  else return false // 0/7/8/9 — не ИИН физлица

  if (mm < 1 || mm > 12) return false
  const daysInMonth = [
    31,
    isLeapYear(century + yy) ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ][mm - 1]
  return dd >= 1 && dd <= daysInMonth
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0
}
