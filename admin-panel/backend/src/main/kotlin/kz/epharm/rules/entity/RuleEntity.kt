package kz.epharm.rules.entity

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/**
 * Тип правила. Substitution — замена при сканировании trigger'а; crosssell —
 * добавка к корзине.
 */
enum class RuleType { substitution, crosssell }

/**
 * Статусы. draft пока не используется в UI (резерв под сохранение черновика
 * до публикации), archived достижим только через archive endpoint.
 */
enum class RuleStatus { active, paused, draft, archived }

/**
 * Триггер: что должно произойти в чеке/корзине чтобы правило сработало.
 *  - kind='product'      → value:String, конкретный товар по id
 *  - kind='mnn'          → value:String, любой товар с такой mnn
 *                          + опционально exclude:List<String> чтобы не рекомендовать сам себя
 *  - kind='product_any'  → value:List<String>, сработает на любом из перечисленных
 *
 * Храним как jsonb, потому что value полиморфно. Frontend type RuleTrigger
 * зеркалит структуру.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RuleTrigger(
    val kind: String,
    val value: Any,
    val exclude: List<String>? = null,
)

/**
 * AB-тест. null означает «прод-вариант всем 100%». Когда задан — фронт
 * показывает badge с долей вариативной группы.
 */
data class RuleAbTest(
    val variant: String,
    val share: Int,
)

/** Одна строка таблицы «Сравнение» в карточке (товар-триггер vs рекомендованный). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RuleComparisonRow(
    val label: String,
    val triggerValue: String,
    val recommendValue: String,
    val recommendHighlight: Boolean = false,
)

/**
 * Презентационные поля правила для богатой карточки фармацевта (Фаза 2). Задаются в админке.
 * null/пусто → касса покажет базовые поля (название/цена/бонус/скрипт/advantages).
 *  - partnerLabel — бейдж «ПАРТНЁР EPHARM»;
 *  - comparison — таблица сравнения;
 *  - goalLabel/goalTarget/goalBonus — цель «N/target замен <label>», прогресс считается динамически.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RuleCard(
    val partnerLabel: String? = null,
    val comparison: List<RuleComparisonRow> = emptyList(),
    val goalLabel: String? = null,
    val goalTarget: Int? = null,
    val goalBonus: Int? = null,
)

@Entity
@Table(name = "rules")
class RuleEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "type", nullable = false, length = 32)
    var typeRaw: String = RuleType.substitution.name,

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = RuleStatus.draft.name,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger", nullable = false, columnDefinition = "jsonb")
    var trigger: RuleTrigger = RuleTrigger(kind = "product", value = ""),

    @Column(name = "recommend", nullable = false, length = 64)
    var recommend: String = "",

    @Column(name = "bonus", nullable = false)
    var bonus: Int = 0,

    @Column(name = "script", nullable = false, columnDefinition = "text")
    var script: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "advantages", nullable = false, columnDefinition = "jsonb")
    var advantages: List<String> = emptyList(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ab_test", columnDefinition = "jsonb")
    var abTest: RuleAbTest? = null,

    // Богатая карточка (Фаза 2): сравнение, партнёр, цель. null = только базовые поля.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "card", columnDefinition = "jsonb")
    var card: RuleCard? = null,

    /**
     * Кампания (promos.id), из которой сгенерировано правило. NULL — ручное правило (legacy).
     * Правила замены/кросс-селла создаются из карточки кампании (см. PromoRulesService).
     */
    @Column(name = "promo_id", length = 64)
    var promoId: String? = null,

    @Column(name = "pharmacies", nullable = false)
    var pharmacies: Int = 0,

    @Column(name = "impressions", nullable = false)
    var impressions: Int = 0,

    @Column(name = "accepts", nullable = false)
    var accepts: Int = 0,

    @Column(name = "conv_rate", nullable = false, precision = 5, scale = 2)
    var convRate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "revenue", nullable = false)
    var revenue: Long = 0,

    @Column(name = "payout", nullable = false)
    var payout: Long = 0,

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var type: RuleType
        get() = RuleType.valueOf(typeRaw)
        set(value) { typeRaw = value.name }

    var status: RuleStatus
        get() = RuleStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
