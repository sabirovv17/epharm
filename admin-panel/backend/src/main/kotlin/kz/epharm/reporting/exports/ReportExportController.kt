package kz.epharm.reporting.exports

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.auth.policy.PermissionKey
import kz.epharm.auth.policy.PharmacyScope
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.reporting.xlsx.CampaignReportWorkbook
import kz.epharm.reporting.xlsx.FinanceReportWorkbooks
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.identity.CanonicalEntityType
import kz.epharm.shared.identity.CanonicalIdentity
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** New exports require explicit verified access, including for a legacy-role account. */
@RestController
@RequestMapping("/api/admin/reports")
class ReportExportController(
    private val access: AdministrativeScope,
    private val catalog: UnifiedAdminCatalog,
    private val finance: FinanceReportReader,
    private val directory: DirectoryPayoutReportReader,
    private val campaigns: CampaignExportReader,
    private val workbooks: FinanceReportWorkbooks,
    private val campaignWorkbook: CampaignReportWorkbook,
    private val json: ObjectMapper,
) {
    @GetMapping("/ledger")
    fun ledger(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) source: String?, @RequestParam(required = false) pharmacistId: String?): ResponseEntity<LedgerReport> {
        access.requireExplicitPermission(key("motivation", "accruals", "view"))
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(finance.ledger(ReportPeriod(from, to), trustedScope(), source, pharmacistId))
    }

    @GetMapping("/ledger.xlsx", produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun ledgerXlsx(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) source: String?, @RequestParam(required = false) pharmacistId: String?,
        @AuthenticationPrincipal actor: AdminPrincipal): ResponseEntity<ByteArray> {
        requireExport("motivation", "accruals")
        val report = finance.ledger(ReportPeriod(from, to), trustedScope(), source, pharmacistId)
        return attachment("ledger-$from-$to.xlsx", workbooks.ledger(report), actor, "ledger", report.entries.size,
            mapOf("from" to from.toString(), "to" to to.toString(), "source" to source, "pharmacistId" to pharmacistId))
    }

    @GetMapping("/payouts.xlsx", produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun payoutsXlsx(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) status: PayoutBatchStatus?, @AuthenticationPrincipal actor: AdminPrincipal): ResponseEntity<ByteArray> {
        requireExport("motivation", "payouts")
        val report = directory.payouts(ReportPeriod(from, to), trustedScope(), status)
        return attachment("payouts-$from-$to.xlsx", workbooks.payouts(report), actor, "payouts", report.batches.size,
            mapOf("from" to from.toString(), "to" to to.toString(), "status" to status?.name))
    }

    @GetMapping("/pharmacists.xlsx", produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun pharmacistsXlsx(@RequestParam(required = false) status: String?, @RequestParam(required = false) pharmacyId: String?,
        @AuthenticationPrincipal actor: AdminPrincipal): ResponseEntity<ByteArray> {
        requireExport("pharmacists", "registry")
        val report = directory.pharmacists(trustedScope(), { access.canPharmacyPermission(catalog.sensitive, it) },
            access.canAllPermission(catalog.financial), status, pharmacyId)
        return attachment("pharmacists.xlsx", workbooks.pharmacists(report), actor, "pharmacists", report.entries.size,
            mapOf("status" to status, "pharmacyId" to pharmacyId))
    }

    @GetMapping("/campaigns/{promoId}/xlsx", produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun campaignXlsx(@PathVariable promoId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @AuthenticationPrincipal actor: AdminPrincipal): ResponseEntity<ByteArray> {
        requireExport("analytics", "campaign_reports")
        access.requireAllPermission(key("analytics", "campaign_reports", "view"))
        val report = campaigns.read(promoId, from, to)
        return attachment("campaign-report-$from-$to.xlsx", campaignWorkbook.render(report.report, report.pharmacies), actor, "campaign", report.report.days.size,
            mapOf("promoId" to promoId, "from" to from.toString(), "to" to to.toString()))
    }

    private fun requireExport(module: String, subsection: String) {
        access.requireExplicitPermission(key(module, subsection, "export"))
        access.requireExplicitPermission(key(module, subsection, "view"))
    }
    private fun trustedScope(): PharmacyScope = access.currentPharmacyIds()?.let { ids ->
        PharmacyScope.Assigned(ids.map { CanonicalIdentity(CanonicalEntityType.PHARMACY, it) })
    } ?: PharmacyScope.All

    private fun attachment(filename: String, bytes: ByteArray, actor: AdminPrincipal, report: String, rows: Int,
        filters: Map<String, String?>): ResponseEntity<ByteArray> {
        // Metadata only: no names, credentials, cell values, context keys or file contents.
        log.info("report_export actor={} report={} rows={} bytes={} scopePharmacies={} filters={}", actor.userId, report, rows, bytes.size,
            access.currentPharmacyIds()?.sorted() ?: "ALL", json.writeValueAsString(filters))
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ReportWorkbook.MIME))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .header("X-Content-Type-Options", "nosniff").body(bytes)
    }

    private fun key(module: String, subsection: String, action: String): PermissionKey = UnifiedAdminCatalog.key(module, subsection, action)
    private val log = LoggerFactory.getLogger(javaClass)
}
