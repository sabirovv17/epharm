import { describe, expect, it } from 'vitest'
import { STAGE_STATUS_LABEL } from './training-ui'

describe('training UI labels', () => {
  it('localizes every stage status shown in the results table', () => {
    expect(STAGE_STATUS_LABEL).toEqual({
      locked: 'Заблокирован',
      available: 'Доступен',
      in_progress: 'В процессе',
      waiting_review: 'На проверке',
      completed: 'Завершён',
      failed: 'Не пройден',
      skipped: 'Пропущен',
    })
  })
})
