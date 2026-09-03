// Fixtures — статические типы + пустые данные.
// Принцип: типы и структуры готовы под backend, но данные = пустые массивы / нули.
// Когда подключим Spring API (Этап 3) — заменим export const X = [] на TanStack Query хуки.
//
// Что НЕ обнуляем:
// - SECTIONS — структура навигации, нужна для Sidebar
// - USERS + MOCK_CREDENTIALS — нужны для dev-аутентификации (login flow)
// - VENDOR_PALETTE / mock helpers — pure-функции без данных

import type { ReactNode } from 'react'
import {
  IconBox,
  IconDashboard,
  IconPromo,
  IconPharmacy,
  IconPharmacist,
  IconLift,
  IconFinance,
  IconReconcile,
  IconAIExam,
  IconRules,
  IconScreens,
  IconLMS,
  IconSettings,
  IconList,
} from '@/ui/icons'

// ─── Formatters ───────────────────────────────────────────────────────────
export const formatNum = (n: number) => new Intl.NumberFormat('ru-RU').format(n)
export const formatKzt = (n: number) => `${formatNum(n)} ₸`

// ─── Sidebar sections (структура, не данные — оставляем) ──────────────────
export type SectionId =
  | 'dashboard'
  | 'promo'
  | 'rules'
  | 'screens'
  | 'pharmacies'
  | 'pharmacists'
  | 'reconcile'
  | 'ai_exam'
  | 'finance'
  | 'lift'
  | 'lms'
  | 'settings'
  | 'storefront'
  | 'fulfillment'

export interface Section {
  id: SectionId
  label: string
  group: 'Обзор' | 'Кампании' | 'Сеть' | 'Операции' | 'Аналитика' | 'Система'
  Icon: (p: { size?: number; className?: string }) => ReactNode
  badge?: string
}

export const SECTIONS: Section[] = [
  { id: 'dashboard', label: 'Дашборд аналитики', group: 'Обзор', Icon: IconDashboard },
  { id: 'promo', label: 'Промо-кампании', group: 'Кампании', Icon: IconPromo },
  { id: 'rules', label: 'Rules Engine', group: 'Кампании', Icon: IconRules },
  // Баннеры приложения управляются внутри раздела «Управление экранами» (вкладка),
  // поэтому отдельного пункта сайдбара у них нет.
  { id: 'screens', label: 'Управление экранами', group: 'Кампании', Icon: IconScreens },
  { id: 'pharmacies', label: 'Сеть аптек', group: 'Сеть', Icon: IconPharmacy },
  { id: 'pharmacists', label: 'Фармацевты', group: 'Сеть', Icon: IconPharmacist },
  { id: 'storefront', label: 'Витрина / Каталог', group: 'Сеть', Icon: IconBox },
  { id: 'reconcile', label: 'Сверка чеков', group: 'Операции', Icon: IconReconcile },
  { id: 'fulfillment', label: 'Интернет-заказы', group: 'Операции', Icon: IconList },
  { id: 'ai_exam', label: 'AI-Экзаменация', group: 'Операции', Icon: IconAIExam },
  { id: 'finance', label: 'Финансы / выплаты', group: 'Операции', Icon: IconFinance },
  { id: 'lift', label: 'Аналитика lift', group: 'Аналитика', Icon: IconLift },
  { id: 'lms', label: 'Обучение', group: 'Аналитика', Icon: IconLMS },
  { id: 'settings', label: 'Настройки', group: 'Система', Icon: IconSettings },
]

// ─── Users — UserDto из api-types (синхронизирован с backend) ────────────
// Re-export для обратной совместимости (Sidebar, RoleSwitcher, тесты).
export type { UserDto as User } from '@/lib/api-types'
import type { UserDto, AdminRole } from '@/lib/api-types'

// Demo-данные ТОЛЬКО для тестов и для UI-сравнения «активный пользователь среди списка».
// Реальная аутентификация уходит через backend (POST /api/admin/auth/login → UserDto).
export const USERS: Record<string, UserDto> = {
  damir: {
    id: 'damir',
    email: 'damir@jadran.com',
    name: 'Дамир Нурланов',
    role: 'BRAND_MANAGER',
    company: 'Jadran',
  },
  aigerim: {
    id: 'aigerim',
    email: 'aigerim@inkar.kz',
    name: 'Айгерим Сарсенова',
    role: 'CATEGORY_LEAD',
    company: 'Inkar / Ledex',
  },
  bauyrzhan: {
    id: 'bauyrzhan',
    email: 'bauyrzhan@inkar.kz',
    name: 'Бауыржан Тлеуов',
    role: 'HQ_HEAD',
    company: 'Inkar',
  },
  lms: {
    id: 'lms',
    email: 'lms@epharm.kz',
    name: 'Руководитель обучения',
    role: 'TRAINING_MANAGER',
    company: 'Inkar',
  },
}

/** Локализованный лейбл для AdminRole. Используется в Topbar / RoleSwitcher / Settings. */
export const roleLabel = (role: AdminRole): string =>
  ({
    SYSTEM_ADMIN: 'Системный администратор',
    HQ_HEAD: 'Head of Trade Marketing',
    TRAINING_MANAGER: 'Менеджер обучения',
    REGIONAL_MANAGER: 'Региональный менеджер',
    TRAINER: 'Тренер',
    CATEGORY_LEAD: 'Category Lead',
    BRAND_MANAGER: 'Brand Manager',
    FINANCE_REVIEWER: 'Finance Reviewer',
  })[role]

// ─── Contract (тип сохранён, runtime-данных нет) ──────────────────────────
export interface Contract {
  brand: string
  brands: string[]
  brandsCount: number
  period: string
  budgetTotal: number
  budgetUsed: number
  approvedBy: string
  signedAt: string
}

// CONTRACT константа осталась для совместимости с ContractModal (никогда не открывается
// пока userHasContract возвращает false). Backend в Этапе 3 принесёт реальные контракты.
export const CONTRACT: Contract = {
  brand: '—',
  brands: [],
  brandsCount: 0,
  period: '—',
  budgetTotal: 0,
  budgetUsed: 0,
  approvedBy: '',
  signedAt: '',
}

// Пока нет реальных контрактов в системе — ни у кого их нет. После Этапа 3 будем брать
// из GET /api/admin/me/contract.
export const userHasContract = (_user: UserDto): boolean => false

export const getUserContract = (user: UserDto): Contract | null =>
  userHasContract(user) ? CONTRACT : null

// ─── Product library (типы из api-types, runtime-данных нет) ─────────────
// Real-time products живут в TanStack Query кэше через useProducts(); этот
// PRODUCT_LIBRARY = [] нужен только для legacy импортов (CommandPalette,
// smoke-тесты). После Этапа 3.3 уйдёт.

import type { ProductDto } from '@/lib/api-types'
export type Product = ProductDto

export const PRODUCT_LIBRARY: Product[] = []
/**
 * Fallback-lookup: возвращает undefined пока не подключён useProducts.
 * Если нужна реальная выборка — используй useProductIndex() из
 * `@/lib/queries/catalog`.
 */
export const productById = (_id: string): Product | undefined => undefined

// ─── Sparkline data (пустые наборы) ──────────────────────────────────────
export const SPARK_HIGH: number[] = []
export const SPARK_MED: number[] = []
export const SPARK_LOW: number[] = []
export const SPARK_FLAT: number[] = []

// ─── Rules Engine ─────────────────────────────────────────────────────────
// Типы синхронизированы с backend (kz.epharm.rules.dto.* → @/lib/api-types).
// Локально добавляем optional `spark` для UI-only sparkline в RuleAnalytics —
// backend пока не отдаёт исторические time-series, добавим в Этапе 4.

import type {
  RuleDto,
  RuleStatus as ApiRuleStatus,
  RuleType as ApiRuleType,
  RuleTriggerDto,
  RuleAbTestDto,
} from '@/lib/api-types'

export type RuleStatus = ApiRuleStatus
export type RuleType = ApiRuleType
export type RuleTrigger = RuleTriggerDto
export type RuleABTest = RuleAbTestDto

export type Rule = RuleDto & {
  /** UI-only исторические показатели для Sparkline; backend заполнит в Этапе 4. */
  spark?: number[]
}

// Старые legacy-экспорты (используются в `sections.smoke.test.tsx`). Когда
// MSW handlers будут отдавать данные, эти константы уйдут.
export const RULES_SUBST: Rule[] = []
export const RULES_CROSS: Rule[] = []
export const RULES_ARCHIVE: Rule[] = []

// ─── Pharmacies network ───────────────────────────────────────────────────
export type PharmacyGroup = 'pilot' | 'control' | 'rolled'

export interface Chain {
  id: string
  name: string
  color: string
  points: number
  group: PharmacyGroup
}

export const CHAINS: Chain[] = []

export interface Pharmacy {
  id: string
  name: string
  chain: string
  chainId: string
  city: string
  district: string
  addr: string
  group: PharmacyGroup
  pharmacists: number
  receipts30d: number
  gmv30d: number
  liftPct: number
  rulesAccepted: number
  active: boolean
}

export const PHARMACY_LIST: Pharmacy[] = []

// ─── Pharmacists registry ─────────────────────────────────────────────────
export type PharmacistTier = 'Silver' | 'Gold' | 'Platinum'
export type PharmacistStatus = 'active' | 'pending' | 'blocked'

export interface Pharmacist {
  id: string
  name: string
  iin: string
  phone: string
  pharmacy: string
  pharmacyId: string
  city: string
  tier: PharmacistTier
  receipts30d: number
  rulesAccepted30d: number
  earned30d: number
  balance: number
  coursesDone: number
  coursesTotal: number
  status: PharmacistStatus
  joinedAt: string
}

export const PHARMACISTS: Pharmacist[] = []

// ─── Finance: payouts ─────────────────────────────────────────────────────
export type PayoutStatus = 'pending' | 'approved'

export interface PayoutBatch {
  id: string
  period: string
  status: PayoutStatus
  pharmacists: number
  amount: number
  reviewer: string
  approvedAt?: string
  items: number
}

export const PAYOUT_BATCHES: PayoutBatch[] = []

export interface PayoutItem {
  id: string
  pharmacist: string
  pharmacy: string
  city: string
  receipts: number
  rules: number
  amount: number
  flag: string | null
}

export const PAYOUT_ITEMS: PayoutItem[] = []

// ─── Reconcile queue ──────────────────────────────────────────────────────
export type ReconcileStatus = 'pending' | 'flagged' | 'approved' | 'rejected'

export interface ReconcileItem {
  id: string
  uploaded: string
  pharmacist: string
  pharmacy: string
  total: number
  products: string[]
  status: ReconcileStatus
  score: number
  flag: string | null
}

export const RECONCILE_QUEUE: ReconcileItem[] = []

// ─── AI Exam ──────────────────────────────────────────────────────────────
export interface ExamQuestion {
  id: string
  topic: string
  q: string
  a: string
  difficulty: 'easy' | 'medium' | 'hard'
  passed: number
}

export const AI_EXAM_BANK: ExamQuestion[] = []

export interface ExamResult {
  id: string
  name: string
  pharmacy: string
  attempts: number
  score: number
  passed: boolean
  bonus: number
  takenAt: string
}

export const AI_EXAM_RESULTS: ExamResult[] = []

// ─── Promo campaigns (типы из api-types, runtime-данные из useQuery) ──────
import type { PromoDto, PromoStatus as ApiPromoStatus } from '@/lib/api-types'
export type PromoStatus = ApiPromoStatus
export type Promo = PromoDto

export const PROMOS: Promo[] = []

// ─── Lift analytics ───────────────────────────────────────────────────────
export interface LiftData {
  pilot: number[]
  control: number[]
  weeks: number
  liftPct: number
  pValue: number
  pilotN: number
  controlN: number
}

export const LIFT_DATA: LiftData = {
  pilot: [],
  control: [],
  weeks: 0,
  liftPct: 0,
  pValue: 0,
  pilotN: 0,
  controlN: 0,
}

// ─── DOOH screens ─────────────────────────────────────────────────────────
export interface Playlist {
  id: string
  name: string
  duration: string
  items: number
  screens: number
  active: boolean
}

export const SCREEN_PLAYLISTS: Playlist[] = []

export interface Slide {
  id: string
  title: string
  duration: number
  type: 'video' | 'image'
  cover: string
}

export const SCREEN_SLIDES: Slide[] = []

// ─── LMS courses ──────────────────────────────────────────────────────────
export type CourseStatus = 'published' | 'draft'

export interface Course {
  id: string
  title: string
  lessons: number
  duration: string
  students: number
  completed: number
  bonus: number
  status: CourseStatus
  updatedAt: string
}

export const LMS_COURSES: Course[] = []

// ─── Dashboard heatmap — 12 weeks × 7 days × intensity 0-4 ────────────────
// Структура пустая: массивы нулей под нулевую интенсивность.
export const HEATMAP: number[][] = Array.from({ length: 12 }, () =>
  Array.from({ length: 7 }, () => 0),
)
