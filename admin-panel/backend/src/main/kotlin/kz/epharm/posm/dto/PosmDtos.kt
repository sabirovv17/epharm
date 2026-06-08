package kz.epharm.posm.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/** Позиция корзины. sku = наш productId (POSM-клиент уже сконвертил iPartID). */
data class CartItemDto(
    @field:NotBlank
    val sku: String,
    val qty: Double = 1.0,
)

/**
 * Запрос рекомендаций от кассы. Передаётся вся текущая корзина + (опц.) только что
 * отсканированный товар. sessionId = один открытый чек (генерит POSM-клиент).
 */
data class RecommendRequest(
    @field:NotBlank
    val pharmacistId: String,
    @field:NotBlank
    val pharmacyId: String,
    @field:NotBlank
    val sessionId: String,
    val scannedSku: String? = null,
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
    val recommendSku: String,
    val recommendName: String,
    val recommendPrice: Int,
    val partnerLabel: String?,
    val bonus: Int,
    val script: String,
    val advantages: List<String>,
    val comparison: List<ComparisonRowDto>,
    val goalText: String?,
    val goalBonus: Int?,
)

/** Ответ Rules Engine: до 2 рекомендаций (substitution раньше crosssell, по бонусу DESC). */
data class RecommendResponse(
    val sessionId: String,
    val recommendations: List<RecommendationDto>,
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
    @field:NotBlank
    val sku: String,
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
