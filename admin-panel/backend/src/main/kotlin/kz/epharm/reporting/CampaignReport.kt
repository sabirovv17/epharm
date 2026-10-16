package kz.epharm.reporting

import java.time.Instant
import java.time.LocalDate
import kotlin.math.round

data class ReportCounts(
    val issued: Long = 0,
    val unconfirmed: Long = 0,
    val confirmed: Long = 0,
    val pharmacistAccepted: Long = 0,
    val attributedSales: Long = 0,
    val unverifiedSaleClaims: Long = 0,
    val recommendationSalesUnder2m: Long = 0,
) {
    val attributedConversionPercent: Double?
        get() = if (confirmed == 0L) null else round(attributedSales * 1000.0 / confirmed) / 10
    val under2mConversionPercent: Double?
        get() = if (confirmed == 0L) null else round(recommendationSalesUnder2m * 1000.0 / confirmed) / 10
    operator fun plus(other: ReportCounts) = ReportCounts(
        issued + other.issued, unconfirmed + other.unconfirmed, confirmed + other.confirmed,
        pharmacistAccepted + other.pharmacistAccepted, attributedSales + other.attributedSales,
        unverifiedSaleClaims + other.unverifiedSaleClaims,
        recommendationSalesUnder2m + other.recommendationSalesUnder2m,
    )
}

data class ReportDay(val date: LocalDate, val counts: ReportCounts)
data class ReportKind(val kind: String, val label: String, val counts: ReportCounts)
data class CampaignReport(
    val campaignId: String,
    val campaignTitle: String,
    val brandLabel: String,
    val from: LocalDate,
    val to: LocalDate,
    val timezone: String,
    val generatedAt: Instant,
    val asOfExclusive: Instant,
    val totals: ReportCounts,
    val kinds: List<ReportKind>,
    val days: List<ReportDay>,
    val methods: List<String>,
) {
    val confirmedSubstitutionSales: Long
        get() = kinds.firstOrNull { it.kind == "substitution" }?.counts?.attributedSales ?: 0
    val confirmedSubstitutionSalesUnder2m: Long
        get() = kinds.firstOrNull { it.kind=="substitution" }?.counts?.recommendationSalesUnder2m ?: 0
}
