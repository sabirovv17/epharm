package kz.epharm.posm.repository

import java.time.Instant
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.ProductPosCodeEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

// ── Проекции для агрегации аналитики (реальные данные из таблицы событий) ────
// Алиасы колонок в кавычках ("ruleId") — чтобы PostgreSQL сохранил camelCase и
// Spring Data сопоставил их с геттерами проекции один-в-один.

/** Агрегат по правилу: показы и принятия. convRate считается в сервисе. */
interface RuleAggRow {
    val ruleId: String
    val impressions: Long
    val accepts: Long
}

/** Агрегат по рекомендованному товару: принятия и атрибутированная выручка. */
interface SkuAggRow {
    val sku: String
    val name: String
    val accepts: Long
    val revenue: Long
}

/** Агрегат по аптеке: показы и принятия рекомендаций (для lift и топа аптек). */
interface PharmacyAggRow {
    val pharmacyId: String
    val impressions: Long
    val accepts: Long
}

/** Количество чего-либо по аптеке (например, чеков за период). */
interface PharmacyCountRow {
    val pharmacyId: String
    val cnt: Long
}

@Repository
interface ProductPosCodeRepository : JpaRepository<ProductPosCodeEntity, Long>

@Repository
interface PosSaleRepository : JpaRepository<PosSaleEntity, String> {
    /** Чеки кассы за период по каждой аптеке — для receipts30d (реальные данные). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT pharmacy_id AS "pharmacyId", COUNT(*) AS "cnt"
            FROM pos_sales WHERE printed_at >= :since GROUP BY pharmacy_id
        """,
    )
    fun countByPharmacySince(@Param("since") since: Instant): List<PharmacyCountRow>

    /**
     * Всего чеков за период по группе аптек (для пилот/контроль-сумм в lift).
     * Native с `>= :since` — согласовано с [countByPharmacySince] (граница окна включительна).
     */
    @Query(
        nativeQuery = true,
        value = "SELECT COUNT(*) FROM pos_sales WHERE pharmacy_id IN (:ids) AND printed_at >= :since",
    )
    fun countByPharmaciesSince(@Param("ids") ids: Collection<String>, @Param("since") since: Instant): Long
}

@Repository
interface RecommendationEventRepository : JpaRepository<RecommendationEventEntity, String> {
    /** Все события чека — для фильтра «не показывать отклонённое в этом чеке». */
    fun findAllBySessionId(sessionId: String): List<RecommendationEventEntity>

    /** Последнее событие правила в этом чеке — для идемпотентного показа. */
    fun findFirstBySessionIdAndRuleIdOrderByShownAtDesc(
        sessionId: String,
        ruleId: String,
    ): RecommendationEventEntity?

    /**
     * Прогресс цели (Фаза 2): сколько раз фармацевт ПРИНЯЛ это правило с начала периода —
     * для динамического счётчика «N/target» на карточке.
     */
    fun countByPharmacistIdAndRuleIdAndOutcomeRawAndDecidedAtAfter(
        pharmacistId: String,
        ruleId: String,
        outcomeRaw: String,
        decidedAt: java.time.Instant,
    ): Long

    // ── Агрегация для аналитики (реальные данные, ТЗ §3.2) ───────────────────
    // FILTER (WHERE ...) — стандарт PostgreSQL; быстрее, чем несколько запросов.

    /** Показы/принятия по правилу за период (для convRate и топа правил). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT rule_id AS "ruleId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE outcome = 'accepted') AS "accepts"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY rule_id
        """,
    )
    fun aggregateByRuleSince(@Param("since") since: Instant): List<RuleAggRow>

    /** Принятия и атрибутированная выручка по рекомендованному товару за период. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT recommend_sku AS "sku",
                   MIN(recommend_name) AS "name",
                   COUNT(*) FILTER (WHERE outcome = 'accepted') AS "accepts",
                   COALESCE(SUM(expected_amount) FILTER (WHERE outcome = 'accepted'), 0) AS "revenue"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY recommend_sku
        """,
    )
    fun aggregateBySkuSince(@Param("since") since: Instant): List<SkuAggRow>

    /** Показы/принятия по аптеке за период (для lift-конверсии и топа аптек). */
    @Query(
        nativeQuery = true,
        value = """
            SELECT pharmacy_id AS "pharmacyId",
                   COUNT(*) AS "impressions",
                   COUNT(*) FILTER (WHERE outcome = 'accepted') AS "accepts"
            FROM recommendation_events
            WHERE shown_at >= :since
            GROUP BY pharmacy_id
        """,
    )
    fun aggregateByPharmacySince(@Param("since") since: Instant): List<PharmacyAggRow>
}
