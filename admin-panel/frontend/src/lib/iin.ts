// Проверка формата и контрольной суммы, не факта выдачи ИИН человеку.
// Цифры ИИН не должны проверяться как дата рождения, век или пол.

const WEIGHTS_1 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
const WEIGHTS_2 = [3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2]

export function isValidIin(raw: string | null | undefined): boolean {
  const iin = (raw ?? '').trim()
  if (!/^[0-9]{12}$/.test(iin)) return false
  if (iin === '000000000000') return false // Пустой идентификатор не заменяет ИИН.

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
