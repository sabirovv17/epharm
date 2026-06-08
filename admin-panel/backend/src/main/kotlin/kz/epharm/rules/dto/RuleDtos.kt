package kz.epharm.rules.dto

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kz.epharm.rules.entity.RuleAbTest
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleComparisonRow
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import java.math.BigDecimal
import java.time.Instant

/**
 * Полный DTO правила, отдаётся фронту. Зеркал interface Rule в
 * frontend/src/mocks/fixtures.ts. Ключевая разница: convRate здесь — Double
 * (JSON numeric), а в БД хранится BigDecimal (5,2).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RuleDto(
    val id: String,
    val type: RuleType,
    val status: RuleStatus,
    val trigger: RuleTrigger,
    val recommend: String,
    val bonus: Int,
    val script: String,
    val advantages: List<String>,
    val abTest: RuleAbTest?,
    // Богатая карточка (Фаза 2): сравнение, партнёр, цель. null — нет богатой карточки.
    val card: RuleCard?,
    val pharmacies: Int,
    val impressions: Int,
    val accepts: Int,
    val convRate: Double,
    val revenue: Long,
    val payout: Long,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: RuleEntity): RuleDto = RuleDto(
            id = e.id,
            type = e.type,
            status = e.status,
            trigger = e.trigger,
            recommend = e.recommend,
            bonus = e.bonus,
            script = e.script,
            advantages = e.advantages,
            abTest = e.abTest,
            card = e.card,
            pharmacies = e.pharmacies,
            impressions = e.impressions,
            accepts = e.accepts,
            convRate = e.convRate.toDouble(),
            revenue = e.revenue,
            payout = e.payout,
            createdBy = e.createdBy,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

// ── Триггер DTO (с валидацией) ───────────────────────────────────────────────

data class TriggerDto(
    @field:NotBlank
    @field:Pattern(regexp = "product|mnn|product_any")
    val kind: String,
    @field:NotNull
    val value: Any,
    val exclude: List<String>? = null,
) {
    fun toEntity(): RuleTrigger = RuleTrigger(kind = kind, value = value, exclude = exclude)
}

data class AbTestDto(
    @field:NotBlank
    val variant: String,
    @field:Min(1)
    val share: Int,
) {
    fun toEntity(): RuleAbTest = RuleAbTest(variant = variant, share = share)
}

// ── Карточка DTO (Фаза 2) — наполнение карточки фармацевта из админки ─────────

data class ComparisonRowDto(
    @field:NotBlank
    @field:Size(max = 120)
    val label: String,
    @field:Size(max = 200)
    val triggerValue: String = "",
    @field:Size(max = 200)
    val recommendValue: String = "",
    val recommendHighlight: Boolean = false,
) {
    fun toEntity() = RuleComparisonRow(label, triggerValue, recommendValue, recommendHighlight)
}

data class CardDto(
    @field:Size(max = 64)
    val partnerLabel: String? = null,
    @field:Valid
    val comparison: List<ComparisonRowDto> = emptyList(),
    @field:Size(max = 120)
    val goalLabel: String? = null,
    @field:Min(0)
    val goalTarget: Int? = null,
    @field:Min(0)
    val goalBonus: Int? = null,
) {
    fun toEntity() = RuleCard(
        partnerLabel = partnerLabel?.takeIf { it.isNotBlank() },
        comparison = comparison.map { it.toEntity() },
        goalLabel = goalLabel?.takeIf { it.isNotBlank() },
        goalTarget = goalTarget,
        goalBonus = goalBonus,
    )
}

/**
 * Create-запрос. ID присваивается сервером.
 */
data class CreateRuleRequest(
    @field:NotNull
    val type: RuleType,
    val status: RuleStatus? = RuleStatus.draft,
    @field:Valid
    @field:NotNull
    val trigger: TriggerDto,
    @field:NotBlank
    val recommend: String,
    @field:Min(0)
    val bonus: Int = 0,
    @field:Size(max = 2000)
    val script: String = "",
    val advantages: List<String> = emptyList(),
    @field:Valid
    val abTest: AbTestDto? = null,
    @field:Valid
    val card: CardDto? = null,
)

/**
 * Partial-update (PATCH). Только поля, которые фронт реально умеет менять
 * через RuleBuilder. Метрики (impressions/accepts/...) не патчатся вручную —
 * их пишет ETL Этапа 4.
 */
data class UpdateRuleRequest(
    val status: RuleStatus? = null,
    @field:Valid
    val trigger: TriggerDto? = null,
    val recommend: String? = null,
    @field:Min(0)
    val bonus: Int? = null,
    @field:Size(max = 2000)
    val script: String? = null,
    val advantages: List<String>? = null,
    @field:Valid
    val abTest: AbTestDto? = null,
    // Явный флаг «снять AB-тест», т.к. null означает «не трогать».
    val clearAbTest: Boolean = false,
    @field:Valid
    val card: CardDto? = null,
    // Явный флаг «убрать богатую карточку», т.к. null означает «не трогать».
    val clearCard: Boolean = false,
)
