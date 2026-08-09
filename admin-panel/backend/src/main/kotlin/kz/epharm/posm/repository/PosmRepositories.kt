package kz.epharm.posm.repository

import java.time.Instant
import kz.epharm.posm.entity.PosSaleEntity
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

/**
 * Одна уже развёрнутая строка журнала показов/продаж. Продажные позиции извлекаются из
 * `pos_sales.items` на стороне PostgreSQL, чтобы API мог читать только запрошенную страницу,
 * а не загружать все чеки за аналитическое окно в память JVM.
 */
interface RecommendationLogRow {
    val id: String
    val eventType: String
    val eventAt: Instant
    val title: String
    val triggerSku: String?
    val pharmacyId: String
    val pharmacistId: String
    val pharmacistName: String?
    val reportedPharmacistId: String?
    val reportedPharmacistName: String?
    val pharmacistSource: String?
    val amount: Long
    val converted: Boolean
    val secondsToSale: Int?
    val units: Double?
    val saleId: String?
}

@Repository
interface PosSaleRepository : JpaRepository<PosSaleEntity, String> {
    /** Чеки за период (свежие первыми) — для единого журнала показ+продажа в админке. */
    fun findByPrintedAtGreaterThanEqualOrderByPrintedAtDesc(since: Instant): List<PosSaleEntity>

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

    /** Неатрибутированные показы этого чека — кандидаты на закрытие продажей (V032). */
    fun findBySessionIdAndSoldAtIsNull(sessionId: String): List<RecommendationEventEntity>

    /** События за период (свежие первыми) — для раздела аналитики «Показано рекомендаций». */
    fun findByShownAtGreaterThanEqualOrderByShownAtDesc(since: Instant): List<RecommendationEventEntity>

    /**
     * Единый журнал: показы + каждая позиция завершённого чека. Атрибуцию рекомендации
     * повторяем по тому же контракту, что использовался в сервисе: штрихкод → точное имя →
     * первая позиция чека. LIMIT/OFFSET применяются уже после UNION и сортировки.
     */
    @Query(
        nativeQuery = true,
        value = """
            WITH fastest_attribution AS (
                SELECT DISTINCT ON (re.sale_id)
                       re.sale_id,
                       re.seconds_to_sale,
                       re.recommend_name,
                       p.barcode AS recommend_barcode
                FROM recommendation_events re
                LEFT JOIN products p ON p.id = re.recommend_sku
                WHERE re.sale_id IS NOT NULL
                  AND re.seconds_to_sale IS NOT NULL
                  AND re.sold_at >= :since
                ORDER BY re.sale_id, re.seconds_to_sale ASC, re.id ASC
            ),
            sale_rows AS (
                SELECT s.*,
                       item.value AS item,
                       item.ordinality AS item_ordinality,
                       jsonb_array_length(s.items) AS item_count,
                       attr.seconds_to_sale AS attributed_seconds,
                       matched.item_ordinality AS matched_ordinality
                FROM pos_sales s
                LEFT JOIN fastest_attribution attr ON attr.sale_id = s.id
                LEFT JOIN LATERAL (
                    SELECT COALESCE(
                        MIN(candidate.ordinality) FILTER (
                            WHERE NULLIF(attr.recommend_barcode, '') IS NOT NULL
                              AND candidate.value ->> 'barcode' = attr.recommend_barcode
                        ),
                        MIN(candidate.ordinality) FILTER (
                            WHERE BTRIM(LOWER(COALESCE(attr.recommend_name, ''))) <> ''
                              AND BTRIM(LOWER(COALESCE(candidate.value ->> 'name', ''))) =
                                  BTRIM(LOWER(attr.recommend_name))
                        ),
                        1
                    ) AS item_ordinality
                    FROM jsonb_array_elements(s.items) WITH ORDINALITY candidate(value, ordinality)
                ) matched ON attr.sale_id IS NOT NULL
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_array_length(s.items) = 0 THEN '[{}]'::jsonb
                        ELSE s.items
                    END
                ) WITH ORDINALITY item(value, ordinality)
                WHERE s.printed_at >= :since
            )
            SELECT e.id AS "id",
                   'show' AS "eventType",
                   COALESCE(e.displayed_at, e.shown_at) AS "eventAt",
                   e.recommend_name AS "title",
                   e.trigger_sku AS "triggerSku",
                   e.pharmacy_id AS "pharmacyId",
                   e.pharmacist_id AS "pharmacistId",
                   CAST(NULL AS VARCHAR) AS "pharmacistName",
                   CAST(NULL AS VARCHAR) AS "reportedPharmacistId",
                   CAST(NULL AS VARCHAR) AS "reportedPharmacistName",
                   CAST(NULL AS VARCHAR) AS "pharmacistSource",
                   e.expected_amount AS "amount",
                   (e.sold_at IS NOT NULL) AS "converted",
                   e.seconds_to_sale AS "secondsToSale",
                   CAST(NULL AS DOUBLE PRECISION) AS "units",
                   CAST(NULL AS VARCHAR) AS "saleId"
            FROM recommendation_events e
            WHERE e.shown_at >= :since

            UNION ALL

            SELECT CASE
                       WHEN s.item_count = 0 THEN s.id
                       ELSE s.id || '#' || (s.item_ordinality - 1)::text
                   END AS "id",
                   'sale' AS "eventType",
                   s.printed_at AS "eventAt",
                   CASE
                       WHEN s.item_count = 0 THEN 'Чек'
                       ELSE COALESCE(NULLIF(s.item ->> 'name', ''), NULLIF(s.item ->> 'sku', ''), 'позиция')
                   END AS "title",
                   CAST(NULL AS VARCHAR) AS "triggerSku",
                   s.pharmacy_id AS "pharmacyId",
                   s.pharmacist_id AS "pharmacistId",
                   s.pharmacist_name AS "pharmacistName",
                   s.reported_pharmacist_id AS "reportedPharmacistId",
                   s.reported_pharmacist_name AS "reportedPharmacistName",
                   s.pharmacist_source AS "pharmacistSource",
                   CASE
                       WHEN s.item_count = 0 THEN s.total_amount
                       ELSE CAST(COALESCE(NULLIF(s.item ->> 'total', ''), '0') AS BIGINT)
                   END AS "amount",
                   false AS "converted",
                   CASE
                       WHEN s.attributed_seconds IS NOT NULL
                        AND s.item_ordinality = COALESCE(s.matched_ordinality, 1)
                       THEN s.attributed_seconds
                       ELSE NULL
                   END AS "secondsToSale",
                   CASE
                       WHEN s.item_count > 0
                        AND CAST(COALESCE(NULLIF(s.item ->> 'qty', ''), '0') AS DOUBLE PRECISION) > 0
                       THEN CAST(s.item ->> 'qty' AS DOUBLE PRECISION)
                       ELSE NULL
                   END AS "units",
                   s.id AS "saleId"
            FROM sale_rows s

            ORDER BY "eventAt" DESC, "id" DESC
            LIMIT :pageSize OFFSET :rowOffset
        """,
    )
    fun findJournalRowsSince(
        @Param("since") since: Instant,
        @Param("pageSize") pageSize: Int,
        @Param("rowOffset") rowOffset: Long,
    ): List<RecommendationLogRow>

    /** Общее число строк после разворачивания каждого чека в позиции. */
    @Query(
        nativeQuery = true,
        value = """
            SELECT
                (SELECT COUNT(*) FROM recommendation_events WHERE shown_at >= :since) +
                (SELECT COALESCE(SUM(GREATEST(jsonb_array_length(items), 1)), 0)
                 FROM pos_sales WHERE printed_at >= :since)
        """,
    )
    fun countJournalRowsSince(@Param("since") since: Instant): Long

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
