package kz.epharm.posm.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Тип рекомендации (зеркалит RuleType). */
enum class RecommendationKind { substitution, crosssell }

/** Жизненный цикл рекомендации: показана → принята/отклонена/истекла. */
enum class RecommendationOutcome { shown, accepted, rejected, expired }

/**
 * Факт + результат показанной рекомендации — доказательная база бонуса.
 * Создаётся при показе (outcome=shown), обновляется при решении фармацевта.
 */
@Entity
@Table(name = "recommendation_events")
class RecommendationEventEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "session_id", nullable = false, length = 64)
    var sessionId: String = "",

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "pharmacy_id", nullable = false, length = 64)
    var pharmacyId: String = "",

    @Column(name = "rule_id", nullable = false, length = 64)
    var ruleId: String = "",

    @Column(name = "kind", nullable = false, length = 32)
    var kindRaw: String = RecommendationKind.substitution.name,

    @Column(name = "trigger_sku", length = 64)
    var triggerSku: String? = null,

    @Column(name = "recommend_sku", nullable = false, length = 64)
    var recommendSku: String = "",

    @Column(name = "recommend_name", nullable = false)
    var recommendName: String = "",

    @Column(name = "expected_amount", nullable = false)
    var expectedAmount: Long = 0,

    @Column(name = "bonus", nullable = false)
    var bonus: Long = 0,

    @Column(name = "outcome", nullable = false, length = 32)
    var outcomeRaw: String = RecommendationOutcome.shown.name,

    @Column(name = "pending_bonus_id", length = 64)
    var pendingBonusId: String? = null,

    @Column(name = "shown_at", nullable = false)
    var shownAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    // ── Атрибуция продажи (V032): конверсия показ→продажа + время до продажи ──
    /** Когда касса РЕАЛЬНО показала попап (пинг из outbox); fallback для тайминга — shownAt. */
    @Column(name = "displayed_at")
    var displayedAt: Instant? = null,

    /** printed_at чека, в котором продан рекомендованный товар (null — ещё не продан). */
    @Column(name = "sold_at")
    var soldAt: Instant? = null,

    /** Какой pos_sale закрыл рекомендацию (для трассировки). */
    @Column(name = "sale_id", length = 64)
    var saleId: String? = null,

    /** Время от показа до продажи в секундах: soldAt − COALESCE(displayedAt, shownAt). */
    @Column(name = "seconds_to_sale")
    var secondsToSale: Int? = null,
) {
    var kind: RecommendationKind
        get() = RecommendationKind.valueOf(kindRaw)
        set(value) { kindRaw = value.name }

    var outcome: RecommendationOutcome
        get() = RecommendationOutcome.valueOf(outcomeRaw)
        set(value) { outcomeRaw = value.name }
}
