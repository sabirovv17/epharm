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
 *  - весь текст фармацевту (script, advantages, карточка-сравнение, цель).
 *
 * По save генерим/перезаписываем правила, привязанные к кампании (rules.promo_id).
 */

/** Ссылка на товар витрины (выбран в пикере). Из неё апсертим локальный товар каталога. */
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
    val price: Int? = null,
    /**
     * Скрипт ЭТОЙ пары (продвигаемый ↔ данный товар): что сказать фармацевту и почему.
     * Попадает в `rules.script` именно этого правила → видно на кассе в рекомендации.
     * Пусто → берётся общий [PromoRulesConfigDto.script] как дефолт.
     */
    @field:Size(max = 2000)
    val script: String = "",
)

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

/** Полная конфигурация правил кампании (request на PUT и тело ответа на GET). */
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
