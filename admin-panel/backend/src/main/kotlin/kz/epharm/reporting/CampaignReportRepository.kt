package kz.epharm.reporting

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class ReportCampaign(val id: String, val title: String, val brand: String)
data class ReportAggregate(val date: LocalDate, val kind: String, val counts: ReportCounts)
data class ReportTimeInterval(val from: Instant, val untilExclusive: Instant)

@Repository
class CampaignReportRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun campaign(id: String): ReportCampaign? = jdbc.query(
        "SELECT id,title,brand FROM promos WHERE id=:id", mapOf("id" to id),
    ) { rs, _ -> ReportCampaign(rs.getString("id"), rs.getString("title"), rs.getString("brand")) }.singleOrNull()

    fun aggregate(id: String, start: Instant, end: Instant): List<ReportAggregate> = aggregateScoped(id,start,end,null)

    /** Binding intervals select issued/displayed cohorts; outcomes retain the report's common cutoff. */
    fun aggregateIntervals(id: String, intervals: List<ReportTimeInterval>, asOfExclusive: Instant): List<ReportAggregate> {
        val clipped=intervals.map { it.copy(untilExclusive=minOf(it.untilExclusive,asOfExclusive)) }.filter { it.from<it.untilExclusive }
        if(clipped.isEmpty()) return emptyList()
        return aggregateScoped(id,clipped.minOf { it.from },asOfExclusive,clipped)
    }

    private fun aggregateScoped(id: String, start: Instant, end: Instant, intervals: List<ReportTimeInterval>?): List<ReportAggregate> {
        val parameters=mutableMapOf<String,Any>("id" to id,"since" to Timestamp.from(start),"until" to Timestamp.from(end))
        intervals?.forEachIndexed { index, interval ->
            parameters["scopeSince$index"]=Timestamp.from(interval.from)
            parameters["scopeUntil$index"]=Timestamp.from(interval.untilExclusive)
        }
        fun cohort(column:String)=if(intervals==null) "$column>=:since AND $column<:until" else intervals.indices.joinToString(" OR ") {
            "($column>=:scopeSince$it AND $column<:scopeUntil$it)" }
        return jdbc.query(
        """
        WITH scoped AS (
            SELECT e.*, s.id AS verified_sale_id,
                   (s.id IS NOT NULL AND s.printed_at < :until) AS valid_sale
            FROM recommendation_events e
            JOIN rules r ON r.id=e.rule_id AND r.promo_id=:id
            LEFT JOIN pos_sales s ON s.id=e.sale_id
                AND s.pharmacy_id=e.pharmacy_id AND s.session_id=e.session_id
                AND s.printed_at=e.sold_at
            WHERE e.kind IN ('substitution','crosssell')
              AND ((${cohort("e.shown_at")}) OR (${cohort("e.displayed_at")}))
        ), displayed AS (
            SELECT *, ROW_NUMBER() OVER (
                PARTITION BY sale_id,recommend_sku
                ORDER BY valid_sale DESC, CASE WHEN outcome='accepted' AND decided_at<:until THEN 1 ELSE 0 END DESC,
                         displayed_at DESC,id DESC
            ) AS sale_rank
            FROM scoped WHERE ${cohort("displayed_at")}
        ), records AS (
            SELECT (shown_at AT TIME ZONE 'Asia/Almaty')::date AS day,kind,
                   1::bigint AS issued,
                   CASE WHEN displayed_at IS NULL OR displayed_at>=:until THEN 1 ELSE 0 END::bigint AS unconfirmed,
                   0::bigint AS confirmed,0::bigint AS accepted,0::bigint AS sold,0::bigint AS unverified,0::bigint AS sold_under_2m
            FROM scoped WHERE ${cohort("shown_at")}
            UNION ALL
            SELECT (displayed_at AT TIME ZONE 'Asia/Almaty')::date,kind,0,0,1,
                   CASE WHEN outcome='accepted' AND decided_at<:until THEN 1 ELSE 0 END,
                   CASE WHEN valid_sale AND sale_rank=1 THEN 1 ELSE 0 END,
                   CASE WHEN sold_at<:until AND verified_sale_id IS NULL THEN 1 ELSE 0 END,
                   CASE WHEN valid_sale AND sale_rank=1 AND sold_at>=displayed_at
                        AND sold_at<=displayed_at+interval '120 seconds' THEN 1 ELSE 0 END
            FROM displayed
        )
        SELECT day,kind,SUM(issued) issued,SUM(unconfirmed) unconfirmed,SUM(confirmed) confirmed,
               SUM(accepted) accepted,SUM(sold) sold,SUM(unverified) unverified,SUM(sold_under_2m) sold_under_2m
        FROM records GROUP BY day,kind ORDER BY day,kind
        """.trimIndent(),
        parameters,
    ) { rs, _ ->
        ReportAggregate(rs.getDate("day").toLocalDate(), rs.getString("kind"), ReportCounts(
            rs.getLong("issued"), rs.getLong("unconfirmed"), rs.getLong("confirmed"),
            rs.getLong("accepted"), rs.getLong("sold"), rs.getLong("unverified"),
            rs.getLong("sold_under_2m"),
        ))
        }
    }
}
