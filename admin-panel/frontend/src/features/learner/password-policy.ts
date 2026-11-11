import { isValidNewPharmacistPassword } from '@/lib/pharmacist-password'

export const newPasswordErrorMessage =
  'Пароль: от 8 символов, не более 72 байт UTF-8; не только пробелы.'

// Validate new passwords only. Existing credentials must not be trimmed/truncated
// or rewritten before the server can verify them and offer account recovery.
export function newLearnerPasswordError(password: string): string | null {
  return isValidNewPharmacistPassword(password, 8) ? null : newPasswordErrorMessage
}
