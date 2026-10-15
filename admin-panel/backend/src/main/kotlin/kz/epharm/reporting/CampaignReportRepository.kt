package kz.epharm.reporting

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class ReportCampaign(val id: String, val title: String, val brand: String)
data class ReportAggregate(val date: LocalDate, val kind: String, val counts: ReportCounts)

@Repository
class CampaignReportRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun campaign(id: String): ReportCampaign? = jdbc.query(
        "SELECT id,title,brand FROM promos WHERE id=:id", mapOf("id" to id),
    ) { rs, _ -> ReportCampaign(rs.getString("id"), rs.getString("title"), rs.getString("brand")) }.singleOrNull()

    fun aggregate(id: String, start: Instant, end: Instant): List<ReportAggregate> = jdbc.query(
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
              AND ((e.shown_at>=:since AND e.shown_at<:until)
                   OR (e.displayed_at>=:since AND e.displayed_at<:until))
        ), displayed AS (
            SELECT *, ROW_NUMBER() OVER (
                PARTITION BY sale_id,recommend_sku
                ORDER BY valid_sale DESC, CASE WHEN outcome='accepted' AND decided_at<:until THEN 1 ELSE 0 END DESC,
                         displayed_at DESC,id DESC
            ) AS sale_rank
            FROM scoped WHERE displayed_at>=:since AND displayed_at<:until
        ), records AS (
            SELECT (shown_at AT TIME ZONE 'Asia/Almaty')::date AS day,kind,
                   1::bigint AS issued,
                   CASE WHEN displayed_at IS NULL OR displayed_at>=:until THEN 1 ELSE 0 END::bigint AS unconfirmed,
                   0::bigint AS confirmed,0::bigint AS accepted,0::bigint AS sold,0::bigint AS unverified,0::bigint AS sold_under_2m
            FROM scoped WHERE shown_at>=:since AND shown_at<:until
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
        mapOf("id" to id, "since" to Timestamp.from(start), "until" to Timestamp.from(end)),
    ) { rs, _ ->
        ReportAggregate(rs.getDate("day").toLocalDate(), rs.getString("kind"), ReportCounts(
            rs.getLong("issued"), rs.getLong("unconfirmed"), rs.getLong("confirmed"),
            rs.getLong("accepted"), rs.getLong("sold"), rs.getLong("unverified"),
            rs.getLong("sold_under_2m"),
        ))
    }
}
