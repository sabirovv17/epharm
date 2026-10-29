package kz.epharm.reporting.exports

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kz.epharm.auth.policy.PharmacyScope
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.reporting.xlsx.ReportWorkbook
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class ReportPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        if (from.year !in 1..9999 || to.year !in 1..9999 || to < from || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Укажите период от 1 до 366 дней")
        }
    }
    val timezone: String get() = "Asia/Almaty"
    fun start(): Instant = from.atStartOfDay(ZoneId.of(timezone)).toInstant()
    fun end(generated: Instant): Instant = minOf(to.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant(), generated)
}

data class LedgerReportEntry(
    val id: String, val pharmacistId: String, val pharmacistName: String,
    val source: String, val sourceId: String, val amountKzt: Long, val earnedDeltaKzt: Long,
    val currency: String, val contextKey: String, val balanceAfterKzt: Long?, val createdAt: Instant,
    val historicalPharmacyId: String?, val historicalPharmacyName: String?, val historicalPharmacyStatus: String,
    val basisStatus: String?, val state: String = "posted",
)
data class LedgerSourceTotal(val source: String, val entries: Int, val amountKzt: Long, val earnedDeltaKzt: Long)
data class LedgerReport(
    val from: LocalDate, val to: LocalDate, val timezone: String, val generatedAt: Instant, val asOfExclusive: Instant,
    val sourceFilter: String?, val pharmacistFilter: String?, val scopePharmacyIds: Set<String>?,
    val entries: List<LedgerReportEntry>, val totals: List<LedgerSourceTotal>,
    val unresolvedHistoricalRows: Int?, val historicalScopeNotice: String,
    val coverage: String = "Только проводки wallet ledger с момента его подключения; старый баланс и основания не превращены в новые начисления",
)

/** Reads each monetary posting once; source tables supply provenance, never a second money sum. */
@Service
class FinanceReportReader(private val jdbc: NamedParameterJdbcTemplate) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun ledger(period: ReportPeriod, scope: PharmacyScope, source: String? = null, pharmacistId: String? = null): LedgerReport {
        if (source != null && source !in SOURCES || pharmacistId != null &&
            (pharmacistId.isBlank() || pharmacistId.length > 64 || pharmacistId.any(Char::isISOControl))) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Некорректный источник или ID фармацевта")
        }
        val assigned = when (scope) {
            PharmacyScope.All -> null
            is PharmacyScope.Assigned -> scope.ids.map { it.id }.toSet().also { if (it.isEmpty()) forbidden() }
            else -> forbidden()
        }
        val generated = Instant.now()
        val end = period.end(generated)
        val params = mutableMapOf<String, Any>("since" to Timestamp.from(period.start()), "until" to Timestamp.from(end))
        val restrictions = buildString {
            if (source != null) { append(" AND w.source=:source"); params["source"] = source }
            if (pharmacistId != null) { append(" AND w.pharmacist_id=:pharmacist"); params["pharmacist"] = pharmacistId }
        }
        val merchConflict = "(m.event_id IS NOT NULL AND nt.dispatch_id IS NOT NULL AND m.pharmacy_id IS DISTINCT FROM nt.historical_pharmacy_id)"
        val historical = "CASE WHEN w.source='receipt_bonus' THEN nullif(pb.pharmacy_id,'') WHEN w.source='merch_task' THEN " +
            "CASE WHEN $merchConflict THEN NULL WHEN nt.dispatch_id IS NOT NULL THEN nullif(nt.historical_pharmacy_id,'') ELSE nullif(m.pharmacy_id,'') END END"
        val scopeSql = if (assigned == null) "" else " AND $historical IN (:pharmacies)".also { params["pharmacies"] = assigned }
        val rows = jdbc.query("""
            SELECT w.id,w.pharmacist_id,p.name AS pharmacist_name,w.source,w.source_id,w.amount_kzt,w.earned_delta_kzt,
                w.currency,w.context_key,w.balance_after_kzt,w.created_at,$historical AS historical_pharmacy_id,
                h.name AS historical_pharmacy_name,$merchConflict AS historical_conflict,
                CASE WHEN w.source='receipt_bonus' THEN pb.status WHEN w.source='training_bonus' THEN tr.status
                    WHEN w.source='merch_task' AND nt.dispatch_id IS NOT NULL THEN nt.state
                    WHEN w.source='merch_task' AND m.event_id IS NOT NULL THEN 'approved'
                    WHEN w.source='payout_reservation' THEN b.status END AS basis_status
            FROM pharmacist_wallet_entries w JOIN pharmacists p ON p.id=w.pharmacist_id
            LEFT JOIN pending_bonuses pb ON w.source='receipt_bonus' AND pb.id=w.source_id AND pb.pharmacist_id=w.pharmacist_id
            LEFT JOIN merch_reward_events m ON w.source='merch_task' AND m.wallet_entry_id=w.id
                AND m.dispatch_id::text=w.source_id AND m.pharmacist_id=w.pharmacist_id
            LEFT JOIN merch_task_rewards nt ON w.source='merch_task' AND nt.wallet_entry_id=w.id
                AND nt.dispatch_id::text=w.source_id AND nt.pharmacist_id=w.pharmacist_id AND nt.amount_kzt=w.amount_kzt
                AND nt.state IN ('credited','already_credited')
            LEFT JOIN training_rewards tr ON w.source='training_bonus' AND tr.assignment_id::text=w.source_id AND tr.pharmacist_id=w.pharmacist_id
            LEFT JOIN payout_items pi ON w.source='payout_reservation' AND pi.id=w.source_id AND pi.pharmacist_id=w.pharmacist_id
            LEFT JOIN payout_batches b ON b.id=pi.batch_id
            LEFT JOIN pharmacies h ON h.id=($historical)
            WHERE w.created_at>=:since AND w.created_at<:until $restrictions $scopeSql
            ORDER BY w.created_at,w.id LIMIT ${ReportWorkbook.MAX_ROWS + 1}
        """.trimIndent(), params) { rs, _ ->
            val pharmacy = rs.getString("historical_pharmacy_id")
            LedgerReportEntry(rs.getString("id"), rs.getString("pharmacist_id"), rs.getString("pharmacist_name"),
                rs.getString("source"), rs.getString("source_id"), rs.getLong("amount_kzt"), rs.getLong("earned_delta_kzt"),
                rs.getString("currency"), rs.getString("context_key"), if (assigned == null) rs.getLong("balance_after_kzt") else null,
                rs.getTimestamp("created_at").toInstant(), pharmacy, rs.getString("historical_pharmacy_name"),
                if (rs.getBoolean("historical_conflict")) "conflicting_source_bases" else if (pharmacy == null) "not_recorded_in_source" else "source_basis",
                rs.getString("basis_status"))
        }
        ensureReportSize(rows.size)
        val totals = rows.groupBy { it.source }.map { (key, entries) -> LedgerSourceTotal(key, entries.size,
            exactSum(entries.map { it.amountKzt }), exactSum(entries.map { it.earnedDeltaKzt })) }
        return LedgerReport(period.from, period.to, period.timezone, generated, end, source, pharmacistId, assigned, rows, totals,
            if (assigned == null) rows.count { it.historicalPharmacyId == null } else null,
            if (assigned == null) "Неизвестная или противоречащая историческая аптека сохранена как null; текущая привязка её не заменяет"
            else "Только основания с доказанной исторической аптекой в выданной области; иная/неизвестная история и глобальный баланс не раскрываются")
    }

    companion object {
        val SOURCES = setOf("receipt_bonus", "training_bonus", "merch_task", "admin_adjustment", "payout_reservation")
        fun ensureReportSize(size: Int) { if (size > ReportWorkbook.MAX_ROWS) throw AppException(ErrorCode.VALIDATION_FAILED,
            "Больше ${ReportWorkbook.MAX_ROWS} строк: сузьте фильтры; результат не усечён", HttpStatus.PAYLOAD_TOO_LARGE) }
        fun exactSum(values: List<Long>): Long = try { values.fold(0L, Math::addExact) } catch (_: ArithmeticException) {
            throw AppException(ErrorCode.CONFLICT, "Итог отчёта выходит за допустимый диапазон; исходные суммы сохранены", HttpStatus.CONFLICT)
        }
        private fun forbidden(): Nothing = throw AppException(ErrorCode.FORBIDDEN, "Не задана область отчёта", HttpStatus.FORBIDDEN)
    }
}
