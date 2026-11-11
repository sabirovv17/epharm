import { describe, expect, it } from 'vitest'
import { newLearnerPasswordError, newPasswordErrorMessage } from './password-policy'
import { learnerTranslate } from './learner-i18n'

describe('new learner passwords shared with POSM', () => {
  it.each(['password', 'a'.repeat(72), 'я'.repeat(36), '😀'.repeat(18), '  secret  '])(
    'accepts the supported boundary without altering input %#',
    (value) => {
      expect(newLearnerPasswordError(value)).toBeNull()
    },
  )
  it.each(['', '1234567', ' '.repeat(8), 'a'.repeat(73), 'я'.repeat(37), '😀'.repeat(19)])(
    'rejects invalid new credentials %#',
    (value) => {
      expect(newLearnerPasswordError(value)).toBe(newPasswordErrorMessage)
    },
  )
  it('has a Kazakh explanation', () => {
    expect(learnerTranslate('kk', newPasswordErrorMessage)).not.toBe(newPasswordErrorMessage)
  })
})
