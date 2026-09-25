package kz.epharm.promo.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * DTO для авторинга правил замены/кросс-селла ИЗ кампании (T2).
 *
 * Кампания продвигает один товар (promos.medusaProductId). В её карточке админ задаёт:
 *  - replacements — товары, которые ЗАМЕНЯЕМ на продвигаемый (substitution);
 *  - crossSells   — товары, С которыми ПРЕДЛАГАЕМ продвигаемый (crosssell);
 *  - для КАЖДОЙ пары — поля, которые видны в блоке рекомендации на кассе
 *    (скрипт, преимущества, метка партнёра, таблица-сравнение, цель).
 *
 * По save генерим/перезаписываем правила, привязанные к кампании (rules.promo_id).
 */

/** Строка таблицы сравнения (зеркало rules CardDto, но в пакете промо). */
data class PromoComparisonRowDto(
    @field:NotBlank
    @field:Size(max = 120)
    val label: String,
    @field:Size(max = 200)
    val triggerValue: String = "",
    @field:Size(max = 200)
    val recommendValue: String = "",
    val recommendHighlight: Boolean = false,
)

/**
 * Дополнительный препарат, который POSM может предложить для той же пары.
 * Основным вариантом остаётся товар кампании; вместе с ним допускается не более
 * четырёх дополнительных вариантов — итого до пяти строк в секции popup.
 */
data class PromoOfferProductRefDto(
    @field:NotBlank
    @field:Size(max = 64)
    val medusaProductId: String,
    @field:Size(max = 255)
    val name: String = "",
    @field:Size(max = 128)
    val brand: String? = null,
    @field:Size(max = 128)
    val mnn: String? = null,
    @field:Size(max = 64)
    val volume: String? = null,
    @field:Size(max = 32)
    val barcode: String? = null,
    @field:Size(max = 64)
    val ipartId: String? = null,
    val price: Int? = null,
    /** Бонус за продажу именно этого варианта: 0 = без бонуса, null = legacy-дефолт кампании. */
    @field:Min(0)
    val bonus: Int? = null,
)

/**
 * Пара кампании (продвигаемый ↔ данный товар) = ОДНА рекомендация на кассе.
 * Несёт всё, что показывается в блоке рекомендации именно для этой пары.
 * Пустые поля → берётся общий дефолт из [PromoRulesConfigDto] (обратная совместимость).
 */
data class PromoRuleProductRefDto(
    @field:NotBlank
    @field:Size(max = 64)
    val medusaProductId: String,
    @field:Size(max = 255)
    val name: String = "",
    @field:Size(max = 128)
    val brand: String? = null,
    @field:Size(max = 128)
    val mnn: String? = null,
    @field:Size(max = 64)
    val volume: String? = null,
    /** Штрих-код EAN-13 (из Medusa) — стампится на ProductEntity.barcode для матчинга кассы. */
    @field:Size(max = 32)
    val barcode: String? = null,
    /** iPartID Стандарт-Н — стампится на ProductEntity.ipartId для матчинга кассы. */
    @field:Size(max = 64)
    val ipartId: String? = null,
    val price: Int? = null,
    /** Бонус за основной товар кампании в этой паре; 0 отключает бонус, null наследует кампанию. */
    @field:Min(0)
    val bonus: Int? = null,
    /** Скрипт пары: что сказать фармацевту и почему. → rules.script (видно на кассе). */
    @field:Size(max = 2000)
    val script: String = "",
    /** Преимущества (по строке) этой рекомендации. → rules.advantages. */
    val advantages: List<String> = emptyList(),
    /** Метка партнёра на карточке кассы. */
    @field:Size(max = 64)
    val partnerLabel: String? = null,
    /** Таблица-сравнение «было/стало» для этой пары. */
    @field:Valid
    val comparison: List<PromoComparisonRowDto> = emptyList(),
    /** Дополнительные варианты к основному товару кампании; максимум 4 (всего 5). */
    @field:Valid
    @field:Size(max = 4)
    val additionalRecommendations: List<PromoOfferProductRefDto> = emptyList(),
    /**
     * Статус именно этой пары: true = «Активно», false = «Черновик».
     * Правило встанет active только если И кампания active, И пара active.
     */
    val active: Boolean = true,
)

/**
 * Полная конфигурация правил кампании (request на PUT и тело ответа на GET).
 * Поля карточки (script/advantages/partnerLabel/comparison) — per-pair (в [PromoRuleProductRefDto]).
 * Поле «Цель» (goalLabel/goalTarget/goalBonus) — на уровне ВСЕЙ кампании (одно на все пары):
 * применяется ко всем правилам кампании. script/advantages/partnerLabel/comparison здесь
 * оставлены для обратной совместимости (legacy «общий дефолт»), фронт их не шлёт.
 */
data class PromoRulesConfigDto(
    @field:Valid
    val replacements: List<PromoRuleProductRefDto> = emptyList(),
    @field:Valid
    val crossSells: List<PromoRuleProductRefDto> = emptyList(),
    @field:Size(max = 2000)
    val script: String = "",
    val advantages: List<String> = emptyList(),
    @field:Size(max = 64)
    val partnerLabel: String? = null,
    @field:Valid
    val comparison: List<PromoComparisonRowDto> = emptyList(),
    // ── Цель кампании (одна на всю кампанию) ──────────────────────────────
    @field:Size(max = 120)
    val goalLabel: String? = null,
    @field:Min(0)
    val goalTarget: Int? = null,
    @field:Min(0)
    val goalBonus: Int? = null,
)

/** Ответ: текущая конфигурация + счётчики сгенерированных правил. */
data class PromoRulesViewDto(
    val promoId: String,
    val config: PromoRulesConfigDto,
    val ruleCount: Int,
    val activeCount: Int,
)
