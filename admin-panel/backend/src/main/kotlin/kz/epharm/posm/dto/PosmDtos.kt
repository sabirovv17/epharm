package kz.epharm.posm.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Позиция корзины кассы Стандарт-Н. Сначала используется точный [barcode], затем [sku]
 * (iPartID, когда EAN недоступен), затем fallback по [name] (sname из лога).
 *  - sku:     локальный iPartID кассы Стандарт-Н — запасной точный ключ;
 *  - barcode: EAN/GTIN (variant.barcode из Medusa) — приоритетный межаптечный ключ;
 *  - name:    название из лога кассы (sname) — fallback-ключ для неполных логов;
 *  - qty:     количество.
 */
data class CartItemDto(
    val sku: String? = null,
    val barcode: String? = null,
    val name: String? = null,
    val qty: Double = 1.0,
)

/**
 * Запрос рекомендаций от кассы. Передаётся вся текущая корзина + (опц.) только что
 * отсканированный штрих-код. sessionId = один открытый чек (генерит POSM-клиент).
 */
data class RecommendRequest(
    // pharmacistId опционален: фармацевт приходит из лога кассы (kassir=), работает посменно;
    // если касса его не прислала — пусто.
    val pharmacistId: String = "",
    // ФИО из Standard-N позволяет выполнить строгое однозначное сопоставление с внутренним
    // профилем той же аптеки, когда USER_ID двух систем различаются.
    @field:Size(max = 255)
    val pharmacistName: String? = null,
    @field:NotBlank
    val pharmacyId: String,
    @field:NotBlank
    val sessionId: String,
    /** Последний отсканированный EAN-13 (информационно; матчинг идёт по cart). */
    val scannedBarcode: String? = null,
    @field:NotNull
    @field:Valid
    val cart: List<CartItemDto> = emptyList(),
)

/** Строка таблицы «Сравнение» в карточке. Зеркалит C# ComparisonRow. */
data class ComparisonRowDto(
    val label: String,
    val triggerValue: String,
    val recommendValue: String,
    val recommendHighlight: Boolean,
)

/**
 * Одна рекомендация для popup фармацевта. Зеркалит C# Recommendation — богатые поля (Фаза 2)
 * приходят из правила (card) + каталога; null/пусто карточка скрывает.
 */
data class RecommendationDto(
    val eventId: String,
    val ruleId: String,
    val kind: String,
    val triggerSku: String?,
    val triggerIpartId: String?,  // iPartID Standard-N; triggerSku is internal catalog productId
    val triggerName: String?,
    val triggerVolume: String?,
    val triggerPrice: Int?,
    val triggerBarcode: String?,    // EAN-13 исходного товара (для лога кассы)
    val recommendSku: String,
    val recommendName: String,
    val recommendPrice: Int,
    val recommendBarcode: String?,  // EAN-13 рекомендованного товара (касса логирует + кладёт в чек)
    val partnerLabel: String?,
    val bonus: Int,
    val script: String,
    val advantages: List<String>,
    val comparison: List<ComparisonRowDto>,
    val goalText: String?,
    val goalBonus: Int?,
)

/**
 * Конфликт правил для кассы (T2): почему замену/кросс-селл показать нельзя.
 * Зеркалит C# Conflict. Касса показывает баннер «невозможно» вместо рекомендации.
 */
data class ConflictDto(
    val kind: String,           // "ambiguous_substitution" | "contradiction"
    val triggerName: String?,
    val reason: String,
    val ruleIds: List<String>,
)

/**
 * Ответ Rules Engine: до 2 рекомендаций (substitution раньше crosssell, по бонусу DESC)
 * + список конфликтов (если есть — касса сообщает о невозможности замены/кросс-селла).
 */
data class RecommendResponse(
    val sessionId: String,
    val recommendations: List<RecommendationDto>,
    val conflicts: List<ConflictDto> = emptyList(),
)

/** Результат рекомендации: фармацевт принял замену/cross-sell или пропустил. */
data class OutcomeRequest(
    @field:NotBlank
    @field:Pattern(regexp = "accepted|rejected")
    val outcome: String,
    /** Фактический проданный sku (если принял — обычно = recommendSku). Для аудита. */
    val finalSku: String? = null,
)

data class OutcomeResponse(
    val eventId: String,
    val outcome: String,
    val pendingBonusId: String?,
)

/**
 * Подтверждение фактического показа рекомендации фармацевту (V032). Касса шлёт client-время
 * показа попапа через outbox (гарантированная доставка). По нему считаем время до продажи точнее,
 * чем по shown_at (момент генерации рекомендации).
 */
data class MarkShownRequest(
    @field:NotNull
    val shownAt: java.time.Instant,
)

data class MarkShownResponse(val eventId: String, val ok: Boolean)

// ── Источник №1 сверки: завершённый чек из лога кассы ───────────────────────

data class PosSaleItemDto(
    // sku — iPartID кассы. sku/barcode/name резолвятся в productId тем же матчерем, что /recommend.
    val sku: String? = null,
    val barcode: String? = null,
    val name: String = "",
    val qty: Double = 1.0,
    val price: Long = 0,
    val total: Long = 0,
)

/**
 * Завершённый чек Standard-N. POSM подтверждает его закрытием активного DOCS-документа
 * или маркером печати в кассовом логе. saleId детерминирован по аптеке и DOCS.ID
 * (fallback — по сессии чека), поэтому повторный сигнал и outbox-retry не двоят данные.
 */
data class PosSaleRequest(
    @field:NotBlank
    val saleId: String,
    // pharmacistId опционален: фармацевт из лога кассы (kassir=), может быть пуст.
    val pharmacistId: String = "",
    // Снимок имени из локальной БД Standard-N. Используется для аудита и отображения, пока
    // внешний USER_ID ещё не сопоставлен с внутренним фармацевтом Epharm.
    @field:Size(max = 255)
    val pharmacistName: String? = null,
    @field:NotBlank
    val pharmacyId: String,
    val sessionId: String? = null,
    /** Локальный DOCS.ID Standard-N; это не фискальный номер. */
    val sourceDocumentId: Long? = null,
    @field:Size(max = 64)
    val captureSource: String? = null,
    @field:Size(max = 16)
    val artifactFormat: String? = null,
    @field:Size(max = 64)
    @field:Pattern(regexp = "[0-9a-fA-F]{64}")
    val artifactSha256: String? = null,
    @field:Size(max = 64)
    val artifactSource: String? = null,
    val fiscalId: String? = null,
    @field:Size(max = 128)
    val fiscalSign: String? = null,
    @field:Size(max = 128)
    val cashRegisterRegistrationNumber: String? = null,
    @field:Size(max = 255)
    val ofdName: String? = null,
    val cashier: String? = null,
    val shift: String? = null,
    val totalAmount: Long = 0,
    @field:NotNull
    @field:Valid
    val items: List<PosSaleItemDto> = emptyList(),
    @field:NotNull
    val printedAt: java.time.Instant,
)

data class PosSaleResponse(val saleId: String, val accepted: Boolean)

/** Пульс кассы (T4): подтверждение, что устройство учтено как онлайн. */
data class HeartbeatResponse(val ok: Boolean, val deviceId: String)
