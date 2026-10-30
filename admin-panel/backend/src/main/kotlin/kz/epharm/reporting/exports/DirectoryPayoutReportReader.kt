package kz.epharm.reporting.exports

import java.sql.Timestamp
import java.time.Instant
import kz.epharm.auth.policy.PharmacyScope
import kz.epharm.finance.dto.PayoutBatchDto
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class PayoutReportItem(
    val id: String, val batchId: String, val pharmacistId: String, val pharmacistName: String,
    val pharmacy: String, val city: String, val receipts: Int, val rules: Int, val amount: Long, val flag: String?,
    val createdAt: Instant, val historicalPharmacyId: String? = null,
)
data class PayoutReport(
    val period: ReportPeriod, val generatedAt: Instant, val asOfExclusive: Instant, val statusFilter: PayoutBatchStatus?,
    val batches: List<PayoutBatchDto>, val items: List<PayoutReportItem>,
    val notice: String = "Резерв и подтверждение пакета в системе не доказывают банковский перевод. Исторический ID аптеки не записан в источнике; название сохранено без сопоставления по имени",
)
data class PharmacistReportEntry(
    val id: String, val name: String, val iin: String?, val phone: String?, val status: String, val tier: String,
    val hasPassword: Boolean, val loginType: String, val loginValue: String?, val accessStatus: String,
    val currentPharmacyId: String?, val currentPharmacyName: String?, val chainId: String?, val chainName: String?,
    val city: String, val pharmacyCity: String?, val address: String?, val district: String?,
    val receipts30d: Int?, val rulesAccepted30d: Int?, val earned30d: Long?, val balance: Long?,
    val coursesDone: Int, val coursesTotal: Int, val joinedAt: String, val createdAt: Instant, val updatedAt: Instant,
)
data class PharmacistReport(
    val generatedAt: Instant, val scopePharmacyIds: Set<String>?, val statusFilter: String?, val pharmacyFilter: String?,
    val entries: List<PharmacistReportEntry>, val globalFinancialFieldsIncluded: Boolean,
    val notice: String = "Аптека/сеть/адрес — текущая привязка, не аптека исторических начислений. Скрытые поля — null, не ноль; пароль/хеш не включены. Район не подменяет неподтверждённый дивизион",
)

@Service
class DirectoryPayoutReportReader(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun payouts(period: ReportPeriod, scope: PharmacyScope, status: PayoutBatchStatus? = null): PayoutReport {
        // The legacy payout snapshot has names/city, not a stable historical pharmacy ID (D6).
        if (scope !is PharmacyScope.All) forbidden()
        val generated = Instant.now()
        val end = period.end(generated)
        val params = mutableMapOf<String, Any>("since" to Timestamp.from(period.start()), "until" to Timestamp.from(end))
        val statusSql = if (status == null) "" else " AND b.status=:status".also { params["status"] = status.name }
        val batches = jdbc.query("""SELECT b.* FROM payout_batches b WHERE b.created_at>=:since AND b.created_at<:until
            $statusSql ORDER BY b.created_at,b.id LIMIT ${ReportWorkbook.MAX_ROWS + 1}""", params) { rs, _ ->
            PayoutBatchDto(rs.getString("id"), rs.getString("period"), PayoutBatchStatus.valueOf(rs.getString("status")),
                rs.getInt("pharmacists"), rs.getLong("amount"), rs.getInt("items"), rs.getString("reviewer_id"),
                rs.getString("reviewer_name"), rs.getTimestamp("approved_at")?.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant())
        }
        FinanceReportReader.ensureReportSize(batches.size)
        val items = if (batches.isEmpty()) emptyList() else jdbc.query("""SELECT i.* FROM payout_items i
            WHERE i.batch_id IN (:batches) ORDER BY i.batch_id,i.amount DESC,i.id LIMIT ${ReportWorkbook.MAX_ROWS + 1}""",
            mapOf("batches" to batches.map { it.id })) { rs, _ ->
            PayoutReportItem(rs.getString("id"), rs.getString("batch_id"), rs.getString("pharmacist_id"), rs.getString("pharmacist_name"),
                rs.getString("pharmacy"), rs.getString("city"), rs.getInt("receipts"), rs.getInt("rules"), rs.getLong("amount"),
                rs.getString("flag"), rs.getTimestamp("created_at").toInstant())
        }
        FinanceReportReader.ensureReportSize(items.size)
        return PayoutReport(period, generated, end, status, batches, items)
    }

    /** Field gates are supplied by the verified server access context, never by query parameters. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun pharmacists(scope: PharmacyScope, personalForPharmacy: (String) -> Boolean, financialAll: Boolean,
        status: String? = null, pharmacyId: String? = null): PharmacistReport {
        if (status != null && status !in setOf("active", "pending", "blocked") || pharmacyId != null &&
            (pharmacyId.isBlank() || pharmacyId.length > 64 || pharmacyId.any(Char::isISOControl))) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный статус или ID аптеки")
        }
        val assigned = when (scope) {
            PharmacyScope.All -> null
            is PharmacyScope.Assigned -> scope.ids.map { it.id }.toSet().also { if (it.isEmpty()) forbidden() }
            else -> forbidden()
        }
        val params = mutableMapOf<String, Any>()
        val where = buildString {
            append(" WHERE 1=1")
            if (assigned != null) { append(" AND p.pharmacy_id IN (:scope)"); params["scope"] = assigned }
            if (status != null) { append(" AND p.status=:status"); params["status"] = status }
            if (pharmacyId != null) { append(" AND p.pharmacy_id=:pharmacy"); params["pharmacy"] = pharmacyId }
        }
        val rows = jdbc.query("""SELECT p.id,p.name,p.iin,p.phone,p.status,p.tier,p.pharmacy_id,p.pharmacy_name,p.city,
            p.receipts_30d,p.rules_accepted_30d,p.earned_30d,p.balance,p.courses_done,p.courses_total,p.joined_at,p.created_at,p.updated_at,
            (coalesce(length(p.password_hash),0)>0) AS has_password,h.chain_id,h.chain_name,h.city AS pharmacy_city,h.addr,h.district
            FROM pharmacists p LEFT JOIN pharmacies h ON h.id=p.pharmacy_id $where
            ORDER BY p.name,p.id LIMIT ${ReportWorkbook.MAX_ROWS + 1}""", params) { rs, _ ->
            val pharmacy = rs.getString("pharmacy_id")
            val personal = personalForPharmacy(pharmacy.orEmpty())
            val hasPassword = rs.getBoolean("has_password")
            PharmacistReportEntry(rs.getString("id"), rs.getString("name"), if (personal) rs.getString("iin") else null,
                if (personal) rs.getString("phone") else null, rs.getString("status"), rs.getString("tier"), hasPassword,
                if (hasPassword) "iin_password" else "phone_sms_or_activation", if (personal) rs.getString(if (hasPassword) "iin" else "phone") else null,
                if (hasPassword) "password_set" else "activation_required_or_sms", pharmacy, rs.getString("pharmacy_name"),
                rs.getString("chain_id"), rs.getString("chain_name"), rs.getString("city"), rs.getString("pharmacy_city"),
                rs.getString("addr"), rs.getString("district"), if (financialAll) rs.getInt("receipts_30d") else null,
                if (financialAll) rs.getInt("rules_accepted_30d") else null, if (financialAll) rs.getLong("earned_30d") else null,
                if (financialAll) rs.getLong("balance") else null, rs.getInt("courses_done"), rs.getInt("courses_total"),
                rs.getDate("joined_at").toString(), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant())
        }
        FinanceReportReader.ensureReportSize(rows.size)
        return PharmacistReport(Instant.now(), assigned, status, pharmacyId, rows, financialAll)
    }

    private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Для этой истории требуется явная допустимая область", HttpStatus.FORBIDDEN)
}
