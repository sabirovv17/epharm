package kz.epharm.reporting.exports

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import kz.epharm.reporting.CampaignReport
import kz.epharm.reporting.CampaignReportService
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class CampaignPharmacyCoverage(val pharmacyId: String?, val currentName: String?, val currentCity: String?, val currentAddress: String?)
data class CampaignExport(val report: CampaignReport, val pharmacies: List<CampaignPharmacyCoverage>)

/** Original counts and their pharmacy coverage share one database snapshot and report cutoff. */
@Service
class CampaignExportReader(private val campaigns: CampaignReportService, private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(id: String, from: LocalDate, to: LocalDate): CampaignExport {
        val report = campaigns.report(id, from, to)
        val start = from.atStartOfDay(ZoneId.of(report.timezone)).toInstant()
        val rows = jdbc.query("""SELECT DISTINCT nullif(e.pharmacy_id,'') AS pharmacy_id,h.name,h.city,h.addr
            FROM recommendation_events e JOIN rules r ON r.id=e.rule_id AND r.promo_id=:id
            LEFT JOIN pharmacies h ON h.id=e.pharmacy_id
            WHERE e.kind IN ('substitution','crosssell') AND ((e.shown_at>=:since AND e.shown_at<:until)
                OR (e.displayed_at>=:since AND e.displayed_at<:until))
            ORDER BY pharmacy_id NULLS LAST LIMIT 50001""", mapOf("id" to id,
            "since" to Timestamp.from(start), "until" to Timestamp.from(report.asOfExclusive))) { rs, _ ->
            CampaignPharmacyCoverage(rs.getString("pharmacy_id"), rs.getString("name"), rs.getString("city"), rs.getString("addr"))
        }
        FinanceReportReader.ensureReportSize(rows.size)
        return CampaignExport(report, rows)
    }
}
