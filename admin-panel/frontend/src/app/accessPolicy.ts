import type { AdminRole } from '@/lib/api-types'
import { SECTIONS, type Section, type SectionId } from '@/mocks/fixtures'

const TRAINING_SECTION_IDS: readonly SectionId[] = ['lms', 'ai_exam']
const TRAINING_READ_ONLY_SECTION_IDS: readonly SectionId[] = ['lms']
const CORE_SECTION_IDS: readonly SectionId[] = SECTIONS.map((section) => section.id).filter(
  (id) => !TRAINING_SECTION_IDS.includes(id) && id !== 'fulfillment',
)

const sectionById = new Map(SECTIONS.map((section) => [section.id, section]))

export function sectionIdsForRole(role: AdminRole): readonly SectionId[] {
  switch (role) {
    case 'SYSTEM_ADMIN':
    case 'HQ_HEAD':
      return SECTIONS.map((section) => section.id)
    case 'TRAINING_MANAGER':
      return TRAINING_SECTION_IDS
    case 'REGIONAL_MANAGER':
    case 'TRAINER':
      return TRAINING_READ_ONLY_SECTION_IDS
    default:
      return CORE_SECTION_IDS
  }
}

export function canManageAiExam(role: AdminRole | undefined): boolean {
  return role === 'SYSTEM_ADMIN' || role === 'TRAINING_MANAGER'
}

export function isTrainingReadOnlyRole(role: AdminRole | undefined): boolean {
  return role === 'HQ_HEAD'
}

export function sectionsForRole(role: AdminRole): Section[] {
  return sectionIdsForRole(role)
    .map((id) => sectionById.get(id))
    .filter((section): section is Section => section !== undefined)
}

export function canAccessSection(role: AdminRole, section: SectionId): boolean {
  return sectionIdsForRole(role).includes(section)
}

export function defaultSectionForRole(role: AdminRole): SectionId {
  return isTrainingWorkspaceRole(role) ? 'lms' : 'rules'
}

export function defaultPathForRole(role: AdminRole): string {
  return `/${defaultSectionForRole(role).replace('_', '-')}`
}

export function isTrainingWorkspaceRole(role: AdminRole): boolean {
  return role === 'TRAINING_MANAGER' || role === 'REGIONAL_MANAGER' || role === 'TRAINER'
}

export function workspaceLabelForRole(role: AdminRole): string {
  return isTrainingWorkspaceRole(role) ? 'Обучение' : 'HQ'
}
