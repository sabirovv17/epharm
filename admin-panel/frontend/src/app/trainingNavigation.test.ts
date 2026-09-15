import { describe, expect, it } from 'vitest'
import {
  TRAINING_NAVIGATION,
  isTrainingTab,
  trainingItem,
  trainingPath,
  trainingTabFromSearch,
} from './trainingNavigation'

describe('trainingNavigation', () => {
  it('сохраняет полный порядок бывших LMS-вкладок', () => {
    expect(TRAINING_NAVIGATION.map((item) => item.value)).toEqual([
      'overview',
      'programs',
      'courses',
      'events',
      'assignments',
      'attendance',
      'results',
      'certificates',
      'analytics',
      'settings',
    ])
  })

  it.each(TRAINING_NAVIGATION)('строит и разбирает deep link для $value', ({ value }) => {
    const path = trainingPath(value)
    const search = path.includes('?') ? path.slice(path.indexOf('?')) : ''

    expect(trainingTabFromSearch(search)).toBe(value)
    expect(isTrainingTab(value)).toBe(true)
    expect(trainingItem(value).sidebarLabel).not.toHaveLength(0)
  })

  it('обрабатывает назначение и некорректные параметры безопасными значениями', () => {
    expect(trainingTabFromSearch('?action=assign&pharmacists=ph-1', true)).toBe('assignments')
    expect(trainingTabFromSearch('?action=assign', true)).toBe('overview')
    expect(trainingTabFromSearch('?tab=not-a-section')).toBe('overview')
    expect(isTrainingTab(null)).toBe(false)
  })
})
