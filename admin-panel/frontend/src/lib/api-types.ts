// Типы API — соответствуют DTO бэкенда (kz.epharm.auth.dto.*).
// Когда обновляем бэкенд DTO — синхронизируем здесь.

export type AdminRole = 'HQ_HEAD' | 'CATEGORY_LEAD' | 'BRAND_MANAGER' | 'FINANCE_REVIEWER'

export interface UserDto {
  id: string
  email: string
  name: string
  role: AdminRole
  company: string
}

export interface AuthTokens {
  /** JWT access token, передаётся в Authorization: Bearer <...>. */
  accessToken: string
  /** ISO-8601 timestamp expire access-токена. */
  accessTokenExpiresAt: string
  /** Raw refresh token, нужен для /api/admin/auth/refresh. */
  refreshToken: string
  refreshTokenExpiresAt: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  tokens: AuthTokens
  user: UserDto
}

export interface RefreshRequest {
  refreshToken: string
}

export interface RefreshResponse {
  tokens: AuthTokens
}

export type ApiErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'INVALID_REFRESH_TOKEN'
  | 'USER_NOT_FOUND'
  | 'USER_NOT_ACTIVE'
  | 'VALIDATION_FAILED'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'INTERNAL'

export interface ApiErrorResponse {
  code: ApiErrorCode
  message: string
  timestamp: string
  fields?: Record<string, string>
}

// ─── Catalog (Этап 3.2) ──────────────────────────────────────────────────
// Зеркало kz.epharm.catalog.dto.*

export interface ProductDto {
  id: string
  name: string
  brand: string
  vendor: string
  mnn: string
  price: number
}

// Зеркало kz.epharm.catalog.dto.CreateProductRequest — id задаёт клиент
// (на него ссылаются правила), формат [a-z0-9_]{2,64}.
export interface CreateProductRequest {
  id: string
  name: string
  brand: string
  vendor: string
  mnn: string
  price: number
}

// Зеркало UpdateProductRequest — partial PATCH (id неизменяем).
export interface UpdateProductRequest {
  name?: string
  brand?: string
  vendor?: string
  mnn?: string
  price?: number
}

export interface BrandDto {
  brand: string
  vendor: string
  productCount: number
}

export interface MnnGroupDto {
  mnn: string
  productCount: number
}

// ─── Rules (Этап 3.2) ────────────────────────────────────────────────────
// Зеркало kz.epharm.rules.dto.* + kz.epharm.rules.entity.RuleTrigger/RuleAbTest.

export type RuleType = 'substitution' | 'crosssell'
export type RuleStatus = 'active' | 'paused' | 'draft' | 'archived'

export type TriggerKind = 'product' | 'mnn' | 'product_any'

export interface RuleTriggerDto {
  kind: TriggerKind
  /**
   * Полиморфно:
   *   kind='product'     → string (productId)
   *   kind='mnn'         → string (MNN name; опц. exclude:string[])
   *   kind='product_any' → string[] (productIds)
   */
  value: string | string[]
  exclude?: string[]
}

export interface RuleAbTestDto {
  variant: string
  share: number
}

// ─── Богатая карточка рекомендации (Фаза 2) — зеркало kz.epharm.rules.entity.RuleCard ───
export interface RuleComparisonRowDto {
  label: string
  triggerValue: string
  recommendValue: string
  recommendHighlight: boolean
}

export interface RuleCardDto {
  partnerLabel: string | null
  comparison: RuleComparisonRowDto[]
  goalLabel: string | null
  goalTarget: number | null
  goalBonus: number | null
}

export interface RuleDto {
  id: string
  type: RuleType
  status: RuleStatus
  trigger: RuleTriggerDto
  recommend: string
  bonus: number
  script: string
  advantages: string[]
  abTest: RuleAbTestDto | null
  /** Богатая карточка (Фаза 2): сравнение, партнёр, цель. null — нет богатой карточки. */
  card: RuleCardDto | null
  pharmacies: number
  impressions: number
  accepts: number
  convRate: number
  revenue: number
  payout: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface CreateRuleRequest {
  type: RuleType
  status?: RuleStatus
  trigger: RuleTriggerDto
  recommend: string
  bonus?: number
  script?: string
  advantages?: string[]
  abTest?: RuleAbTestDto | null
  card?: RuleCardDto | null
}

// ─── Finance / Payouts (Этап 3.5) ────────────────────────────────────────

export type PayoutBatchStatus = 'pending' | 'approved'

export interface PayoutBatchDto {
  id: string
  period: string
  status: PayoutBatchStatus
  pharmacists: number
  amount: number
  items: number
  reviewerId: string | null
  reviewerName: string | null
  approvedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface PayoutItemDto {
  id: string
  batchId: string
  pharmacistId: string
  pharmacistName: string
  pharmacy: string
  city: string
  receipts: number
  rules: number
  amount: number
  flag: string | null
}

// ─── Pharmacies + Pharmacists (Этап 3.4) ─────────────────────────────────

export type PharmacyGroup = 'pilot' | 'control' | 'rolled'

export interface ChainDto {
  id: string
  name: string
  color: string
  points: number
  group: PharmacyGroup
}

// Зеркало kz.epharm.pharmacies.dto.CreateChainRequest — id slug [a-z0-9_],
// color hex #RRGGBB (на сеть ссылаются аптеки через chainId).
export interface CreateChainRequest {
  id: string
  name: string
  color: string
  points?: number
  group: PharmacyGroup
}

export interface UpdateChainRequest {
  name?: string
  color?: string
  points?: number
  group?: PharmacyGroup
}

export interface PharmacyDto {
  id: string
  name: string
  chainId: string
  chainName: string
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
  createdAt: string
  updatedAt: string
}

export interface CreatePharmacyRequest {
  name: string
  chainId: string
  city: string
  district?: string
  addr?: string
  group: PharmacyGroup
  active?: boolean
}

export interface UpdatePharmacyRequest {
  name?: string
  city?: string
  district?: string
  addr?: string
  group?: PharmacyGroup
  active?: boolean
  receipts30d?: number
  gmv30d?: number
  rulesAccepted?: number
}

export type PharmacistTier = 'Silver' | 'Gold' | 'Platinum'
export type PharmacistStatus = 'active' | 'pending' | 'blocked'

export interface PharmacistDto {
  id: string
  name: string
  iin: string
  phone: string
  pharmacyId: string
  pharmacyName: string
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
  createdAt: string
  updatedAt: string
}

export interface CreatePharmacistRequest {
  name: string
  iin: string
  phone: string
  pharmacyId: string
  tier?: PharmacistTier
  status?: PharmacistStatus
}

export interface UpdatePharmacistRequest {
  name?: string
  phone?: string
  tier?: PharmacistTier
  balance?: number
  coursesDone?: number
  coursesTotal?: number
}

// ─── Promo (Этап 3.3) ────────────────────────────────────────────────────
// Зеркало kz.epharm.promo.dto.* + PromoStatus.

export type PromoStatus = 'active' | 'draft' | 'paused' | 'archived'

/** Ценовой порог акции: «от minQty шт → price ₸» (+ разовый bonus ₸ за порог). */
export interface PromoTierDto {
  minQty: number
  price: number
  bonus: number
}

export interface PromoDto {
  id: string
  title: string
  status: PromoStatus
  brand: string
  period: string
  pharmacies: number
  budget: number
  spent: number
  kpi: string
  cover: string
  // Товарная акция (V022): линк на товар Medusa + снимок + даты.
  medusaProductId: string | null
  productName: string
  productImage: string | null
  /** Ручной override фото товара (поверх PIM/Medusa). null — берётся productImage. */
  overrideImage: string | null
  /** Ручной override описания товара (поверх PIM/Medusa). null — нет переопределения. */
  overrideDescription: string | null
  /** Цена товара из Medusa (read-only, обновляется ежедневно — НЕ редактируется в админке). */
  price: number
  /** Единый бонус фармацевту за продажу (заменил многоуровневые пороги). */
  pharmacistBonus: number
  dateStart: string | null
  dateEnd: string | null
  /** Всегда один авто-собранный порог (legacy-поле; редактора порогов в кампании нет). */
  tiers: PromoTierDto[]
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface CreatePromoRequest {
  title: string
  status?: PromoStatus
  brand?: string
  period?: string
  budget?: number
  kpi?: string
  cover?: string
  medusaProductId?: string | null
  productName?: string
  productImage?: string | null
  overrideImage?: string | null
  overrideDescription?: string | null
  pharmacistBonus?: number
  dateStart?: string | null
  dateEnd?: string | null
}

export interface UpdatePromoRequest {
  status?: PromoStatus
  title?: string
  brand?: string
  period?: string
  budget?: number
  kpi?: string
  cover?: string
  medusaProductId?: string | null
  productName?: string
  productImage?: string | null
  overrideImage?: string | null
  overrideDescription?: string | null
  pharmacistBonus?: number
  dateStart?: string | null
  dateEnd?: string | null
}

// ─── Campaign rules (T2) — авторинг правил замены/кросс-селла из кампании ──────
// Зеркало backend PromoRulesViewDto / PromoRulesConfigDto / PromoRuleProductRef.

/** Ссылка на товар витрины внутри правила кампании (снимок имени/бренда/цены). */
export interface PromoRuleProductRef {
  medusaProductId: string
  name: string
  brand?: string | null
  mnn?: string | null
  volume?: string | null
  price?: number | null
  /** Скрипт ЭТОЙ пары: что сказать фармацевту и почему (видно на кассе). */
  script?: string
}

export interface PromoRulesConfigDto {
  /** Товары, которые ЗАМЕНЯЕТ продвигаемый товар (substitution). */
  replacements: PromoRuleProductRef[]
  /** Товары, ВМЕСТЕ с которыми продаётся продвигаемый товар (cross-sell). */
  crossSells: PromoRuleProductRef[]
  script: string
  advantages: string[]
  partnerLabel: string | null
  comparison: RuleComparisonRowDto[]
  goalLabel: string | null
  goalTarget: number | null
  goalBonus: number | null
}

export interface PromoRulesViewDto {
  promoId: string
  config: PromoRulesConfigDto
  ruleCount: number
  activeCount: number
}

export interface UpdateRuleRequest {
  status?: RuleStatus
  trigger?: RuleTriggerDto
  recommend?: string
  bonus?: number
  script?: string
  advantages?: string[]
  abTest?: RuleAbTestDto | null
  /** Поставить true чтобы явно снять abTest (т.к. undefined = «не трогать»). */
  clearAbTest?: boolean
  card?: RuleCardDto | null
  /** Поставить true чтобы явно убрать богатую карточку. */
  clearCard?: boolean
}

// ─── Dashboard (Этап 3.6) — read-only сводка KPI ───
export interface TopRuleDto {
  id: string
  label: string
  convRate: number
  accepts: number
}

export interface TopPharmacyDto {
  id: string
  name: string
  city: string
  rulesAccepted: number
}

export interface TopProductDto {
  id: string
  name: string
  revenue: number
}

export interface DashboardSummaryDto {
  impressions: number
  accepts: number
  convRate: number
  activeRules: number
  totalRules: number
  paidOut: number
  pendingPayout: number
  pilotPharmacies: number
  avgLiftPct: number
  topRules: TopRuleDto[]
  topPharmacies: TopPharmacyDto[]
  topProducts: TopProductDto[]
}

// ─── Lift (Этап 3.6) — pilot vs control аналитика ───
export interface LiftSegmentDto {
  name: string
  liftPct: number
  pharmacies: number
}

export interface LiftSummaryDto {
  networkLiftPct: number
  pValue: number | null
  /** true → нет показов в pilot и/или control: lift/pValue недостоверны. */
  insufficientData: boolean
  pilotCount: number
  controlCount: number
  rolledCount: number
  pilotReceipts30d: number
  controlReceipts30d: number
  segments: LiftSegmentDto[]
}

// ─── LMS (Этап 3.6) — обучающие курсы ───
export type CourseStatus = 'published' | 'draft' | 'archived'

export interface CourseDto {
  id: string
  title: string
  status: CourseStatus
  category: string
  lessons: number
  durationMin: number
  enrolled: number
  completed: number
  bonus: number
  createdAt: string
  updatedAt: string
}

export interface CreateCourseRequest {
  title: string
  status?: CourseStatus
  category?: string
  lessons?: number
  durationMin?: number
  bonus?: number
}

export interface UpdateCourseRequest {
  title?: string
  status?: CourseStatus
  category?: string
  lessons?: number
  durationMin?: number
  bonus?: number
}

// ─── Screens (Этап 3.6) — indoor-DOOH плейлисты + слайды (read-only; редактор в Этапе 5) ───
export type PlaylistStatus = 'active' | 'draft' | 'archived'
export type SlideKind = 'video' | 'image'

export interface PlaylistDto {
  id: string
  name: string
  status: PlaylistStatus
  slidesCount: number
  durationSec: number
  pharmacies: number
  /** Назначение (V016): null = все экраны (глобальный), иначе id конкретной аптеки/экрана. */
  pharmacyId: string | null
  createdAt: string
  updatedAt: string
}

export interface SlideDto {
  id: string
  title: string
  kind: SlideKind
  durationSec: number
  mediaUrl: string
  playlistId: string | null
  position: number
}

// Зеркало kz.epharm.screens.dto.* (управление экранами, ТЗ §3.3)
export interface CreatePlaylistRequest {
  name: string
  status?: PlaylistStatus
}

export interface UpdatePlaylistRequest {
  name?: string
  status?: PlaylistStatus
  /** Применить назначение (V016). true → задать targetPharmacyId (в т.ч. null = все экраны). */
  setTarget?: boolean
  /** Целевая аптека/экран; null = все экраны. Действует только при setTarget=true. */
  targetPharmacyId?: string | null
}

export interface AssignSlideRequest {
  playlistId: string | null
  position?: number
}

// ─── Connected registers (T4) — heartbeat подключённых касс (POSM-плеер) ──────
export interface ConnectedDeviceDto {
  deviceId: string
  pharmacyId: string | null
  lastSeen: string
}

export interface ConnectedScreensDto {
  total: number
  devices: ConnectedDeviceDto[]
}

// ─── AI-Exam (Этап 3.6) — банк вопросов (результаты/сертификаты в Этапе 4) ───
export type ExamQuestionKind = 'factual' | 'comparative' | 'scenario'

export interface ExamQuestionDto {
  id: string
  prompt: string
  kind: ExamQuestionKind
  category: string
  keywords: string[]
  difficulty: number
  createdAt: string
  updatedAt: string
}

export interface CreateExamQuestionRequest {
  prompt: string
  kind?: ExamQuestionKind
  category?: string
  keywords?: string[]
  difficulty?: number
}

export interface UpdateExamQuestionRequest {
  prompt?: string
  kind?: ExamQuestionKind
  category?: string
  keywords?: string[]
  difficulty?: number
}

// ─── Receipts / Reconcile (ТЗ §3.5) ──────────────────────────────────────
// Зеркало kz.epharm.receipts.dto.*
export type ReceiptStatus = 'pending' | 'moderation_required' | 'flagged' | 'approved' | 'rejected'

export interface ReceiptDto {
  id: string
  pharmacistId: string
  pharmacistName: string
  pharmacyId: string
  pharmacyName: string
  photoUrl: string | null
  parsedSku: string
  parsedAmount: number
  parsedCashier: string
  parsedAt: string | null
  status: ReceiptStatus
  autoApproved: boolean
  flagReason: string | null
  pendingBonusId: string | null
  /** CSV id акций (pr_*), заявленных фармацевтом при загрузке чека (контекст для модератора). */
  claimedPromoIds: string | null
  expectedAmount: number | null
  bonus: number | null
  bonusCredited: number
  source: string
  confirmedByLog: boolean
  confirmedByExcel: boolean
  reviewer: string | null
  reviewedAt: string | null
  createdAt: string
}

export interface ReconcileSummaryDto {
  queue: number
  moderationRequired: number
  flagged: number
  approved: number
  rejected: number
  autoApproved: number
}

export interface ExcelImportResultDto {
  importId: string
  fileName: string
  rowsTotal: number
  rowsMatched: number
}

// ── Витрина каталога (Medusa, read-only в админке) ──────────────────────────
// Зеркало backend MobileCatalogProductDto/MobileCatalogPageDto. Цена/бренд/фото
// nullable — реальный каталог наполняется постепенно.
export interface StorefrontProductDto {
  id: string
  name: string
  brand: string | null
  mnn: string | null
  rxOtc: string | null
  price: number | null
  currency: string
  imageUrl: string | null
  barcode: string | null
  category: string | null
}

export interface StorefrontPageDto {
  items: StorefrontProductDto[]
  total: number
  limit: number
  offset: number
}
