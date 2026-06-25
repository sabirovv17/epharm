package kz.epharm.posm.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/**
 * Позиция корзины кассы Стандарт-Н. Матчинг к нашему товару идёт по точным ключам
 * [sku] (iPartID) и [barcode] (EAN-13), затем fallback по [name] (sname из лога).
 *  - sku:     iPartID кассы Стандарт-Н — точный ключ матчинга, если заполнен в админке;
 *  - barcode: EAN-13 (variant.barcode из Medusa) — точный ключ матчинга;
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
    @field:NotBlank
    val pharmacistId: String,
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
 * Чек, подтверждённый логом кассы (POSM-клиент шлёт после печати). saleId — client-GUID
 * для идемпотентности (повторная отправка из outbox не двоит данные).
 */
data class PosSaleRequest(
    @field:NotBlank
    val saleId: String,
    @field:NotBlank
    val pharmacistId: String,
    @field:NotBlank
    val pharmacyId: String,
    val sessionId: String? = null,
    val fiscalId: String? = null,
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
