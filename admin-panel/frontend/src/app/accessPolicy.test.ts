import { describe, expect, it } from 'vitest'
import {
  canManageAiExam,
  canManageMoney,
  canManagePharmacistCredentials,
  canAccessSection,
  defaultPathForRole,
  isTrainingReadOnlyRole,
  sectionIdsForRole,
} from './accessPolicy'

describe('политика доступа к разделам консоли', () => {
  it.each(['SYSTEM_ADMIN', 'HQ_HEAD', 'FINANCE_REVIEWER'] as const)('роль %s управляет денежными операциями', (role) => {
    expect(canManageMoney(role)).toBe(true)
  })

  it.each(['CATEGORY_LEAD', 'BRAND_MANAGER', 'TRAINING_MANAGER', 'REGIONAL_MANAGER', 'TRAINER', undefined] as const)('роль %s не получает денежные полномочия', (role) => {
    expect(canManageMoney(role)).toBe(false)
  })

  it.each(['SYSTEM_ADMIN', 'HQ_HEAD'] as const)('роль %s управляет учётными данными фармацевта', (role) => {
    expect(canManagePharmacistCredentials(role)).toBe(true)
  })

  it.each(['FINANCE_REVIEWER', 'CATEGORY_LEAD', 'BRAND_MANAGER', 'TRAINING_MANAGER', 'REGIONAL_MANAGER', 'TRAINER', undefined] as const)('роль %s не меняет учётные данные фармацевта', (role) => {
    expect(canManagePharmacistCredentials(role)).toBe(false)
  })

  it('руководителю обучения доступны только LMS и AI-экзамены', () => {
    expect(sectionIdsForRole('TRAINING_MANAGER')).toEqual(['lms', 'ai_exam'])
    expect(defaultPathForRole('TRAINING_MANAGER')).toBe('/lms')
    expect(canAccessSection('TRAINING_MANAGER', 'rules')).toBe(false)
  })

  it('главный HQ-аккаунт видит основные и учебные разделы в режиме просмотра', () => {
    expect(canAccessSection('HQ_HEAD', 'dashboard')).toBe(true)
    expect(canAccessSection('HQ_HEAD', 'rules')).toBe(true)
    expect(canAccessSection('HQ_HEAD', 'lms')).toBe(true)
    expect(canAccessSection('HQ_HEAD', 'ai_exam')).toBe(true)
    expect(isTrainingReadOnlyRole('HQ_HEAD')).toBe(true)
    expect(canManageAiExam('HQ_HEAD')).toBe(false)
  })

  it('системный администратор сохраняет аварийный полный доступ', () => {
    expect(canAccessSection('SYSTEM_ADMIN', 'lms')).toBe(true)
    expect(canAccessSection('SYSTEM_ADMIN', 'settings')).toBe(true)
    expect(canManageAiExam('SYSTEM_ADMIN')).toBe(true)
  })

  it('интернет-заказы доступны только системному администратору и HQ', () => {
    expect(canAccessSection('SYSTEM_ADMIN', 'fulfillment')).toBe(true)
    expect(canAccessSection('HQ_HEAD', 'fulfillment')).toBe(true)
    expect(canAccessSection('CATEGORY_LEAD', 'fulfillment')).toBe(false)
    expect(canAccessSection('BRAND_MANAGER', 'fulfillment')).toBe(false)
    expect(canAccessSection('FINANCE_REVIEWER', 'fulfillment')).toBe(false)
  })

  it('руководитель обучения может управлять банком AI-экзамена', () => {
    expect(canManageAiExam('TRAINING_MANAGER')).toBe(true)
    expect(isTrainingReadOnlyRole('TRAINING_MANAGER')).toBe(false)
  })
})
