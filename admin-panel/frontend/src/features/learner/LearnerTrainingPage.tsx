import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import jsQR from 'jsqr'
import {
  AlertCircle,
  Award,
  ArrowLeft,
  BadgeCheck,
  Bell,
  BookOpen,
  Building2,
  Camera,
  CameraOff,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  CircleDollarSign,
  Download,
  Eye,
  EyeOff,
  ExternalLink,
  FileText,
  Gift,
  GraduationCap,
  Headphones,
  Home,
  Image,
  Lock,
  LogOut,
  Loader2,
  MapPin,
  PackageSearch,
  Phone,
  Pill,
  PlayCircle,
  QrCode,
  ReceiptText,
  RefreshCw,
  Search,
  ShieldCheck,
  Upload,
  UserRound,
  WalletCards,
  X,
} from 'lucide-react'
import { proxyMedia } from '../../lib/media'
import { useUiStore } from '@/app/store'
import { currentLearnerTranslate, learnerTranslate, useLearnerT } from './learner-i18n'

type Tokens = {
  accessToken: string
  refreshToken: string
}

type TokenLifecycle = {
  onRefreshed: (tokens: Tokens) => void
  onExpired: () => void
}

type Pharmacist = {
  id: string
  name: string
  phone: string
  pharmacyName: string | null
  city: string
  tier?: string
  balance?: number
  earned30d?: number
  coursesDone?: number
  coursesTotal?: number
}

type TrainingEvent = {
  id: string
  title: string
  startsAt?: string | null
  endsAt?: string | null
  timezone?: string
  city: string
  address: string
  mapUrl?: string | null
  capacity: number
  occupied: number
  status: string
}

type Certificate = {
  id: string
  number: string
  assignmentId: string
  programName: string
  format: string
  issuedAt?: string | null
  expiresAt?: string | null
  score?: number | null
  status: string
  pdfUrl?: string | null
}

type TrainingNotification = {
  id: string
  eventType: string
  title: string
  message: string
  assignmentId?: string | null
  eventId?: string | null
  read: boolean
  scheduledAt?: string | null
  readAt?: string | null
}

type TrainingReward = {
  id: string
  amount: number
  reason: string
  status: string
  issuedAt?: string | null
}

type PromotionTier = {
  minQty: number
  price: number
  bonus: number
}

type Promotion = {
  id: string
  title?: string | null
  productId: string
  name: string
  brand?: string | null
  mnn?: string | null
  rxOtc?: string | null
  imageUrl?: string | null
  overrideDescription?: string | null
  barcode?: string | null
  category?: string | null
  categories?: string[]
  dateStart?: string | null
  dateEnd?: string | null
  tiers: PromotionTier[]
}

type CatalogProduct = {
  id: string
  name: string
  brand?: string | null
  mnn?: string | null
  rxOtc?: string | null
  price?: number | null
  priceMin?: number | null
  priceMax?: number | null
  pharmacyPriceCount?: number | null
  currency?: string
  imageUrl?: string | null
  barcode?: string | null
  category?: string | null
  categories?: string[]
}

type CatalogPage = {
  items: CatalogProduct[]
  total: number
  limit: number
  offset: number
}

type CatalogDetail = CatalogProduct & {
  atc?: string | null
  images?: string[]
  country?: string | null
  manufacturer?: string | null
  description?: string | null
  keyFacts?: string[]
  marketplaceLinks?: Array<{ platform: string; url?: string | null; price?: number | null }>
  qa?: Array<{ q: string; a: string }>
  hasActiveCampaign?: boolean
  promoId?: string | null
  campaignTitle?: string | null
  bonus?: number | null
}

type MobileReceipt = {
  id: string
  status: 'inReview' | 'confirmed' | 'rejected' | string
  productName: string
  sku: string
  amount: number
  bonus?: number | null
  bonusCredited: number
  photoUrl?: string | null
  rejectedReason?: string | null
  pharmacyName: string
  createdAt: string
}

type PortalSection = 'home' | 'catalog' | 'receipts' | 'training' | 'profile'

type Lesson = {
  id: string
  title: string
  description?: string
  content?: string
  kind?: string
  videoUrl?: string | null
  externalUrl?: string | null
  required?: boolean
  minimumWatchPct?: number | null
  quizQuestions?: Array<{
    id: string
    prompt: string
    options: string[]
  }>
  quizPassingScore?: number
  quizScore?: number | null
  quizAttempts?: number
  durationMin?: number
  order?: number
  progressPct?: number | null
  lastPositionSeconds?: number | null
  completedAt?: string | null
  attachments?: Array<{
    id: string
    title: string
    fileName: string
    contentType: string
    mediaUrl: string
    sizeBytes: number
    kind: 'image' | 'video' | 'audio' | 'document'
  }>
}

type Course = {
  id: string
  title: string
  description?: string
  coverUrl?: string | null
  durationMin?: number
  totalDurationMin?: number
  lessons: Lesson[]
}

type Stage = {
  id: string
  title: string
  type: string
  status: string
  progressPct: number
  contentUrl?: string | null
  course?: Course | null
}

type Assignment = {
  id: string
  programId?: string
  programVersion?: number
  programName: string
  programShortDescription?: string
  coverUrl?: string | null
  pharmacyName: string
  city: string
  status: string
  format: string
  priority?: string
  required?: boolean
  event?: TrainingEvent | null
  startsAt?: string | null
  dueAt?: string | null
  progressPct: number
  score?: number | null
  startedAt?: string | null
  completedAt?: string | null
  stages: Stage[]
  certificate?: Certificate | null
  reward?: TrainingReward | null
}

type QuizSubmissionResult = {
  score: number
  passed: boolean
  correctAnswers: number
  totalQuestions: number
  attempt: number
  questions: Array<{
    questionId: string
    correct: boolean
    correctOption: number
    explanation: string
  }>
  assignment: Assignment
}

type Overview = {
  total: number
  inProgress: number
  completed: number
  overdue: number
  upcomingEvents?: TrainingEvent[]
  assignments: Assignment[]
  certificates?: Certificate[]
  notifications?: TrainingNotification[]
  defaultFormat?: string | null
}

const TOKEN_KEY = 'epharm.learner.tokens'

function lessonThreshold(lesson: Lesson) {
  return lesson.kind === 'video' || !!lesson.videoUrl
    ? Math.min(100, Math.max(1, lesson.minimumWatchPct ?? 80))
    : 100
}

function lessonCompleted(lesson: Lesson) {
  return !!lesson.completedAt || (lesson.progressPct ?? 0) >= lessonThreshold(lesson)
}

function readTokens(): Tokens | null {
  try {
    const raw = sessionStorage.getItem(TOKEN_KEY)
    return raw ? (JSON.parse(raw) as Tokens) : null
  } catch {
    return null
  }
}

function messageFrom(error: unknown) {
  if (error instanceof Error) return error.message
  return currentLearnerTranslate('Не удалось выполнить запрос')
}

class LearnerApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'LearnerApiError'
    this.status = status
  }
}

let refreshInFlight: Promise<Tokens> | null = null

async function fetchResponse(path: string, init: RequestInit, tokens?: Tokens | null) {
  return fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(tokens?.accessToken ? { Authorization: `Bearer ${tokens.accessToken}` } : {}),
      ...init.headers,
    },
  })
}

async function decodeResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new LearnerApiError(
      body?.message || currentLearnerTranslate('Сервер вернул ошибку {status}', { status: response.status }),
      response.status,
    )
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

async function refreshTokens(refreshToken: string): Promise<Tokens> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const response = await fetchResponse('/api/mobile/auth/refresh', {
        method: 'POST',
        body: JSON.stringify({ refreshToken }),
      })
      const result = await decodeResponse<{ tokens: Tokens }>(response)
      return result.tokens
    })().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  tokens?: Tokens | null,
  lifecycle?: TokenLifecycle,
): Promise<T> {
  let response = await fetchResponse(path, init, tokens)
  if (response.status === 401 && tokens?.refreshToken && lifecycle) {
    try {
      const refreshed = await refreshTokens(tokens.refreshToken)
      lifecycle.onRefreshed(refreshed)
      response = await fetchResponse(path, init, refreshed)
    } catch (error) {
      if (error instanceof LearnerApiError && error.status === 401) lifecycle.onExpired()
      throw error
    }
  }
  if (response.status === 401 && lifecycle) lifecycle.onExpired()
  return decodeResponse<T>(response)
}

async function requestForm<T>(
  path: string,
  body: FormData,
  tokens: Tokens,
  lifecycle: TokenLifecycle,
): Promise<T> {
  const send = (activeTokens: Tokens) => fetch(path, {
    method: 'POST',
    headers: { Authorization: `Bearer ${activeTokens.accessToken}` },
    body,
  })
  let response = await send(tokens)
  if (response.status === 401 && tokens.refreshToken) {
    try {
      const refreshed = await refreshTokens(tokens.refreshToken)
      lifecycle.onRefreshed(refreshed)
      response = await send(refreshed)
    } catch (error) {
      if (error instanceof LearnerApiError && error.status === 401) lifecycle.onExpired()
      throw error
    }
  }
  if (response.status === 401) lifecycle.onExpired()
  return decodeResponse<T>(response)
}

const statusLabel: Record<string, string> = {
  planned: 'Запланировано',
  scheduled: 'Запланировано',
  not_started: 'Не начато',
  in_progress: 'В процессе',
  waiting_online: 'Онлайн-этап',
  waiting_test: 'Ожидает тест',
  waiting_exam: 'Ожидает экзамен',
  waiting_event_selection: 'Выберите мероприятие',
  waiting_offline: 'Ожидает очный этап',
  waiting_attendance: 'Ожидает посещение',
  waiting_review: 'На проверке',
  retake_required: 'Нужна пересдача',
  completed: 'Завершено',
  overdue: 'Просрочено',
  paused: 'Приостановлено',
  cancelled: 'Отменено',
  available: 'Доступно',
  locked: 'Заблокировано',
  failed: 'Не пройдено',
  skipped: 'Пропущено',
}

const formatLabel: Record<string, string> = {
  online: 'Онлайн',
  hybrid: 'Гибрид',
  offline: 'Очно',
}

function statusText(value: string) {
  return currentLearnerTranslate(statusLabel[value] || value)
}

function trainingFormatText(value: string) {
  return currentLearnerTranslate(formatLabel[value] || value)
}

function formatDate(value?: string | null) {
  if (!value) return currentLearnerTranslate('Без срока')
  const locale = useUiStore.getState().language === 'kk' ? 'kk-KZ' : 'ru-RU'
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'long', year: 'numeric' })
    .format(new Date(value))
}

function formatDateTime(value?: string | null) {
  if (!value) return currentLearnerTranslate('Дата уточняется')
  const locale = useUiStore.getState().language === 'kk' ? 'kk-KZ' : 'ru-RU'
  return new Intl.DateTimeFormat(locale, {
    day: '2-digit',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatKzt(value?: number | null) {
  return `${new Intl.NumberFormat('ru-RU').format(value ?? 0)} ₸`
}

function promotionPeriod(promotion: Promotion) {
  if (!promotion.dateStart && !promotion.dateEnd) return currentLearnerTranslate('Постоянное предложение')
  if (promotion.dateStart && promotion.dateEnd) {
    return `${formatDate(promotion.dateStart)} — ${formatDate(promotion.dateEnd)}`
  }
  return promotion.dateStart
    ? currentLearnerTranslate('С {date}', { date: formatDate(promotion.dateStart) })
    : currentLearnerTranslate('До {date}', { date: formatDate(promotion.dateEnd) })
}

function extractQrToken(value: string) {
  const match = value.trim().match(/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/i)
  return match?.[0] ?? ''
}

function extractCheckInCode(value: string) {
  const digits = value.replace(/\D/g, '')
  return digits.length === 6 ? digits : ''
}

function formatIinInput(value: string) {
  const digits = value.replace(/\D/g, '').slice(0, 12)
  return digits.length > 6 ? `${digits.slice(0, 6)} ${digits.slice(6)}` : digits
}

type AuthStep = 'iin' | 'password' | 'phone' | 'otp' | 'create-password'

function normalizePhoneInput(value: string) {
  return value.replace(/\D/g, '').slice(0, 10)
}

export default function LearnerTrainingPage() {
  const t = useLearnerT()
  const language = useUiStore((state) => state.language)
  const navigate = useNavigate()
  const location = useLocation()
  const { assignmentId, lessonId } = useParams<{ assignmentId?: string; lessonId?: string }>()
  const portalSection: PortalSection = location.pathname.startsWith('/learn/catalog')
    ? 'catalog'
    : location.pathname.startsWith('/learn/receipts')
      ? 'receipts'
      : location.pathname.startsWith('/learn/profile')
        ? 'profile'
        : location.pathname.startsWith('/learn/training') || location.pathname.startsWith('/learn/course')
          ? 'training'
          : 'home'
  const [tokens, setTokens] = useState<Tokens | null>(() => readTokens())
  const [pharmacist, setPharmacist] = useState<Pharmacist | null>(null)
  const [overview, setOverview] = useState<Overview | null>(null)
  const [selected, setSelected] = useState<Assignment | null>(null)
  const [authStep, setAuthStep] = useState<AuthStep>('iin')
  const [iin, setIin] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [phone, setPhone] = useState('')
  const [phoneMasked, setPhoneMasked] = useState('')
  const [otpCode, setOtpCode] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(Boolean(tokens))
  const [error, setError] = useState('')
  const [availableEvents, setAvailableEvents] = useState<TrainingEvent[]>([])
  const [eventsLoading, setEventsLoading] = useState(false)
  const [attendanceOpen, setAttendanceOpen] = useState(false)
  const [promotions, setPromotions] = useState<Promotion[]>([])

  const clearSession = useCallback(() => {
    sessionStorage.removeItem(TOKEN_KEY)
    setTokens(null)
    setPharmacist(null)
    setOverview(null)
    setSelected(null)
    setAvailableEvents([])
    setPromotions([])
  }, [])

  const persistTokens = useCallback((nextTokens: Tokens) => {
    sessionStorage.setItem(TOKEN_KEY, JSON.stringify(nextTokens))
    setTokens(nextTokens)
  }, [])

  const tokenLifecycle = useMemo<TokenLifecycle>(() => ({
    onRefreshed: persistTokens,
    onExpired: clearSession,
  }), [clearSession, persistTokens])

  useEffect(() => {
    const previousTitle = document.title
    document.title = `ePharm — ${learnerTranslate(language, 'Обучение')}`
    document.body.classList.add('learner-portal')
    return () => {
      document.title = previousTitle
      document.body.classList.remove('learner-portal')
    }
  }, [language])

  const loadPortal = useCallback(async (activeTokens: Tokens) => {
    try {
      const [me, training, availablePromotions] = await Promise.all([
        request<Pharmacist>('/api/mobile/auth/me', {}, activeTokens, tokenLifecycle),
        request<Overview>('/api/mobile/training', {}, activeTokens, tokenLifecycle),
        request<Promotion[]>('/api/mobile/promotions').catch(() => []),
      ])
      setPharmacist(me)
      setOverview(training)
      setPromotions(availablePromotions)
    } catch (loadError) {
      setError(messageFrom(loadError))
    } finally {
      setLoading(false)
    }
  }, [tokenLifecycle])

  const authenticated = tokens !== null
  useEffect(() => {
    if (!authenticated) return
    const timer = window.setTimeout(() => {
      const activeTokens = readTokens()
      if (activeTokens) void loadPortal(activeTokens)
      else clearSession()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [authenticated, clearSession, loadPortal])

  const loadAssignment = useCallback(async (id: string, activeTokens: Tokens) => {
    setBusy(true)
    setError('')
    setAvailableEvents([])
    try {
      const detail = await request<Assignment>(
        `/api/mobile/training/assignments/${id}`,
        {},
        activeTokens,
        tokenLifecycle,
      )
      setSelected(detail)
      if (detail.format !== 'online' && !detail.event) {
        setEventsLoading(true)
        try {
          const events = await request<TrainingEvent[]>(
            `/api/mobile/training/assignments/${detail.id}/events`,
            {},
            activeTokens,
            tokenLifecycle,
          )
          setAvailableEvents(events)
        } finally {
          setEventsLoading(false)
        }
      }
    } catch (loadError) {
      setError(messageFrom(loadError))
    } finally {
      setBusy(false)
    }
  }, [tokenLifecycle])

  useEffect(() => {
    if (!tokens || !assignmentId) return
    const timer = window.setTimeout(() => void loadAssignment(assignmentId, tokens), 0)
    return () => window.clearTimeout(timer)
  }, [assignmentId, loadAssignment, tokens])

  async function checkIin(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const status = await request<{ passwordSet: boolean; phoneMasked: string }>(
        '/api/mobile/auth/activation/status',
        { method: 'POST', body: JSON.stringify({ iin }) },
      )
      setPhoneMasked(status.phoneMasked)
      setAuthStep(status.passwordSet ? 'password' : 'phone')
    } catch (statusError) {
      setError(messageFrom(statusError))
    } finally {
      setBusy(false)
    }
  }

  async function login(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const result = await request<{
        tokens: Tokens
        pharmacist: Pharmacist
      }>('/api/mobile/auth/login', {
        method: 'POST',
        body: JSON.stringify({ iin, password }),
      })
      persistTokens(result.tokens)
      setPharmacist(result.pharmacist)
    } catch (loginError) {
      setError(messageFrom(loginError))
    } finally {
      setBusy(false)
    }
  }

  function openAssignment(assignment: Assignment) {
    navigate(`/learn/course/${assignment.id}`)
  }

  async function requestActivationCode(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const result = await request<{ phoneMasked: string }>(
        '/api/mobile/auth/activation/sms/request',
        {
          method: 'POST',
          body: JSON.stringify({ iin, phone: `+7${phone}` }),
        },
      )
      setPhoneMasked(result.phoneMasked)
      setOtpCode('')
      setAuthStep('otp')
    } catch (requestError) {
      setError(messageFrom(requestError))
    } finally {
      setBusy(false)
    }
  }

  async function verifyActivationCode(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await request('/api/mobile/auth/activation/sms/verify', {
        method: 'POST',
        body: JSON.stringify({ iin, phone: `+7${phone}`, code: otpCode }),
      })
      setPassword('')
      setPasswordConfirmation('')
      setAuthStep('create-password')
    } catch (verifyError) {
      setError(messageFrom(verifyError))
    } finally {
      setBusy(false)
    }
  }

  async function createInitialPassword(event: React.FormEvent) {
    event.preventDefault()
    if (password !== passwordConfirmation) {
      setError(t('Пароли не совпадают'))
      return
    }
    setBusy(true)
    setError('')
    try {
      const result = await request<{ tokens: Tokens; pharmacist: Pharmacist }>(
        '/api/mobile/auth/activation/password',
        {
          method: 'POST',
          body: JSON.stringify({ iin, phone: `+7${phone}`, password }),
        },
      )
      persistTokens(result.tokens)
      setPharmacist(result.pharmacist)
    } catch (passwordError) {
      setError(messageFrom(passwordError))
    } finally {
      setBusy(false)
    }
  }

  function resetActivation() {
    setAuthStep('iin')
    setPassword('')
    setPasswordConfirmation('')
    setPhone('')
    setPhoneMasked('')
    setOtpCode('')
    setError('')
  }

  async function refreshPortal() {
    if (!tokens) return
    setLoading(true)
    setError('')
    await loadPortal(tokens)
  }

  async function startAssignment(assignment: Assignment) {
    if (!tokens) return
    setBusy(true)
    setError('')
    try {
      const updated = await request<Assignment>(
        `/api/mobile/training/assignments/${assignment.id}/start`,
        { method: 'POST' },
        tokens,
        tokenLifecycle,
      )
      setSelected(updated)
      const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
      setOverview(refreshed)
    } catch (startError) {
      setError(messageFrom(startError))
    } finally {
      setBusy(false)
    }
  }

  async function selectTrainingEvent(event: TrainingEvent) {
    if (!tokens || !selected) return
    setBusy(true)
    setError('')
    try {
      const updated = await request<Assignment>(
        `/api/mobile/training/assignments/${selected.id}/events/${event.id}`,
        { method: 'POST' },
        tokens,
        tokenLifecycle,
      )
      setSelected(updated)
      setAvailableEvents([])
      const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
      setOverview(refreshed)
    } catch (selectError) {
      setError(messageFrom(selectError))
    } finally {
      setBusy(false)
    }
  }

  async function checkInByQr(rawValue: string) {
    if (!tokens) return false
    const qrToken = extractQrToken(rawValue)
    const checkInCode = extractCheckInCode(rawValue)
    if (!qrToken && !checkInCode) {
      setError(t('Введите 6-значный код мероприятия или вставьте ссылку из QR-кода.'))
      return false
    }
    setBusy(true)
    setError('')
    try {
      const updated = await request<Assignment>(
        qrToken
          ? `/api/mobile/training/events/check-in/${qrToken}`
          : `/api/mobile/training/events/check-in-code/${checkInCode}`,
        { method: 'POST' },
        tokens,
        tokenLifecycle,
      )
      setSelected(updated)
      const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
      setOverview(refreshed)
      setAttendanceOpen(false)
      navigate(`/learn/course/${updated.id}`)
      return true
    } catch (checkInError) {
      setError(messageFrom(checkInError))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function openNotification(notification: TrainingNotification) {
    if (!tokens) return
    if (!notification.read) {
      try {
        await request<TrainingNotification>(
          `/api/mobile/training/notifications/${notification.id}/read`,
          { method: 'PATCH' },
          tokens,
          tokenLifecycle,
        )
        setOverview((current) => current ? {
          ...current,
          notifications: (current.notifications ?? []).map((item) =>
            item.id === notification.id ? { ...item, read: true, readAt: new Date().toISOString() } : item,
          ),
        } : current)
      } catch (notificationError) {
        setError(messageFrom(notificationError))
        return
      }
    }
    if (notification.assignmentId) navigate(`/learn/course/${notification.assignmentId}`)
  }

  async function completeStage(stage: Stage) {
    if (!tokens || !selected) return
    setBusy(true)
    setError('')
    try {
      const updated = await request<Assignment>(
        `/api/mobile/training/assignments/${selected.id}/stages/${stage.id}`,
        { method: 'PATCH', body: JSON.stringify({ progressPct: 100 }) },
        tokens,
        tokenLifecycle,
      )
      setSelected(updated)
      const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
      setOverview(refreshed)
    } catch (completeError) {
      setError(messageFrom(completeError))
    } finally {
      setBusy(false)
    }
  }

  async function saveLessonProgress(
    stage: Stage,
    lesson: Lesson,
    progressPct: number,
    positionSeconds: number,
    refreshOverview = false,
  ) {
    if (!tokens || !selected) return null
    try {
      const updated = await request<Assignment>(
        `/api/mobile/training/assignments/${selected.id}/stages/${stage.id}/lessons/${lesson.id}`,
        {
          method: 'PATCH',
          body: JSON.stringify({
            progressPct: Math.min(100, Math.max(0, Math.round(progressPct))),
            positionSeconds: Math.max(0, Math.round(positionSeconds)),
          }),
        },
        tokens,
        tokenLifecycle,
      )
      setSelected(updated)
      if (refreshOverview) {
        const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
        setOverview(refreshed)
      }
      return updated
    } catch (progressError) {
      setError(messageFrom(progressError))
      return null
    }
  }

  async function submitLessonQuiz(stage: Stage, lesson: Lesson, answers: Record<string, number>) {
    if (!tokens || !selected) return null
    try {
      const result = await request<QuizSubmissionResult>(
        `/api/mobile/training/assignments/${selected.id}/stages/${stage.id}/lessons/${lesson.id}/quiz`,
        { method: 'POST', body: JSON.stringify({ answers }) },
        tokens,
        tokenLifecycle,
      )
      setSelected(result.assignment)
      const refreshed = await request<Overview>('/api/mobile/training', {}, tokens, tokenLifecycle)
      setOverview(refreshed)
      return result
    } catch (quizError) {
      setError(messageFrom(quizError))
      return null
    }
  }

  async function logout() {
    const activeTokens = tokens
    try {
      if (activeTokens) {
        await request('/api/mobile/auth/logout', { method: 'POST' }, activeTokens, tokenLifecycle)
      }
    } catch {
      // Local logout must always succeed even when the server is unavailable.
    } finally {
      clearSession()
      setPassword('')
      navigate('/learn')
    }
  }

  if (!tokens) {
    return (
      <LearnerAuthLayout>
        <div className="learner-auth-card">
          <div className="learner-auth-brand-panel">
            <div>
              <div className="flex items-center gap-3">
                <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-white/10 text-brand-green-200">
                  <BookOpen size={24} />
                </span>
                <div>
                  <div className="text-2xl font-extrabold tracking-tight text-white">ePharm</div>
                  <div className="text-xs font-semibold text-brand-green-200">{t('Обучение фармацевтов')}</div>
                </div>
              </div>
              <div className="mt-12 hidden md:block">
                <div className="text-xs font-extrabold uppercase tracking-[0.18em] text-brand-green-300">{t('Единая платформа')}</div>
                <h2 className="mt-4 max-w-sm text-4xl font-extrabold leading-tight text-white">
                  {t('Развивайтесь в профессии каждый день')}
                </h2>
                <p className="mt-4 max-w-sm text-sm leading-6 text-brand-green-100/80">
                  {t('Курсы, очные мероприятия, сертификаты и полезные материалы — в одном защищённом кабинете.')}
                </p>
              </div>
            </div>
            <div className="hidden grid-cols-3 gap-3 md:grid">
              {[
                ['01', t('Обучайтесь')],
                ['02', t('Закрепляйте')],
                ['03', t('Получайте сертификат')],
              ].map(([number, label]) => (
                <div key={number} className="rounded-xl border border-white/10 bg-white/[0.06] p-3">
                  <div className="text-xs font-extrabold text-brand-green-300">{number}</div>
                  <div className="mt-1 text-xs font-semibold leading-5 text-white/80">{label}</div>
                </div>
              ))}
            </div>
          </div>

          <div className="learner-auth-form-panel relative">
            <LanguageSwitcher className="absolute right-5 top-5" />
            <div className="mx-auto w-full max-w-md">
              <div className="mb-7 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100 text-brand-green-700 md:hidden">
                <ShieldCheck size={24} />
              </div>
              <div className="text-xs font-extrabold uppercase tracking-[0.16em] text-brand-green-700">{t('Личный кабинет')}</div>
              <h1 className="mt-2 text-3xl font-extrabold tracking-tight text-ink-900">
                {authStep === 'iin' && t('Вход для фармацевта')}
                {authStep === 'password' && t('Введите пароль')}
                {authStep === 'phone' && t('Подтвердите телефон')}
                {authStep === 'otp' && t('Введите код из SMS')}
                {authStep === 'create-password' && t('Создайте пароль')}
              </h1>
              <p className="mt-3 text-sm leading-6 text-ink-500">
                {authStep === 'iin' && t('Сначала введите ИИН. При первом входе вы подтвердите телефон и создадите собственный пароль.')}
                {authStep === 'password' && t('Пароль закреплён за указанным ИИН.')}
                {authStep === 'phone' && t('Введите номер телефона. Мы отправим код и после подтверждения закрепим номер за этим ИИН.')}
                {authStep === 'otp' && t('Код отправлен на номер {phone}', { phone: phoneMasked })}
                {authStep === 'create-password' && t('Придумайте пароль не короче 8 символов. Он будет закреплён за вашим ИИН.')}
              </p>
              {authStep !== 'iin' && (
                <div className="mt-5 flex items-center justify-between rounded-xl border border-ink-100 bg-paper-hover px-4 py-3">
                  <div>
                    <div className="text-[11px] font-bold uppercase tracking-[0.08em] text-ink-400">{t('ИИН')}</div>
                    <div className="mt-0.5 font-mono text-sm font-bold text-ink-800">{formatIinInput(iin)}</div>
                  </div>
                  <button type="button" className="text-sm font-bold text-brand-green-700" onClick={resetActivation}>
                    {t('Изменить')}
                  </button>
                </div>
              )}
              {error && <ErrorMessage text={error} />}
              {authStep === 'iin' && (
                <form className="mt-8 space-y-5" onSubmit={checkIin}>
                  <label className="block text-sm font-bold text-ink-700">
                    {t('ИИН')}
                    <input
                      className="inp mt-2 h-12 font-mono text-base tracking-[0.08em]"
                      inputMode="numeric"
                      autoComplete="username"
                      value={formatIinInput(iin)}
                      onChange={(event) => setIin(event.target.value.replace(/\D/g, '').slice(0, 12))}
                      placeholder="000000 000000"
                      minLength={13}
                      maxLength={13}
                      autoFocus
                      required
                    />
                  </label>
                  <button className="btn btn-primary h-12 w-full text-base" disabled={busy || iin.length !== 12}>
                    {busy ? t('Проверяем…') : t('Продолжить')}
                  </button>
                </form>
              )}
              {authStep === 'password' && (
                <form className="mt-6 space-y-5" onSubmit={login}>
                  <PasswordInput
                    label={t('Пароль')}
                    value={password}
                    onChange={setPassword}
                    visible={showPassword}
                    onToggle={() => setShowPassword((current) => !current)}
                    autoComplete="current-password"
                    t={t}
                  />
                  <button className="btn btn-primary h-12 w-full text-base" disabled={busy || password.length < 8}>
                    {busy ? t('Входим…') : t('Войти')}
                  </button>
                </form>
              )}
              {authStep === 'phone' && (
                <form className="mt-6 space-y-5" onSubmit={requestActivationCode}>
                  <label className="block text-sm font-bold text-ink-700">
                    {t('Номер телефона')}
                    <span className="relative mt-2 block">
                      <Phone className="absolute left-4 top-3.5 text-ink-400" size={19} />
                      <span className="absolute left-11 top-3 font-mono text-base font-bold text-ink-700">+7</span>
                      <input
                        className="inp h-12 pl-[4.5rem] font-mono text-base tracking-[0.04em]"
                        aria-label={t('Номер телефона')}
                        inputMode="tel"
                        autoComplete="tel"
                        value={phone}
                        onChange={(event) => setPhone(normalizePhoneInput(event.target.value))}
                        placeholder="7470799353"
                        autoFocus
                        required
                      />
                    </span>
                  </label>
                  {phoneMasked && <p className="text-xs text-ink-400">{t('Подсказка: номер в профиле {phone}', { phone: phoneMasked })}</p>}
                  <button className="btn btn-primary h-12 w-full text-base" disabled={busy || phone.length !== 10}>
                    {busy ? t('Отправляем…') : t('Получить код')}
                  </button>
                </form>
              )}
              {authStep === 'otp' && (
                <form className="mt-6 space-y-5" onSubmit={verifyActivationCode}>
                  <label className="block text-sm font-bold text-ink-700">
                    {t('Код из SMS')}
                    <input
                      className="inp mt-2 h-14 text-center font-mono text-2xl font-extrabold tracking-[0.35em]"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      value={otpCode}
                      onChange={(event) => setOtpCode(event.target.value.replace(/\D/g, '').slice(0, 8))}
                      placeholder="••••"
                      minLength={4}
                      maxLength={8}
                      autoFocus
                      required
                    />
                  </label>
                  <button className="btn btn-primary h-12 w-full text-base" disabled={busy || otpCode.length < 4}>
                    {busy ? t('Проверяем…') : t('Подтвердить код')}
                  </button>
                  <button type="button" className="w-full text-sm font-bold text-brand-green-700" onClick={() => setAuthStep('phone')}>
                    {t('Изменить номер')}
                  </button>
                </form>
              )}
              {authStep === 'create-password' && (
                <form className="mt-6 space-y-5" onSubmit={createInitialPassword}>
                  <PasswordInput
                    label={t('Новый пароль')}
                    value={password}
                    onChange={setPassword}
                    visible={showPassword}
                    onToggle={() => setShowPassword((current) => !current)}
                    autoComplete="new-password"
                    t={t}
                  />
                  <PasswordInput
                    label={t('Повторите пароль')}
                    value={passwordConfirmation}
                    onChange={setPasswordConfirmation}
                    visible={showPassword}
                    onToggle={() => setShowPassword((current) => !current)}
                    autoComplete="new-password"
                    t={t}
                  />
                  <button
                    className="btn btn-primary h-12 w-full text-base"
                    disabled={busy || password.length < 8 || passwordConfirmation.length < 8}
                  >
                    {busy ? t('Сохраняем…') : t('Создать пароль и войти')}
                  </button>
                </form>
              )}
              <div className="mt-7 flex items-start gap-2 border-t border-ink-100 pt-5 text-xs leading-5 text-ink-400">
                <ShieldCheck className="mt-0.5 shrink-0 text-brand-green-600" size={16} />
                {t('Пароль передаётся по защищённому соединению и хранится на сервере только в виде BCrypt-хеша.')}
              </div>
            </div>
          </div>
        </div>
      </LearnerAuthLayout>
    )
  }

  const sectionTitle: Record<PortalSection, string> = {
    home: t('Здравствуйте, {name}', { name: pharmacist?.name ?? '' }),
    catalog: t('Каталог препаратов'),
    receipts: t('Мои чеки'),
    training: t('Обучение'),
    profile: t('Профиль'),
  }

  return (
    <LearnerLayout>
      <div className="learner-app-frame">
        {!selected && (
          <PortalNavigation
            active={portalSection}
            pharmacist={pharmacist}
            onLogout={logout}
            onNavigate={(section) => navigate(section === 'home' ? '/learn' : `/learn/${section}`)}
          />
        )}
        <div className={`min-w-0 pb-24 lg:pb-4 ${selected ? 'lg:col-span-2 lg:mx-auto lg:w-full lg:max-w-6xl' : ''}`}>
        <header className="learner-page-header">
          <div>
            <div className="text-[11px] font-extrabold uppercase tracking-[0.18em] text-brand-green-700">{t('Кабинет фармацевта')}</div>
            <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-ink-900 sm:text-[28px]">
              {selected ? selected.programName : sectionTitle[portalSection]}
            </h1>
            {!selected && portalSection === 'home' && (
              <p className="mt-1 text-sm text-ink-500">
                {pharmacist?.pharmacyName || t('Аптека не назначена')}{pharmacist?.city ? ` · ${pharmacist.city}` : ''}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2">
            {!selected && (
              <button className="btn btn-outline btn-md hidden shrink-0 sm:inline-flex" disabled={busy} onClick={refreshPortal}>
                <RefreshCw className={busy ? 'animate-spin' : ''} size={16} /> {t('Обновить')}
              </button>
            )}
            <LanguageSwitcher compact />
            <button className="btn btn-outline btn-icon shrink-0 lg:hidden" onClick={() => void logout()} aria-label={t('Выйти')}>
              <LogOut size={17} />
            </button>
          </div>
        </header>

        {error && <ErrorMessage text={error} />}
        {loading ? (
          <div className="flex items-center justify-center py-24 text-ink-500">
            <RefreshCw className="mr-2 animate-spin" size={20} /> {t('Загружаем обучение…')}
          </div>
        ) : selected && lessonId ? (
          <LessonPage
            assignment={selected}
            lessonId={lessonId}
            busy={busy}
            onBack={() => navigate(`/learn/course/${selected.id}`)}
            onOpenLesson={(nextLessonId) =>
              navigate(`/learn/course/${selected.id}/lesson/${nextLessonId}`)
            }
            onSaveProgress={saveLessonProgress}
            onSubmitQuiz={submitLessonQuiz}
          />
        ) : selected ? (
          <AssignmentView
            assignment={selected}
            busy={busy}
            availableEvents={availableEvents}
            eventsLoading={eventsLoading}
            onBack={() => {
              setSelected(null)
              navigate('/learn')
            }}
            onStart={() => startAssignment(selected)}
            onSelectEvent={selectTrainingEvent}
            onCheckIn={() => setAttendanceOpen(true)}
            onOpenLesson={(nextLessonId) =>
              navigate(`/learn/course/${selected.id}/lesson/${nextLessonId}`)
            }
            onComplete={completeStage}
          />
        ) : portalSection === 'catalog' ? (
          <CatalogView tokens={tokens} lifecycle={tokenLifecycle} />
        ) : portalSection === 'receipts' ? (
          <ReceiptsView tokens={tokens} lifecycle={tokenLifecycle} />
        ) : portalSection === 'profile' ? (
          <ProfileView pharmacist={pharmacist} overview={overview} onLogout={logout} />
        ) : (
          <OverviewView
            overview={overview}
            pharmacist={pharmacist}
            promotions={promotions}
            busy={busy}
            onOpen={openAssignment}
            onRefresh={refreshPortal}
            onOpenNotification={openNotification}
            onCheckIn={() => setAttendanceOpen(true)}
            trainingOnly={portalSection === 'training'}
          />
        )}
        </div>
      </div>
      {attendanceOpen && (
        <AttendanceDialog
          busy={busy}
          onClose={() => setAttendanceOpen(false)}
          onSubmit={checkInByQr}
        />
      )}
    </LearnerLayout>
  )
}

function LearnerAuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className="learner-auth-shell">
      {children}
    </main>
  )
}

function LanguageSwitcher({ className = '', compact = false }: { className?: string; compact?: boolean }) {
  const language = useUiStore((state) => state.language)
  const setLanguage = useUiStore((state) => state.setLanguage)
  return (
    <div className={`inline-flex rounded-xl border border-ink-100 bg-white p-1 shadow-sm ${className}`} role="group" aria-label="Тіл / Язык">
      {([
        ['ru', compact ? 'RU' : 'Рус'],
        ['kk', compact ? 'ҚАЗ' : 'Қаз'],
      ] as const).map(([value, label]) => (
        <button
          key={value}
          type="button"
          className={`rounded-lg px-2.5 py-1.5 text-xs font-extrabold transition ${language === value ? 'bg-brand-green-700 text-white' : 'text-ink-500 hover:bg-paper-hover'}`}
          aria-pressed={language === value}
          onClick={() => setLanguage(value)}
        >
          {label}
        </button>
      ))}
    </div>
  )
}

function LearnerLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className="min-h-screen bg-paper px-4 py-4 sm:px-6 sm:py-6 lg:px-5 lg:py-5">
      {children}
    </main>
  )
}

function PasswordInput({
  label,
  value,
  onChange,
  visible,
  onToggle,
  autoComplete,
  t,
}: {
  label: string
  value: string
  onChange: (value: string) => void
  visible: boolean
  onToggle: () => void
  autoComplete: string
  t: (source: string) => string
}) {
  return (
    <label className="block text-sm font-bold text-ink-700">
      {label}
      <span className="relative mt-2 block">
        <input
          className="inp h-12 pr-12 text-base"
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={t('Введите пароль')}
          minLength={8}
          maxLength={128}
          required
        />
        <button
          type="button"
          className="absolute right-1 top-1 flex h-10 w-10 items-center justify-center rounded-lg text-ink-400 transition hover:bg-paper-hover hover:text-ink-700"
          onClick={onToggle}
          aria-label={visible ? t('Скрыть пароль') : t('Показать пароль')}
        >
          {visible ? <EyeOff size={18} /> : <Eye size={18} />}
        </button>
      </span>
    </label>
  )
}

function ErrorMessage({ text }: { text: string }) {
  return <div className="mt-5 rounded-xl bg-surface-danger px-4 py-3 text-sm font-semibold text-accent-danger">{text}</div>
}

function PortalNavigation({
  active,
  pharmacist,
  onLogout,
  onNavigate,
}: {
  active: PortalSection
  pharmacist: Pharmacist | null
  onLogout: () => void | Promise<void>
  onNavigate: (section: PortalSection) => void
}) {
  const t = useLearnerT()
  const items = [
    { id: 'home' as const, label: t('Главная'), icon: Home },
    { id: 'catalog' as const, label: t('Каталог'), icon: Pill },
    { id: 'receipts' as const, label: t('Чеки'), icon: ReceiptText },
    { id: 'training' as const, label: t('Обучение'), icon: GraduationCap },
    { id: 'profile' as const, label: t('Профиль'), icon: UserRound },
  ]
  return (
    <nav className="learner-navigation" aria-label={t('Разделы приложения')}>
      <div className="hidden lg:block">
        <div className="flex items-center gap-3 px-3 pb-7 pt-2">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/10 text-brand-green-200"><BookOpen size={21} /></span>
          <div>
            <div className="text-xl font-extrabold tracking-tight text-white">ePharm</div>
            <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-brand-green-200">{t('Фармацевт')}</div>
          </div>
        </div>
        <div className="mb-3 px-3 text-[10px] font-extrabold uppercase tracking-[0.16em] text-white/40">{t('Навигация')}</div>
      </div>
      <div className="mx-auto grid max-w-6xl grid-cols-5 gap-1 lg:flex lg:flex-col lg:gap-1">
        {items.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            className={`learner-nav-item ${active === id ? 'is-active' : ''}`}
            onClick={() => onNavigate(id)}
            aria-current={active === id ? 'page' : undefined}
          >
            <Icon size={19} />
            <span className="truncate">{label}</span>
          </button>
        ))}
      </div>
      <div className="mt-auto hidden border-t border-white/10 pt-4 lg:block">
        <div className="flex items-center gap-3 px-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-green-400 text-sm font-extrabold text-ink-900">
            {(pharmacist?.name || 'Ф').slice(0, 1)}
          </span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-xs font-extrabold text-white">{pharmacist?.name || t('Фармацевт')}</div>
            <div className="mt-0.5 truncate text-[10px] font-semibold text-white/45">{pharmacist?.pharmacyName || t('Аптека не назначена')}</div>
          </div>
          <button className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-white/55 transition hover:bg-white/10 hover:text-white" onClick={() => void onLogout()} aria-label={t('Выйти')}>
            <LogOut size={17} />
          </button>
        </div>
      </div>
    </nav>
  )
}

function CatalogView({ tokens, lifecycle }: { tokens: Tokens; lifecycle: TokenLifecycle }) {
  const t = useLearnerT()
  const [query, setQuery] = useState('')
  const [page, setPage] = useState<CatalogPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<CatalogDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    const timer = window.setTimeout(async () => {
      setLoading(true)
      setError('')
      try {
        const params = new URLSearchParams({ limit: '48', offset: '0' })
        if (query.trim()) params.set('q', query.trim())
        const result = await request<CatalogPage>(`/api/mobile/catalog/products?${params}`, {}, tokens, lifecycle)
        if (!cancelled) setPage(result)
      } catch (loadError) {
        if (!cancelled) setError(messageFrom(loadError))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [lifecycle, query, tokens])

  async function openProduct(product: CatalogProduct) {
    setDetailLoading(true)
    setError('')
    try {
      const detail = await request<CatalogDetail>(
        `/api/mobile/catalog/products/${encodeURIComponent(product.id)}`,
        {},
        tokens,
        lifecycle,
      )
      setSelected(detail)
    } catch (loadError) {
      setError(messageFrom(loadError))
    } finally {
      setDetailLoading(false)
    }
  }

  return (
    <section>
      <div className="mb-5 rounded-2xl bg-white p-4 shadow-card sm:p-5">
        <label className="relative block">
          <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink-400" size={19} />
          <input
            className="inp h-12 w-full pl-11"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t('Название, бренд или МНН')}
            aria-label={t('Поиск препаратов')}
          />
        </label>
        <div className="mt-3 flex items-center justify-between text-xs font-semibold text-ink-400">
          <span>{t('Данные синхронизированы с мобильным приложением')}</span>
          {page && <span>{t('{count} товаров', { count: page.total })}</span>}
        </div>
      </div>

      {error && <ErrorMessage text={error} />}
      {loading ? (
        <div className="flex items-center justify-center py-24 text-ink-500"><Loader2 className="mr-2 animate-spin" size={20} /> {t('Загружаем каталог…')}</div>
      ) : !page?.items.length ? (
        <div className="rounded-2xl bg-white p-10 text-center shadow-card">
          <Pill className="mx-auto text-ink-300" size={38} />
          <p className="mt-3 font-bold text-ink-700">{t('Препараты не найдены')}</p>
          <p className="mt-1 text-sm text-ink-400">{t('Измените поисковый запрос')}</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {page.items.map((product) => (
            <CatalogProductCard key={product.id} product={product} onOpen={() => void openProduct(product)} />
          ))}
        </div>
      )}
      {detailLoading && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-900/45"><Loader2 className="animate-spin text-white" size={36} /></div>
      )}
      {selected && <CatalogProductDialog product={selected} onClose={() => setSelected(null)} />}
    </section>
  )
}

function CatalogProductCard({ product, onOpen }: { product: CatalogProduct; onOpen: () => void }) {
  const t = useLearnerT()
  const image = proxyMedia(product.imageUrl)
  const price = product.price ?? product.priceMin
  return (
    <button className="group overflow-hidden rounded-2xl bg-white text-left shadow-card transition hover:-translate-y-0.5 hover:shadow-fab" onClick={onOpen}>
      <div className="relative aspect-square overflow-hidden bg-paper-hover">
        {image ? (
          <img className="h-full w-full object-contain p-3 transition duration-300 group-hover:scale-105" src={image} alt="" loading="lazy" />
        ) : (
          <div className="flex h-full items-center justify-center text-4xl font-extrabold text-brand-green-600">{product.name.slice(0, 1).toUpperCase()}</div>
        )}
        {product.rxOtc && <span className="absolute left-2 top-2 rounded-lg bg-white/90 px-2 py-1 text-[10px] font-extrabold text-brand-green-700">{product.rxOtc.toUpperCase()}</span>}
      </div>
      <div className="p-3.5">
        <p className="truncate text-[11px] font-bold uppercase tracking-wide text-ink-400">{product.brand || product.category || 'ePharm'}</p>
        <h3 className="mt-1 line-clamp-2 min-h-10 text-sm font-extrabold leading-5 text-ink-900 group-hover:text-brand-green-700">{product.name}</h3>
        <div className="mt-3 flex items-end justify-between gap-2">
          <span className="text-sm font-extrabold text-brand-green-700">{price != null ? formatKzt(price) : t('Цена в аптеке')}</span>
          <ChevronRight className="shrink-0 text-ink-300" size={18} />
        </div>
      </div>
    </button>
  )
}

function CatalogProductDialog({ product, onClose }: { product: CatalogDetail; onClose: () => void }) {
  const t = useLearnerT()
  const image = proxyMedia(product.imageUrl || product.images?.[0])
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-900/55 p-4" role="presentation" onMouseDown={(event) => {
      if (event.currentTarget === event.target) onClose()
    }}>
      <section className="max-h-[92vh] w-full max-w-4xl overflow-y-auto rounded-3xl bg-white shadow-fab" role="dialog" aria-modal="true" aria-labelledby="catalog-product-title">
        <div className="sticky top-0 z-10 flex justify-end bg-white/90 p-3 backdrop-blur">
          <button className="btn btn-ghost h-10 w-10 rounded-full p-0" onClick={onClose} aria-label={t('Закрыть карточку')}><X size={20} /></button>
        </div>
        <div className="grid gap-6 px-5 pb-7 sm:grid-cols-[280px_1fr] sm:px-8">
          <div className="aspect-square overflow-hidden rounded-2xl bg-paper-hover">
            {image ? <img className="h-full w-full object-contain p-5" src={image} alt={product.name} /> : <div className="flex h-full items-center justify-center text-6xl font-extrabold text-brand-green-600">{product.name.slice(0, 1)}</div>}
          </div>
          <div>
            <div className="flex flex-wrap gap-2">
              {product.rxOtc && <span className="chip chip-green">{product.rxOtc.toUpperCase()}</span>}
              {product.category && <span className="chip chip-ink">{product.category}</span>}
              {product.hasActiveCampaign && <span className="chip chip-blue">{t('Акция')}</span>}
            </div>
            <h2 id="catalog-product-title" className="mt-3 text-2xl font-extrabold leading-tight text-ink-900">{product.name}</h2>
            {product.brand && <p className="mt-2 font-bold text-brand-green-700">{product.brand}</p>}
            <div className="mt-5 text-2xl font-extrabold text-ink-900">{product.price != null ? formatKzt(product.price) : t('Цена в аптеке')}</div>
            {product.bonus != null && product.bonus > 0 && (
              <div className="mt-3 inline-flex items-center gap-2 rounded-xl bg-brand-green-700 px-4 py-3 font-extrabold text-white"><Gift size={18} /> {t('Бонус {value}', { value: formatKzt(product.bonus) })}</div>
            )}
            <dl className="mt-6 grid gap-3 text-sm sm:grid-cols-2">
              {product.mnn && <div><dt className="font-bold text-ink-400">{t('МНН')}</dt><dd className="mt-1 font-semibold text-ink-700">{product.mnn}</dd></div>}
              {product.manufacturer && <div><dt className="font-bold text-ink-400">{t('Производитель')}</dt><dd className="mt-1 font-semibold text-ink-700">{product.manufacturer}</dd></div>}
              {product.country && <div><dt className="font-bold text-ink-400">{t('Страна')}</dt><dd className="mt-1 font-semibold text-ink-700">{product.country}</dd></div>}
              {product.barcode && <div><dt className="font-bold text-ink-400">{t('Штрихкод')}</dt><dd className="mt-1 font-mono font-semibold text-ink-700">{product.barcode}</dd></div>}
            </dl>
          </div>
        </div>
        {(product.description || product.keyFacts?.length) && (
          <div className="border-t border-ink-100 px-5 py-6 sm:px-8">
            <h3 className="font-extrabold text-ink-900">{t('О препарате')}</h3>
            {product.description && <p className="mt-3 whitespace-pre-line text-sm leading-6 text-ink-600">{product.description}</p>}
            {!!product.keyFacts?.length && <ul className="mt-4 space-y-2">{product.keyFacts.map((fact) => <li key={fact} className="flex gap-2 text-sm text-ink-600"><CheckCircle2 className="mt-0.5 shrink-0 text-brand-green-600" size={17} />{fact}</li>)}</ul>}
          </div>
        )}
      </section>
    </div>
  )
}

function ReceiptsView({ tokens, lifecycle }: { tokens: Tokens; lifecycle: TokenLifecycle }) {
  const t = useLearnerT()
  const [receipts, setReceipts] = useState<MobileReceipt[]>([])
  const [file, setFile] = useState<File | null>(null)
  const [loading, setLoading] = useState(true)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')

  const loadReceipts = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setReceipts(await request<MobileReceipt[]>('/api/mobile/receipts', {}, tokens, lifecycle))
    } catch (loadError) {
      setError(messageFrom(loadError))
    } finally {
      setLoading(false)
    }
  }, [lifecycle, tokens])

  useEffect(() => {
    const timer = window.setTimeout(() => void loadReceipts(), 0)
    return () => window.clearTimeout(timer)
  }, [loadReceipts])

  async function uploadReceipt() {
    if (!file) return
    setUploading(true)
    setError('')
    try {
      const body = new FormData()
      body.append('file', file)
      const created = await requestForm<MobileReceipt>('/api/mobile/receipts', body, tokens, lifecycle)
      setReceipts((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setFile(null)
    } catch (uploadError) {
      setError(messageFrom(uploadError))
    } finally {
      setUploading(false)
    }
  }

  return (
    <section className="grid gap-5 lg:grid-cols-[360px_1fr]">
      <div>
        <div className="rounded-2xl bg-white p-5 shadow-card lg:sticky lg:top-5">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-green-100 text-brand-green-700"><Camera size={24} /></div>
          <h2 className="mt-4 text-lg font-extrabold text-ink-900">{t('Загрузить чек')}</h2>
          <p className="mt-2 text-sm leading-6 text-ink-500">{t('Сфотографируйте чек целиком. Аптека и подходящие акции определятся автоматически.')}</p>
          <label className="mt-5 flex min-h-32 cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed border-ink-200 bg-paper-hover p-4 text-center transition hover:border-brand-green-400">
            <Upload className="text-brand-green-600" size={28} />
            <span className="mt-2 text-sm font-extrabold text-ink-700">{file ? file.name : t('Выбрать фото чека')}</span>
            <span className="mt-1 text-xs text-ink-400">{t('JPG, PNG или HEIC')}</span>
            <input className="sr-only" type="file" accept="image/*" capture="environment" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
          <button className="btn btn-primary btn-md mt-4 w-full" disabled={!file || uploading} onClick={() => void uploadReceipt()}>
            {uploading ? <Loader2 className="animate-spin" size={18} /> : <Upload size={18} />} {uploading ? t('Отправляем…') : t('Отправить на проверку')}
          </button>
          {error && <ErrorMessage text={error} />}
        </div>
      </div>
      <div>
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 className="text-lg font-extrabold text-ink-900">{t('История чеков')}</h2>
          <button className="btn btn-ghost" disabled={loading} onClick={() => void loadReceipts()}><RefreshCw className={loading ? 'animate-spin' : ''} size={17} /> {t('Обновить')}</button>
        </div>
        {loading ? (
          <div className="flex items-center justify-center py-20 text-ink-500"><Loader2 className="mr-2 animate-spin" size={20} /> {t('Загружаем чеки…')}</div>
        ) : receipts.length === 0 ? (
          <div className="rounded-2xl bg-white p-10 text-center shadow-card"><ReceiptText className="mx-auto text-ink-300" size={38} /><p className="mt-3 font-bold text-ink-700">{t('Чеков пока нет')}</p><p className="mt-1 text-sm text-ink-400">{t('Загруженные чеки появятся здесь')}</p></div>
        ) : (
          <div className="space-y-3">{receipts.map((receipt) => <ReceiptCard key={receipt.id} receipt={receipt} />)}</div>
        )}
      </div>
    </section>
  )
}

function ReceiptCard({ receipt }: { receipt: MobileReceipt }) {
  const t = useLearnerT()
  const status = receipt.status === 'confirmed'
    ? { label: t('Подтверждён'), classes: 'bg-brand-green-100 text-brand-green-700', icon: CheckCircle2 }
    : receipt.status === 'rejected'
      ? { label: t('Отклонён'), classes: 'bg-surface-danger text-accent-danger', icon: AlertCircle }
      : { label: t('На проверке'), classes: 'bg-blue-50 text-blue-700', icon: Clock3 }
  const StatusIcon = status.icon
  return (
    <article className="rounded-2xl bg-white p-4 shadow-card sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0"><h3 className="truncate font-extrabold text-ink-900">{receipt.productName || t('Фискальный чек')}</h3><p className="mt-1 text-xs font-semibold text-ink-400">{formatDateTime(receipt.createdAt)} · {receipt.pharmacyName}</p></div>
        <span className={`inline-flex shrink-0 items-center gap-1 rounded-lg px-2.5 py-1.5 text-[11px] font-extrabold ${status.classes}`}><StatusIcon size={14} />{status.label}</span>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 rounded-xl bg-paper-hover p-3 text-sm">
        <div><div className="text-xs font-bold text-ink-400">{t('Сумма')}</div><div className="mt-1 font-extrabold text-ink-900">{formatKzt(receipt.amount)}</div></div>
        <div><div className="text-xs font-bold text-ink-400">{t('Бонус')}</div><div className="mt-1 font-extrabold text-brand-green-700">{formatKzt(receipt.bonusCredited || receipt.bonus)}</div></div>
      </div>
      {receipt.rejectedReason && <p className="mt-3 rounded-xl bg-surface-danger px-3 py-2 text-xs font-semibold text-accent-danger">{receipt.rejectedReason}</p>}
    </article>
  )
}

function ProfileView({
  pharmacist,
  overview,
  onLogout,
}: {
  pharmacist: Pharmacist | null
  overview: Overview | null
  onLogout: () => void | Promise<void>
}) {
  const t = useLearnerT()
  return (
    <section className="grid gap-5 lg:grid-cols-[360px_1fr]">
      <div className="rounded-3xl bg-white p-6 text-center shadow-card">
        <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-3xl bg-brand-green-100 text-3xl font-extrabold text-brand-green-700">{(pharmacist?.name || 'Ф').slice(0, 1)}</div>
        <h2 className="mt-4 text-xl font-extrabold text-ink-900">{pharmacist?.name || t('Фармацевт')}</h2>
        <p className="mt-1 text-sm font-semibold text-ink-400">{pharmacist?.tier || 'Silver'} · ePharm</p>
        <div className="mt-5 rounded-2xl bg-brand-green-700 p-5 text-left text-white">
          <div className="flex items-center gap-2 text-xs font-bold text-white/70"><CircleDollarSign size={17} /> {t('Доступный баланс')}</div>
          <div className="mt-2 text-3xl font-extrabold">{formatKzt(pharmacist?.balance)}</div>
          <div className="mt-2 text-xs font-semibold text-white/70">{t('За 30 дней: +{value}', { value: formatKzt(pharmacist?.earned30d) })}</div>
        </div>
      </div>
      <div className="space-y-4">
        <div className="rounded-2xl bg-white p-5 shadow-card">
          <h2 className="flex items-center gap-2 text-lg font-extrabold text-ink-900"><BadgeCheck size={21} /> {t('Данные фармацевта')}</h2>
          <div className="mt-5 divide-y divide-ink-100">
            <ProfileRow icon={Phone} label={t('Телефон')} value={pharmacist?.phone || t('Не указан')} />
            <ProfileRow icon={Building2} label={t('Аптека')} value={pharmacist?.pharmacyName || t('Не назначена')} />
            <ProfileRow icon={MapPin} label={t('Город')} value={pharmacist?.city || t('Не указан')} />
            <ProfileRow icon={GraduationCap} label={t('Обучение')} value={t('{done} из {total} завершено', { done: pharmacist?.coursesDone ?? overview?.completed ?? 0, total: pharmacist?.coursesTotal ?? overview?.total ?? 0 })} />
          </div>
        </div>
        <div className="rounded-2xl bg-white p-5 shadow-card">
          <h2 className="font-extrabold text-ink-900">{t('Безопасность')}</h2>
          <p className="mt-2 text-sm leading-6 text-ink-500">{t('Профиль синхронизируется с ePharm. Для изменения ФИО, телефона или аптеки обратитесь к администратору.')}</p>
          <button className="btn btn-outline btn-md mt-4 w-full sm:w-auto" onClick={() => void onLogout()}><LogOut size={17} /> {t('Выйти из профиля')}</button>
        </div>
      </div>
    </section>
  )
}

function ProfileRow({ icon: Icon, label, value }: { icon: typeof Phone; label: string; value: string }) {
  return <div className="flex items-center gap-3 py-4 first:pt-0 last:pb-0"><span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-paper-hover text-brand-green-700"><Icon size={19} /></span><span className="min-w-0 flex-1"><span className="block text-xs font-bold text-ink-400">{label}</span><span className="mt-0.5 block truncate text-sm font-extrabold text-ink-800">{value}</span></span></div>
}

function AttendanceDialog({
  busy,
  onClose,
  onSubmit,
}: {
  busy: boolean
  onClose: () => void
  onSubmit: (value: string) => Promise<boolean>
}) {
  const t = useLearnerT()
  const [value, setValue] = useState('')
  const [scanning, setScanning] = useState(false)
  const [cameraError, setCameraError] = useState('')
  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const frameRef = useRef<number | null>(null)
  const valid = extractQrToken(value).length > 0 || extractCheckInCode(value).length === 6

  const stopCamera = useCallback(() => {
    if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
    frameRef.current = null
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    if (videoRef.current) videoRef.current.srcObject = null
    setScanning(false)
  }, [])

  useEffect(() => stopCamera, [stopCamera])

  const closeDialog = useCallback(() => {
    stopCamera()
    onClose()
  }, [onClose, stopCamera])

  async function startCamera() {
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError(t('Браузер не поддерживает доступ к камере. Введите 6-значный код вручную.'))
      return
    }

    setCameraError('')
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: 'environment' },
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
      })
      streamRef.current = stream
      setScanning(true)

      const video = videoRef.current
      if (!video) throw new Error('Camera preview is unavailable')
      video.srcObject = stream
      await video.play()

      const canvas = document.createElement('canvas')
      const context = canvas.getContext('2d', { willReadFrequently: true })
      if (!context) throw new Error('QR scanner is unavailable')

      let lastScanAt = 0
      const scanFrame = async (timestamp: number) => {
        if (!streamRef.current || !videoRef.current) return

        if (timestamp - lastScanAt >= 150 && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
          lastScanAt = timestamp
          const width = video.videoWidth
          const height = video.videoHeight
          if (width > 0 && height > 0) {
            canvas.width = width
            canvas.height = height
            context.drawImage(video, 0, 0, width, height)
            const image = context.getImageData(0, 0, width, height)
            const decoded = jsQR(image.data, width, height, { inversionAttempts: 'attemptBoth' })
            if (decoded?.data) {
              const decodedValue = decoded.data.trim()
              setValue(decodedValue)
              stopCamera()
              const accepted = await onSubmit(decodedValue)
              if (!accepted) setCameraError(t('QR-код распознан, но мероприятие не найдено или посещение уже отмечено.'))
              return
            }
          }
        }

        frameRef.current = requestAnimationFrame(scanFrame)
      }
      frameRef.current = requestAnimationFrame(scanFrame)
    } catch (error) {
      stopCamera()
      const denied = error instanceof DOMException && ['NotAllowedError', 'SecurityError'].includes(error.name)
      setCameraError(denied
        ? t('Доступ к камере запрещён. Разрешите камеру для сайта в настройках браузера или введите код вручную.')
        : t('Не удалось открыть камеру. Проверьте разрешение браузера или введите код вручную.'))
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-900/50 p-4" role="presentation" onMouseDown={(event) => {
      if (event.currentTarget === event.target && !busy) closeDialog()
    }}>
      <section className="max-h-[calc(100dvh-2rem)] w-full max-w-lg overflow-y-auto rounded-3xl bg-white p-6 shadow-fab sm:p-8" role="dialog" aria-modal="true" aria-labelledby="attendance-title">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-green-100 text-brand-green-700"><QrCode size={28} /></div>
        <h2 id="attendance-title" className="mt-5 text-2xl font-extrabold text-ink-900">{t('Отметить посещение')}</h2>
        <p className="mt-2 text-sm leading-6 text-ink-500">{t('Наведите камеру телефона на QR-код организатора или введите 6-значный код мероприятия. Посещение сразу синхронизируется с приложением.')}</p>

        <button
          type="button"
          className="btn btn-outline btn-md mt-5 w-full"
          disabled={busy}
          onClick={scanning ? stopCamera : () => void startCamera()}
        >
          {scanning ? <CameraOff size={18} /> : <Camera size={18} />}
          {scanning ? t('Закрыть камеру') : t('Сканировать QR камерой')}
        </button>

        <div className={scanning ? 'relative mt-4 aspect-[3/4] overflow-hidden rounded-2xl bg-black sm:aspect-video' : 'hidden'}>
          <video ref={videoRef} className="h-full w-full object-cover" autoPlay muted playsInline aria-label={t('Камера для сканирования QR-кода')} />
          <div className="pointer-events-none absolute inset-[12%] rounded-2xl border-2 border-white/90 shadow-[0_0_0_999px_rgba(0,0,0,0.25)]" />
          <div className="pointer-events-none absolute inset-x-4 bottom-4 rounded-xl bg-black/65 px-3 py-2 text-center text-xs font-bold text-white">{t('Поместите QR-код в рамку')}</div>
        </div>

        {cameraError && (
          <div className="mt-4 flex gap-2 rounded-2xl bg-red-50 px-4 py-3 text-sm font-semibold leading-5 text-red-700" role="alert">
            <AlertCircle className="mt-0.5 shrink-0" size={17} />
            <span>{cameraError}</span>
          </div>
        )}

        <form className="mt-5 space-y-4" onSubmit={async (event) => {
          event.preventDefault()
          if (valid) await onSubmit(value)
        }}>
          <label className="block text-sm font-bold text-ink-700">
            {t('Код мероприятия или ссылка из QR')}
            <textarea
              className="inp mt-2 min-h-24 w-full resize-y py-3"
              value={value}
              onChange={(event) => {
                setValue(event.target.value)
                setCameraError('')
              }}
              placeholder={t('Например, 814523')}
            />
          </label>
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" className="btn btn-ghost btn-md" disabled={busy} onClick={closeDialog}>{t('Отмена')}</button>
            <button className="btn btn-primary btn-md" disabled={busy || !valid}>
              <Check size={17} /> {busy ? t('Проверяем…') : t('Подтвердить посещение')}
            </button>
          </div>
        </form>
      </section>
    </div>
  )
}

function OverviewView({
  overview,
  pharmacist,
  promotions,
  busy,
  onOpen,
  onRefresh,
  onOpenNotification,
  onCheckIn,
  trainingOnly = false,
}: {
  overview: Overview | null
  pharmacist: Pharmacist | null
  promotions: Promotion[]
  busy: boolean
  onOpen: (assignment: Assignment) => void
  onRefresh: () => void | Promise<void>
  onOpenNotification: (notification: TrainingNotification) => void | Promise<void>
  onCheckIn: () => void
  trainingOnly?: boolean
}) {
  const t = useLearnerT()
  const [filter, setFilter] = useState<'active' | 'completed' | 'all'>('active')
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim().toLowerCase()
  const assignments = (overview?.assignments ?? [])
    .filter((assignment) => {
      if (filter === 'active') return assignment.status !== 'completed' && assignment.status !== 'cancelled'
      if (filter === 'completed') return assignment.status === 'completed'
      return true
    })
    .filter((assignment) => !normalizedQuery || [
      assignment.programName,
      assignment.programShortDescription ?? '',
      assignment.pharmacyName,
      assignment.city,
    ].some((value) => value.toLowerCase().includes(normalizedQuery)))
    .sort((left, right) => {
      const priority = { critical: 4, high: 3, normal: 2, low: 1 }
      const priorityDiff = (priority[right.priority as keyof typeof priority] ?? 2) -
        (priority[left.priority as keyof typeof priority] ?? 2)
      if (priorityDiff) return priorityDiff
      return new Date(left.dueAt ?? '9999-12-31').getTime() - new Date(right.dueAt ?? '9999-12-31').getTime()
    })
  const notifications = overview?.notifications ?? []
  const events = overview?.upcomingEvents ?? []
  const certificates = overview?.certificates ?? []
  return (
    <>
      {!trainingOnly && <section className="mb-5 overflow-hidden rounded-2xl bg-white shadow-card">
        <div className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div className="flex min-w-0 items-center gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-brand-green-100 text-lg font-extrabold text-brand-green-700">
              {(pharmacist?.name || 'Ф').slice(0, 1)}
            </div>
            <div className="min-w-0">
              <div className="truncate font-extrabold text-ink-900">{pharmacist?.name || t('Фармацевт')}</div>
              <div className="mt-1 text-xs font-semibold text-ink-400">
                {pharmacist?.tier || 'Silver'} · {overview?.defaultFormat ? t('формат {format}', { format: trainingFormatText(overview.defaultFormat) }) : t('формат не задан')}
              </div>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:min-w-[300px]">
            <div className="rounded-xl bg-paper-hover px-4 py-3">
              <div className="flex items-center gap-2 text-xs font-bold text-ink-400"><WalletCards size={15} /> {t('Баланс')}</div>
              <div className="mt-1 font-extrabold text-ink-900">{formatKzt(pharmacist?.balance)}</div>
            </div>
            <div className="rounded-xl bg-paper-hover px-4 py-3">
              <div className="text-xs font-bold text-ink-400">{t('Курсы')}</div>
              <div className="mt-1 font-extrabold text-ink-900">{pharmacist?.coursesDone ?? overview?.completed ?? 0} / {pharmacist?.coursesTotal ?? overview?.total ?? 0}</div>
            </div>
          </div>
        </div>
      </section>}

      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat value={overview?.total ?? 0} label={t('Всего')} />
        <Stat value={overview?.inProgress ?? 0} label={t('В процессе')} />
        <Stat value={overview?.completed ?? 0} label={t('Завершено')} />
        <Stat value={overview?.overdue ?? 0} label={t('Просрочено')} danger />
      </div>

      <div className="mb-6 grid gap-3 sm:grid-cols-2">
        <button className="btn btn-outline btn-md w-full" disabled={busy} onClick={onCheckIn}>
          <QrCode size={18} /> {t('Отметить посещение по QR')}
        </button>
        <button className="btn btn-outline btn-md w-full" disabled={busy} onClick={onRefresh}>
          <RefreshCw className={busy ? 'animate-spin' : ''} size={18} /> {t('Обновить данные')}
        </button>
      </div>

      {notifications.length > 0 && (
        <section className="mb-7">
          <h2 className="mb-3 flex items-center gap-2 text-lg font-extrabold text-ink-900"><Bell size={20} /> {t('Уведомления')}</h2>
          <div className="space-y-2">
            {notifications.slice(0, 5).map((notification) => (
              <button
                key={notification.id}
                className={`flex w-full items-start gap-3 rounded-2xl border p-4 text-left transition hover:bg-paper-hover ${notification.read ? 'border-ink-100 bg-white' : 'border-brand-green-200 bg-brand-green-50'}`}
                onClick={() => void onOpenNotification(notification)}
              >
                <span className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${notification.read ? 'bg-ink-200' : 'bg-brand-green-600'}`} />
                <span className="min-w-0 flex-1">
                  <span className="block font-extrabold text-ink-900">{notification.title}</span>
                  <span className="mt-1 block text-sm leading-5 text-ink-500">{notification.message}</span>
                </span>
                <span className="shrink-0 text-[11px] font-semibold text-ink-400">{formatDate(notification.scheduledAt)}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      {events.length > 0 && (
        <section className="mb-7">
          <h2 className="mb-3 flex items-center gap-2 text-lg font-extrabold text-ink-900"><CalendarDays size={20} /> {t('Ближайшие события')}</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {events.map((event) => <EventCard key={event.id} event={event} />)}
          </div>
        </section>
      )}

      {!trainingOnly && <PromotionShowcase promotions={promotions} />}

      <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-lg font-extrabold text-ink-900">{t('Мои программы')}</h2>
        <div className="inline-flex rounded-xl bg-white p-1 shadow-card">
          {([['active', t('Активные')], ['completed', t('Завершённые')], ['all', t('Все')]] as const).map(([value, label]) => (
            <button
              key={value}
              className={`rounded-lg px-3 py-2 text-xs font-extrabold transition ${filter === value ? 'bg-brand-green-600 text-white' : 'text-ink-500 hover:bg-paper-hover'}`}
              onClick={() => setFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      <label className="relative mb-4 block">
        <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink-400" size={18} />
        <input
          className="inp h-12 w-full pl-11"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t('Найти программу')}
          aria-label={t('Найти программу')}
        />
      </label>
      {assignments.length === 0 ? (
        <div className="rounded-2xl bg-white p-8 text-center shadow-card">
          <BookOpen className="mx-auto text-ink-300" size={36} />
          <p className="mt-3 font-bold text-ink-700">{normalizedQuery ? t('Программы не найдены') : t('В этом разделе программ пока нет')}</p>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {assignments.map((assignment) => (
            <button
              key={assignment.id}
              className="group rounded-2xl bg-white p-5 text-left shadow-card transition hover:-translate-y-0.5 hover:shadow-fab disabled:opacity-60"
              disabled={busy}
              onClick={() => onOpen(assignment)}
            >
              <div className="flex items-start justify-between gap-3">
                <span className="chip chip-green">{statusText(assignment.status)}</span>
                <span className="text-sm font-extrabold text-brand-green-700">{assignment.progressPct}%</span>
              </div>
              <h3 className="mt-4 text-lg font-extrabold text-ink-900 group-hover:text-brand-green-700">
                {assignment.programName}
              </h3>
              {assignment.programShortDescription && (
                <p className="mt-2 line-clamp-2 text-sm leading-6 text-ink-500">{assignment.programShortDescription}</p>
              )}
              <div className="mt-5 h-2 overflow-hidden rounded-full bg-paper-input">
                <div className="h-full rounded-full bg-brand-green-600" style={{ width: `${assignment.progressPct}%` }} />
              </div>
              <div className="mt-4 flex items-center gap-2 text-xs font-semibold text-ink-400">
                <Clock3 size={15} /> {t('Срок: {date}', { date: formatDate(assignment.dueAt) })} · {trainingFormatText(assignment.format)}
              </div>
            </button>
          ))}
        </div>
      )}

      {certificates.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 flex items-center gap-2 text-lg font-extrabold text-ink-900"><Award size={20} /> {t('Сертификаты')}</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {certificates.map((certificate) => (
              <CertificateCard key={certificate.id} certificate={certificate} />
            ))}
          </div>
        </section>
      )}
    </>
  )
}

function PromotionShowcase({ promotions }: { promotions: Promotion[] }) {
  const t = useLearnerT()
  const [selectedPromotion, setSelectedPromotion] = useState<Promotion | null>(null)
  if (!promotions.length) return null

  return (
    <section className="mb-8">
      <div className="mb-4 flex items-end justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-extrabold text-ink-900">
            <PackageSearch size={21} /> {t('Препараты и бонусы')}
          </h2>
          <p className="mt-1 text-sm text-ink-500">{t('Демонстрационная витрина из мобильного приложения')}</p>
        </div>
        <span className="chip chip-green shrink-0">{t('{count} предложений', { count: promotions.length })}</span>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {promotions.map((promotion) => (
          <PromotionCard
            key={promotion.id}
            promotion={promotion}
            onOpen={() => setSelectedPromotion(promotion)}
          />
        ))}
      </div>
      {selectedPromotion && (
        <PromotionDialog promotion={selectedPromotion} onClose={() => setSelectedPromotion(null)} />
      )}
    </section>
  )
}

function PromotionCard({ promotion, onOpen }: { promotion: Promotion; onOpen: () => void }) {
  const t = useLearnerT()
  const maxBonus = Math.max(0, ...promotion.tiers.map((tier) => tier.bonus))
  const prices = promotion.tiers.map((tier) => tier.price).filter((price) => price > 0)
  const minPrice = prices.length ? Math.min(...prices) : null
  const image = proxyMedia(promotion.imageUrl)

  return (
    <button
      className="group overflow-hidden rounded-2xl bg-white text-left shadow-card transition hover:-translate-y-0.5 hover:shadow-fab"
      onClick={onOpen}
      aria-label={t('Открыть препарат {name}', { name: promotion.name })}
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-brand-green-50">
        {image ? (
          <img className="h-full w-full object-contain p-3 transition duration-300 group-hover:scale-105" src={image} alt="" loading="lazy" />
        ) : (
          <div className="flex h-full items-center justify-center text-4xl font-extrabold text-brand-green-600">
            {promotion.name.slice(0, 1).toUpperCase()}
          </div>
        )}
        {promotion.rxOtc && (
          <span className={`absolute left-2 top-2 rounded-lg px-2 py-1 text-[10px] font-extrabold ${promotion.rxOtc.toLowerCase() === 'rx' ? 'bg-surface-danger text-accent-danger' : 'bg-white/90 text-brand-green-700'}`}>
            {promotion.rxOtc.toUpperCase()}
          </span>
        )}
        {maxBonus > 0 && (
          <span className="absolute bottom-2 right-2 rounded-lg bg-brand-green-700 px-2 py-1 text-[10px] font-extrabold text-white">
            +{formatKzt(maxBonus)}
          </span>
        )}
      </div>
      <div className="p-3.5">
        <h3 className="line-clamp-2 min-h-10 text-sm font-extrabold leading-5 text-ink-900 group-hover:text-brand-green-700">{promotion.name}</h3>
        <p className="mt-1 truncate text-xs font-semibold text-ink-400">{promotion.brand || promotion.category || 'ePharm'}</p>
        <div className="mt-3 text-sm font-extrabold text-brand-green-700">
          {maxBonus > 0 ? t('Бонус {value}', { value: formatKzt(maxBonus) }) : minPrice ? t('от {value}', { value: formatKzt(minPrice) }) : t('Подробнее')}
        </div>
      </div>
    </button>
  )
}

function PromotionDialog({ promotion, onClose }: { promotion: Promotion; onClose: () => void }) {
  const t = useLearnerT()
  const image = proxyMedia(promotion.imageUrl)
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-900/55 p-4" role="presentation" onMouseDown={(event) => {
      if (event.currentTarget === event.target) onClose()
    }}>
      <section className="max-h-[92vh] w-full max-w-3xl overflow-y-auto rounded-3xl bg-white shadow-fab" role="dialog" aria-modal="true" aria-labelledby="promotion-title">
        <div className="sticky top-0 z-10 flex justify-end bg-white/90 p-3 backdrop-blur">
          <button className="btn btn-ghost h-10 w-10 rounded-full p-0" onClick={onClose} aria-label={t('Закрыть карточку препарата')}><X size={20} /></button>
        </div>
        <div className="grid gap-6 px-5 pb-6 sm:grid-cols-[240px_1fr] sm:px-7 sm:pb-8">
          <div className="aspect-square overflow-hidden rounded-2xl bg-brand-green-50">
            {image ? (
              <img className="h-full w-full object-contain p-5" src={image} alt={promotion.name} />
            ) : (
              <div className="flex h-full items-center justify-center text-6xl font-extrabold text-brand-green-600">{promotion.name.slice(0, 1).toUpperCase()}</div>
            )}
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap gap-2">
              {promotion.rxOtc && <span className="chip chip-green">{promotion.rxOtc.toUpperCase()}</span>}
              {promotion.category && <span className="chip chip-ink">{promotion.category}</span>}
            </div>
            <h2 id="promotion-title" className="mt-3 text-2xl font-extrabold leading-tight text-ink-900">{promotion.name}</h2>
            {promotion.brand && <p className="mt-2 font-bold text-brand-green-700">{promotion.brand}</p>}
            {promotion.mnn && <p className="mt-3 text-sm leading-6 text-ink-500"><strong className="text-ink-700">{t('МНН')}:</strong> {promotion.mnn}</p>}
            {promotion.overrideDescription && <p className="mt-3 text-sm leading-6 text-ink-600">{promotion.overrideDescription}</p>}
            <div className="mt-4 rounded-xl bg-paper-hover p-4 text-sm text-ink-500">
              <div className="font-bold text-ink-700">{promotionPeriod(promotion)}</div>
              {promotion.barcode && <div className="mt-1">{t('Штрихкод')}: <span className="font-mono text-ink-700">{promotion.barcode}</span></div>}
            </div>
          </div>
        </div>
        {promotion.tiers.length > 0 && (
          <div className="border-t border-ink-100 px-5 py-6 sm:px-7">
            <h3 className="flex items-center gap-2 font-extrabold text-ink-900"><Gift size={19} /> {t('Цены и бонусы')}</h3>
            <div className="mt-3 grid gap-3 sm:grid-cols-3">
              {promotion.tiers.map((tier, index) => (
                <div key={`${tier.minQty}-${index}`} className="rounded-2xl bg-brand-green-50 p-4">
                  <div className="text-lg font-extrabold text-brand-green-700">{formatKzt(tier.price)}</div>
                  <div className="mt-1 text-xs font-semibold text-ink-500">{t('от {count} шт.', { count: tier.minQty })}</div>
                  {tier.bonus > 0 && <div className="mt-3 rounded-lg bg-brand-green-700 px-3 py-2 text-center text-xs font-extrabold text-white">{t('Бонус {value}', { value: formatKzt(tier.bonus) })}</div>}
                </div>
              ))}
            </div>
          </div>
        )}
      </section>
    </div>
  )
}

function Stat({ value, label, danger = false }: { value: number; label: string; danger?: boolean }) {
  return (
    <div className="rounded-2xl bg-white p-4 shadow-card">
      <div className={`text-2xl font-extrabold ${danger && value ? 'text-accent-danger' : 'text-ink-900'}`}>{value}</div>
      <div className="mt-1 text-xs font-semibold text-ink-400">{label}</div>
    </div>
  )
}

function AssignmentView({
  assignment,
  busy,
  availableEvents,
  eventsLoading,
  onBack,
  onStart,
  onSelectEvent,
  onCheckIn,
  onOpenLesson,
  onComplete,
}: {
  assignment: Assignment
  busy: boolean
  availableEvents: TrainingEvent[]
  eventsLoading: boolean
  onBack: () => void
  onStart: () => void | Promise<void>
  onSelectEvent: (event: TrainingEvent) => void | Promise<void>
  onCheckIn: () => void
  onOpenLesson: (lessonId: string) => void
  onComplete: (stage: Stage) => Promise<void>
}) {
  const t = useLearnerT()
  const completed = assignment.status === 'completed'
  const cancelled = assignment.status === 'cancelled'
  const startsLater = assignment.status === 'scheduled' || assignment.status === 'planned'
  const canStart = !completed && !cancelled && !assignment.startedAt && !startsLater
  return (
    <div>
      <button className="btn btn-ghost mb-4 -ml-2" onClick={onBack}>
        <ArrowLeft size={18} /> {t('Все программы')}
      </button>
      <section className="rounded-3xl bg-white p-5 shadow-card sm:p-7">
        <div className="flex flex-wrap items-center gap-2">
          <span className="chip chip-green">{statusText(assignment.status)}</span>
          <span className="chip chip-ink">{trainingFormatText(assignment.format)}</span>
          {assignment.required && <span className="chip chip-blue">{t('Обязательно')}</span>}
          {assignment.priority === 'high' && <span className="chip chip-red">{t('Высокий приоритет')}</span>}
          {assignment.priority === 'critical' && <span className="chip chip-red">{t('Критический приоритет')}</span>}
        </div>
        {assignment.programShortDescription && (
          <p className="mt-4 max-w-3xl text-sm leading-6 text-ink-600">{assignment.programShortDescription}</p>
        )}
        <div className="mt-5 flex items-center gap-3">
          <div className="h-2 flex-1 overflow-hidden rounded-full bg-paper-input">
            <div className="h-full rounded-full bg-brand-green-600" style={{ width: `${assignment.progressPct}%` }} />
          </div>
          <strong className="text-sm text-brand-green-700">{assignment.progressPct}%</strong>
        </div>
        <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-xs font-semibold text-ink-400">
          <span className="flex items-center gap-1.5"><Clock3 size={14} /> {t('Срок: {date}', { date: formatDate(assignment.dueAt) })}</span>
          <span className="flex items-center gap-1.5"><MapPin size={14} /> {[assignment.pharmacyName, assignment.city].filter(Boolean).join(' · ')}</span>
          {assignment.score != null && <span className="flex items-center gap-1.5"><Award size={14} /> {t('Результат: {score}%', { score: assignment.score })}</span>}
        </div>
        {canStart && (
          <button className="btn btn-primary btn-md mt-5 w-full sm:w-auto" disabled={busy} onClick={onStart}>
            <PlayCircle size={18} /> {busy ? t('Запускаем…') : t('Начать программу')}
          </button>
        )}
        {startsLater && <div className="mt-4 rounded-xl bg-paper-hover px-4 py-3 text-sm font-semibold text-ink-500">{t('Программа откроется {date}.', { date: formatDateTime(assignment.startsAt) })}</div>}
      </section>

      {assignment.event && (
        <section className="mt-5">
          <EventCard event={assignment.event} />
          {!completed && (
            <button className="btn btn-outline btn-md mt-3 w-full" disabled={busy} onClick={onCheckIn}>
              <QrCode size={18} /> {t('Отметить посещение по QR')}
            </button>
          )}
        </section>
      )}

      {!assignment.event && assignment.format !== 'online' && (
        <section className="mt-5 rounded-2xl bg-white p-5 shadow-card sm:p-6">
          <h2 className="text-lg font-extrabold text-ink-900">{t('Выберите очное мероприятие')}</h2>
          <p className="mt-1 text-sm leading-6 text-ink-500">{t('Выбор синхронизируется с мобильным приложением и закрепляется за программой.')}</p>
          {eventsLoading ? (
            <div className="mt-5 flex items-center gap-2 text-sm font-semibold text-ink-500"><RefreshCw className="animate-spin" size={18} /> {t('Загружаем расписание…')}</div>
          ) : availableEvents.length ? (
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              {availableEvents.map((event) => (
                <div key={event.id} className="rounded-2xl border border-ink-100 p-4">
                  <div className="font-extrabold text-ink-900">{event.title}</div>
                  <div className="mt-2 text-sm font-semibold text-ink-500">{formatDateTime(event.startsAt)}</div>
                  <div className="mt-1 text-sm text-ink-500">{[event.city, event.address].filter(Boolean).join(' · ')}</div>
                  <button className="btn btn-outline btn-md mt-4 w-full" disabled={busy || event.occupied >= event.capacity} onClick={() => void onSelectEvent(event)}>
                    {event.occupied >= event.capacity ? t('Мест нет') : t('Выбрать мероприятие')}
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <div className="mt-4 rounded-xl bg-paper-hover p-4 text-sm text-ink-500">{t('Доступных мероприятий пока нет.')}</div>
          )}
        </section>
      )}

      <div className="mt-6 space-y-4">
        {assignment.stages.map((stage) => (
          <StageCard
            key={stage.id}
            stage={stage}
            busy={busy}
            onOpenLesson={onOpenLesson}
            onComplete={() => onComplete(stage)}
          />
        ))}
      </div>

      {completed && (
        <section className="mt-6 rounded-3xl bg-brand-green-700 p-6 text-white shadow-card sm:p-8">
          <div className="flex items-start gap-4">
            <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-white/15"><Check size={27} /></span>
            <div className="min-w-0 flex-1">
              <h2 className="text-xl font-extrabold">{t('Программа завершена')}</h2>
              <p className="mt-2 text-sm leading-6 text-white/75">{t('Результат сохранён и синхронизирован с мобильным приложением.')}</p>
              <div className="mt-4 flex flex-wrap gap-2">
                {assignment.score != null && <span className="rounded-full bg-white/15 px-3 py-1.5 text-xs font-extrabold">{t('Результат {score}%', { score: assignment.score })}</span>}
                {assignment.reward && <span className="rounded-full bg-white/15 px-3 py-1.5 text-xs font-extrabold">{t('Начислено {value}', { value: formatKzt(assignment.reward.amount) })}</span>}
              </div>
              {assignment.certificate?.pdfUrl && (
                <a className="btn btn-md mt-5 bg-white text-brand-green-700 hover:bg-brand-green-50" href={assignment.certificate.pdfUrl} target="_blank" rel="noreferrer">
                  <Award size={18} /> {t('Открыть сертификат')}
                </a>
              )}
            </div>
          </div>
        </section>
      )}
    </div>
  )
}

function StageCard({
  stage,
  busy,
  onOpenLesson,
  onComplete,
}: {
  stage: Stage
  busy: boolean
  onOpenLesson: (lessonId: string) => void
  onComplete: () => void | Promise<void>
}) {
  const t = useLearnerT()
  const lessons = useMemo(
    () => [...(stage.course?.lessons ?? [])].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)),
    [stage.course?.lessons],
  )
  const completedLessonCount = lessons.filter(lessonCompleted).length
  const completed = stage.status === 'completed' || stage.progressPct >= 100
  const canCompleteDirectly = !completed && lessons.length === 0 && (
    stage.type === 'material' || (stage.type === 'online_course' && !stage.course && !!stage.contentUrl)
  )

  return (
    <section className="overflow-hidden rounded-2xl bg-white shadow-card">
      <div className="border-b border-ink-100 p-5 sm:p-6">
        <div className="flex items-start justify-between gap-3">
          <div>
            <span className="text-xs font-extrabold uppercase tracking-wider text-brand-green-700">{t('Этап обучения')}</span>
            <h2 className="mt-1 text-xl font-extrabold text-ink-900">{stage.course?.title || stage.title}</h2>
            {stage.course?.description && <p className="mt-2 text-sm leading-6 text-ink-500">{stage.course.description}</p>}
          </div>
          {completed && <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-green-100 text-brand-green-700"><Check size={20} /></span>}
        </div>
        {lessons.length > 0 && (
          <div className="mt-3 text-xs font-semibold text-ink-400">
            {t('{count} урока · {minutes} мин', { count: lessons.length, minutes: stage.course?.durationMin ?? stage.course?.totalDurationMin ?? lessons.reduce((sum, lesson) => sum + (lesson.durationMin ?? 0), 0) })}
          </div>
        )}
      </div>

      <div className="divide-y divide-ink-100">
        {lessons.map((lesson, index) => (
          <LessonRow
            key={lesson.id}
            lesson={lesson}
            number={index + 1}
            read={completed || lessonCompleted(lesson)}
            disabled={stage.status === 'locked' || (!completed && lessons
              .slice(0, index)
              .some((previous) => (previous.required ?? true) && !lessonCompleted(previous)))}
            onOpen={() => onOpenLesson(lesson.id)}
          />
        ))}
        {lessons.length === 0 && stage.contentUrl && (
          <a className="flex items-center gap-2 p-5 font-bold text-brand-green-700 hover:bg-paper-hover" href={stage.contentUrl} target="_blank" rel="noreferrer">
            <PlayCircle size={20} /> {t('Открыть материал')}
          </a>
        )}
      </div>

      {!completed && lessons.length > 0 && (
        <div className="border-t border-ink-100 bg-paper-hover p-5 text-xs font-semibold text-ink-500">
          {t('Завершайте обязательные уроки по порядку. Прогресс сохраняется на сервере автоматически: {done} из {total}.', { done: completedLessonCount, total: lessons.length })}
        </div>
      )}

      {canCompleteDirectly && (
        <div className="border-t border-ink-100 bg-paper-hover p-5 sm:flex sm:items-center sm:justify-between sm:gap-4">
          <p className="mb-3 text-xs font-semibold text-ink-500 sm:mb-0">
            {t('После подтверждения прогресс сохранится в ePharm.')}
          </p>
          <button className="btn btn-primary btn-md w-full sm:w-auto" disabled={busy} onClick={onComplete}>
            <Check size={17} /> {t('Завершить этап')}
          </button>
        </div>
      )}
    </section>
  )
}

function LessonPage({
  assignment,
  lessonId,
  busy,
  onBack,
  onOpenLesson,
  onSaveProgress,
  onSubmitQuiz,
}: {
  assignment: Assignment
  lessonId: string
  busy: boolean
  onBack: () => void
  onOpenLesson: (lessonId: string) => void
  onSaveProgress: (
    stage: Stage,
    lesson: Lesson,
    progressPct: number,
    positionSeconds: number,
    refreshOverview?: boolean,
  ) => Promise<Assignment | null>
  onSubmitQuiz: (
    stage: Stage,
    lesson: Lesson,
    answers: Record<string, number>,
  ) => Promise<QuizSubmissionResult | null>
}) {
  const t = useLearnerT()
  const stage = assignment.stages.find((candidate) =>
    candidate.course?.lessons.some((lesson) => lesson.id === lessonId),
  )
  const lessons = [...(stage?.course?.lessons ?? [])]
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  const index = lessons.findIndex((lesson) => lesson.id === lessonId)
  const lesson = lessons[index]
  const [watchedProgress, setWatchedProgress] = useState<Record<string, number>>({})
  const [saving, setSaving] = useState(false)
  const currentPositions = useRef<Record<string, number>>({})
  const lastSyncedProgress = useRef<Record<string, number>>({})
  const progressInFlight = useRef<Set<string>>(new Set())

  if (!stage || !lesson) {
    return (
      <div className="rounded-2xl bg-white p-8 text-center shadow-card">
        <h2 className="text-lg font-extrabold text-ink-900">{t('Урок не найден')}</h2>
        <button className="btn btn-outline btn-md mt-4" onClick={onBack}>{t('Вернуться к курсу')}</button>
      </div>
    )
  }

  const currentStage = stage
  const previous = lessons[index - 1]
  const next = lessons[index + 1]
  const attachments = lesson.attachments ?? []
  const read = stage.status === 'completed' || lessonCompleted(lesson)
  const videoLesson = lesson.kind === 'video' || !!lesson.videoUrl
  const quizLesson = lesson.kind === 'quiz' || lesson.kind === 'test'
  const threshold = lessonThreshold(lesson)
  const effectiveProgress = Math.max(watchedProgress[lesson.id] ?? 0, lesson.progressPct ?? 0)
  const blockedByPrevious = lessons
    .slice(0, index)
    .find((previousLesson) => (previousLesson.required ?? true) && !lessonCompleted(previousLesson))

  if (stage.status === 'locked' || (stage.status !== 'completed' && blockedByPrevious)) {
    return (
      <div className="rounded-2xl bg-white p-8 text-center shadow-card">
        <Lock className="mx-auto text-ink-300" size={36} />
        <h2 className="mt-3 text-lg font-extrabold text-ink-900">{t('Урок пока недоступен')}</h2>
        <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">
          {blockedByPrevious
            ? t('Сначала завершите обязательный урок «{title}».', { title: blockedByPrevious.title })
            : t('Сначала завершите предыдущий обязательный этап обучения.')}
        </p>
        <button className="btn btn-outline btn-md mt-5" onClick={onBack}>{t('Вернуться к курсу')}</button>
      </div>
    )
  }

  async function persistVideoProgress(progressPct: number, positionSeconds: number) {
    if (progressInFlight.current.has(lesson.id) || read) return
    progressInFlight.current.add(lesson.id)
    try {
      const saved = await onSaveProgress(currentStage, lesson, progressPct, positionSeconds)
      if (saved) {
        lastSyncedProgress.current[lesson.id] = Math.max(
          lastSyncedProgress.current[lesson.id] ?? lesson.progressPct ?? 0,
          progressPct,
        )
      }
    } finally {
      progressInFlight.current.delete(lesson.id)
    }
  }

  function updateVideoProgress(element: HTMLVideoElement) {
    if (!Number.isFinite(element.duration) || element.duration <= 0) return
    const progressPct = Math.min(100, Math.floor((element.currentTime / element.duration) * 100))
    const positionSeconds = Math.floor(element.currentTime)
    currentPositions.current[lesson.id] = positionSeconds
    setWatchedProgress((current) => ({
      ...current,
      [lesson.id]: Math.max(current[lesson.id] ?? 0, progressPct),
    }))
    const lastSynced = Math.max(
      lastSyncedProgress.current[lesson.id] ?? 0,
      lesson.progressPct ?? 0,
    )
    if (progressPct >= lastSynced + 5 || progressPct >= threshold) {
      void persistVideoProgress(progressPct, positionSeconds)
    }
  }

  async function finishLesson() {
    if (quizLesson && !read) return
    if (!read) {
      if (videoLesson && effectiveProgress < threshold) return
      setSaving(true)
      const saved = await onSaveProgress(
        currentStage,
        lesson,
        videoLesson ? effectiveProgress : 100,
        Math.max(
          currentPositions.current[lesson.id] ?? 0,
          lesson.lastPositionSeconds ?? 0,
        ),
        !next,
      )
      setSaving(false)
      if (!saved) return
    }
    if (next) {
      onOpenLesson(next.id)
      return
    }
    onBack()
  }

  return (
    <article>
      <button className="btn btn-ghost mb-4 -ml-2" onClick={onBack}>
        <ArrowLeft size={18} /> {t('К содержанию курса')}
      </button>
      <div className="overflow-hidden rounded-3xl bg-white shadow-card">
        <header className="border-b border-ink-100 p-5 sm:p-8">
          <div className="text-xs font-extrabold uppercase tracking-[0.16em] text-brand-green-700">
            {t('Урок {current} из {total}', { current: index + 1, total: lessons.length })}
          </div>
          <h2 className="mt-2 text-2xl font-extrabold text-ink-900 sm:text-3xl">{lesson.title}</h2>
          {lesson.description && <p className="mt-3 max-w-3xl text-sm leading-6 text-ink-500">{lesson.description}</p>}
          <div className="mt-4 flex flex-wrap gap-2">
            <span className="chip chip-ink"><Clock3 size={14} /> {t('{minutes} мин', { minutes: lesson.durationMin ?? 0 })}</span>
            {lesson.videoUrl && <span className="chip chip-green"><PlayCircle size={14} /> {t('Видеоурок')}</span>}
            {quizLesson && <span className="chip chip-blue"><CheckCircle2 size={14} /> {t('Тест')} · {t('{count} вопросов', { count: lesson.quizQuestions?.length ?? 0 })}</span>}
            {attachments.length > 0 && <span className="chip chip-blue"><FileText size={14} /> {t('{count} материалов', { count: attachments.length })}</span>}
          </div>
        </header>

        <div className="space-y-7 p-5 sm:p-8">
          {lesson.videoUrl && (
            <section>
              <h3 className="mb-3 text-base font-extrabold text-ink-900">{t('Видео урока')}</h3>
              <video
                controls
                preload="metadata"
                className="aspect-video w-full rounded-2xl bg-ink-900"
                src={lesson.videoUrl}
                onLoadedMetadata={(event) => {
                  const resumeAt = lesson.lastPositionSeconds ?? 0
                  if (resumeAt > 0 && resumeAt < event.currentTarget.duration) {
                    event.currentTarget.currentTime = resumeAt
                  }
                }}
                onPlay={(event) => {
                  void persistVideoProgress(
                    Math.max(
                      lastSyncedProgress.current[lesson.id] ?? 0,
                      lesson.progressPct ?? 0,
                    ),
                    Math.floor(event.currentTarget.currentTime),
                  )
                }}
                onTimeUpdate={(event) => updateVideoProgress(event.currentTarget)}
                onPause={(event) => updateVideoProgress(event.currentTarget)}
              />
              <div className="mt-3 flex items-center justify-between gap-3 text-xs font-semibold text-ink-500">
                <span>{t('Просмотрено {progress}%', { progress: effectiveProgress })}</span>
                <span>{t('Для завершения нужно {threshold}%', { threshold })}</span>
              </div>
            </section>
          )}

          {lesson.externalUrl && (
            <section>
              <h3 className="mb-3 text-base font-extrabold text-ink-900">{t('Внешний материал')}</h3>
              <a
                className="btn btn-outline btn-md"
                href={lesson.externalUrl}
                target="_blank"
                rel="noreferrer"
              >
                <ExternalLink size={17} /> {t('Открыть материал')}
              </a>
            </section>
          )}

          {lesson.content && (
            <section>
              <h3 className="mb-3 text-base font-extrabold text-ink-900">{t('Материал урока')}</h3>
              <div className="whitespace-pre-wrap text-[15px] leading-8 text-ink-700">{lesson.content}</div>
            </section>
          )}

          {quizLesson && (lesson.quizQuestions?.length ?? 0) > 0 && (
            <QuizLesson
              lesson={lesson}
              completed={read}
              onSubmit={(answers) => onSubmitQuiz(currentStage, lesson, answers)}
            />
          )}

          {attachments.length > 0 && (
            <section>
              <h3 className="mb-3 text-base font-extrabold text-ink-900">{t('Дополнительные материалы')}</h3>
              <div className="grid gap-3 sm:grid-cols-2">
                {attachments.map((attachment) =>
                  attachment.kind === 'image' ? (
                    <a key={attachment.id} href={attachment.mediaUrl} target="_blank" rel="noreferrer" className="overflow-hidden rounded-xl border border-ink-100 bg-paper-hover">
                      <img className="aspect-video w-full object-cover" src={attachment.mediaUrl} alt={attachment.title} />
                      <div className="flex items-center gap-2 p-3 text-sm font-bold text-ink-700"><Image size={17} /> {attachment.title}</div>
                    </a>
                  ) : attachment.kind === 'video' ? (
                    <div key={attachment.id} className="rounded-xl border border-ink-100 p-3">
                      <video controls preload="metadata" className="aspect-video w-full rounded-lg bg-ink-900" src={attachment.mediaUrl} />
                      <div className="mt-2 text-sm font-bold text-ink-700">{attachment.title}</div>
                    </div>
                  ) : attachment.kind === 'audio' ? (
                    <div key={attachment.id} className="rounded-xl border border-ink-100 p-4">
                      <div className="mb-3 flex items-center gap-2 text-sm font-bold text-ink-700">
                        <Headphones size={18} /> {attachment.title}
                      </div>
                      <audio controls preload="metadata" className="w-full" src={attachment.mediaUrl} />
                    </div>
                  ) : (
                    <a key={attachment.id} href={attachment.mediaUrl} target="_blank" rel="noreferrer" download className="flex items-center gap-3 rounded-xl border border-ink-100 p-4 hover:bg-paper-hover">
                      <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-brand-blue-100 text-brand-blue-600"><FileText size={20} /></span>
                      <span className="min-w-0 flex-1"><span className="block truncate text-sm font-extrabold text-ink-900">{attachment.title}</span><span className="text-xs text-ink-400">{formatBytes(attachment.sizeBytes)}</span></span>
                      <Download className="text-ink-400" size={18} />
                    </a>
                  ),
                )}
              </div>
            </section>
          )}

          {!lesson.content && !lesson.videoUrl && !lesson.externalUrl && !quizLesson && attachments.length === 0 && (
            <div className="rounded-xl bg-paper-hover p-5 text-sm text-ink-500">{t('Материалы этого урока пока не добавлены.')}</div>
          )}
        </div>

        <footer className="border-t border-ink-100 bg-paper-hover p-5 sm:flex sm:items-center sm:justify-between sm:gap-4 sm:p-6">
          <div className="mb-3 flex gap-2 sm:mb-0">
            {previous && <button className="btn btn-outline btn-md" onClick={() => onOpenLesson(previous.id)}><ArrowLeft size={17} /> {t('Назад')}</button>}
          </div>
          <button
            className="btn btn-primary btn-md w-full sm:w-auto"
            disabled={busy || saving || (videoLesson && !read && effectiveProgress < threshold) || (quizLesson && !read)}
            onClick={finishLesson}
          >
            <Check size={17} />{' '}
            {quizLesson && !read
              ? t('Сначала пройдите тест')
              : videoLesson && !read && effectiveProgress < threshold
              ? t('Просмотрите ещё {progress}%', { progress: threshold - effectiveProgress })
              : next
                ? read
                  ? t('Следующий урок')
                  : t('Урок изучен — далее')
                : t('Завершить курс')}
          </button>
        </footer>
      </div>
    </article>
  )
}

function QuizLesson({
  lesson,
  completed,
  onSubmit,
}: {
  lesson: Lesson
  completed: boolean
  onSubmit: (answers: Record<string, number>) => Promise<QuizSubmissionResult | null>
}) {
  const t = useLearnerT()
  const questions = lesson.quizQuestions ?? []
  const [answers, setAnswers] = useState<Record<string, number>>({})
  const [result, setResult] = useState<QuizSubmissionResult | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit() {
    if (questions.some((question) => answers[question.id] == null)) {
      setError(t('Ответьте на все вопросы'))
      return
    }
    setSubmitting(true)
    setError('')
    const nextResult = await onSubmit(answers)
    if (nextResult) setResult(nextResult)
    setSubmitting(false)
  }

  if (completed && !result) {
    return (
      <section className="rounded-2xl border border-brand-green-200 bg-brand-green-50 p-5">
        <div className="flex items-center gap-3 text-brand-green-700">
          <CheckCircle2 size={24} />
          <div>
            <div className="font-extrabold">{t('Тест пройден')}</div>
            {lesson.quizScore != null && (
              <div className="mt-1 text-sm font-semibold">{t('Результат: {score}%', { score: lesson.quizScore })}</div>
            )}
          </div>
        </div>
      </section>
    )
  }

  return (
    <section className="rounded-2xl border border-ink-100 bg-paper-hover p-4 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-extrabold text-ink-900">{t('Тест')}</h3>
          <p className="mt-1 text-sm text-ink-500">{t('Выберите один вариант ответа в каждом вопросе.')}</p>
        </div>
        <span className="chip chip-blue">{t('Проходной балл: {score}%', { score: lesson.quizPassingScore ?? 80 })}</span>
      </div>

      {result && (
        <div className={`mt-4 rounded-xl p-4 ${result.passed ? 'bg-brand-green-100 text-brand-green-800' : 'bg-surface-danger text-accent-danger'}`}>
          <div className="font-extrabold">{result.passed ? t('Тест пройден') : t('Тест не пройден')}</div>
          <div className="mt-1 text-sm font-semibold">
            {t('Результат: {score}% · правильных ответов {correct} из {total}', {
              score: result.score,
              correct: result.correctAnswers,
              total: result.totalQuestions,
            })} · {t('Попытка {attempt}', { attempt: result.attempt })}
          </div>
        </div>
      )}

      <div className="mt-5 space-y-4">
        {questions.map((question, questionIndex) => {
          const questionResult = result?.questions.find((item) => item.questionId === question.id)
          return (
            <div
              key={question.id}
              role="group"
              aria-labelledby={`quiz-question-${question.id}`}
              className="rounded-xl border border-ink-100 bg-white p-4"
            >
              <div id={`quiz-question-${question.id}`} className="text-sm font-extrabold leading-5 text-ink-900">
                {t('Вопрос {number}', { number: questionIndex + 1 })}. {question.prompt}
              </div>
              <div className="mt-3 space-y-2">
                {question.options.map((option, optionIndex) => {
                  const selected = answers[question.id] === optionIndex
                  const correct = questionResult?.correctOption === optionIndex
                  const wrongSelected = !!questionResult && selected && !correct
                  return (
                    <label
                      key={`${question.id}-${optionIndex}`}
                      className={`flex cursor-pointer items-start gap-3 rounded-xl border px-3 py-3 text-sm font-semibold transition ${
                        correct
                          ? 'border-brand-green-300 bg-brand-green-50 text-brand-green-800'
                          : wrongSelected
                            ? 'border-red-200 bg-red-50 text-red-700'
                            : selected
                              ? 'border-brand-green-400 bg-brand-green-50 text-ink-800'
                              : 'border-ink-100 bg-white text-ink-700 hover:bg-paper-hover'
                      }`}
                    >
                      <input
                        type="radio"
                        name={`quiz-${lesson.id}-${question.id}`}
                        checked={selected}
                        disabled={!!result || submitting}
                        onChange={() => setAnswers((current) => ({ ...current, [question.id]: optionIndex }))}
                      />
                      <span>{option}</span>
                    </label>
                  )
                })}
              </div>
              {questionResult && (
                <div className={`mt-3 text-xs font-bold ${questionResult.correct ? 'text-brand-green-700' : 'text-red-700'}`}>
                  {questionResult.correct ? t('Правильный ответ') : t('Неправильный ответ')}
                  {questionResult.explanation && <span className="ml-1 font-semibold text-ink-500">· {questionResult.explanation}</span>}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {error && <div className="mt-4 text-sm font-bold text-accent-danger">{error}</div>}
      <div className="mt-5 flex justify-end">
        {result && !result.passed ? (
          <button
            type="button"
            className="btn btn-outline btn-md"
            onClick={() => {
              setAnswers({})
              setResult(null)
              setError('')
            }}
          >
            <RefreshCw size={17} /> {t('Повторить тест')}
          </button>
        ) : !result ? (
          <button type="button" className="btn btn-primary btn-md" disabled={submitting} onClick={() => void submit()}>
            {submitting ? <Loader2 className="animate-spin" size={17} /> : <Check size={17} />}
            {submitting ? t('Отправляем ответы…') : t('Завершить тест')}
          </button>
        ) : null}
      </div>
    </section>
  )
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} КБ`
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`
}

function LessonRow({
  lesson,
  number,
  read,
  disabled,
  onOpen,
}: {
  lesson: Lesson
  number: number
  read: boolean
  disabled: boolean
  onOpen: () => void
}) {
  const t = useLearnerT()
  return (
    <article>
      <button
        className="flex w-full items-center gap-3 p-5 text-left hover:bg-paper-hover disabled:cursor-not-allowed disabled:opacity-55 disabled:hover:bg-white"
        disabled={disabled}
        onClick={onOpen}
      >
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-extrabold ${read ? 'bg-brand-green-600 text-white' : disabled ? 'bg-ink-100 text-ink-400' : 'bg-brand-green-100 text-brand-green-700'}`}>
          {read ? <Check size={18} /> : disabled ? <Lock size={16} /> : number}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block font-extrabold text-ink-900">{lesson.title}</span>
          <span className="mt-1 block text-xs font-semibold text-ink-400">
            {disabled ? t('Сначала завершите предыдущий урок') : lesson.durationMin ? t('{minutes} мин', { minutes: lesson.durationMin }) : t('Учебный материал')}
          </span>
        </span>
        {disabled
          ? <Lock className="shrink-0 text-ink-300" size={18} />
          : <ChevronDown className="-rotate-90 shrink-0 text-ink-400" size={20} />}
      </button>
    </article>
  )
}

function EventCard({ event, compact = false }: { event: TrainingEvent; compact?: boolean }) {
  const t = useLearnerT()
  const availableSeats = Math.max(0, event.capacity - event.occupied)
  return (
    <article className={`rounded-2xl border border-ink-100 bg-white ${compact ? 'p-4' : 'p-5'} shadow-card`}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-xs font-extrabold uppercase tracking-wider text-brand-green-700">{t('Очное мероприятие')}</div>
          <h3 className="mt-1 font-extrabold text-ink-900">{event.title}</h3>
        </div>
        <span className="chip chip-blue">{t('{count} мест', { count: availableSeats })}</span>
      </div>
      <div className="mt-4 space-y-2 text-sm font-semibold text-ink-500">
        <div className="flex items-center gap-2"><CalendarDays size={16} /> {formatDateTime(event.startsAt)}</div>
        <div className="flex items-center gap-2"><MapPin size={16} /> {[event.city, event.address].filter(Boolean).join(' · ')}</div>
      </div>
      {event.mapUrl && (
        <a className="mt-3 inline-flex items-center gap-1 text-sm font-extrabold text-brand-green-700" href={event.mapUrl} target="_blank" rel="noreferrer">
          {t('Открыть карту')} <ExternalLink size={14} />
        </a>
      )}
    </article>
  )
}

function CertificateCard({ certificate }: { certificate: Certificate }) {
  const content = (
    <>
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-brand-green-100 text-brand-green-700"><Award size={22} /></span>
      <span className="min-w-0 flex-1">
        <span className="block truncate font-extrabold text-ink-900">{certificate.programName}</span>
        <span className="mt-1 block text-xs font-semibold text-ink-400">№ {certificate.number} · {formatDate(certificate.issuedAt)}</span>
      </span>
      {certificate.score != null && <span className="chip chip-green">{certificate.score}%</span>}
      {certificate.pdfUrl && <ExternalLink className="shrink-0 text-ink-400" size={17} />}
    </>
  )
  const className = 'flex items-center gap-3 rounded-2xl border border-ink-100 bg-white p-4 text-left shadow-card transition hover:bg-paper-hover'
  return certificate.pdfUrl ? (
    <a className={className} href={certificate.pdfUrl} target="_blank" rel="noreferrer">{content}</a>
  ) : (
    <div className={className}>{content}</div>
  )
}
