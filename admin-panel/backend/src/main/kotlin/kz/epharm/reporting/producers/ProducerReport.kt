package kz.epharm.reporting.producers

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.reporting.CampaignReport
import kz.epharm.reporting.ReportCounts
import kz.epharm.reporting.ReportDay
import kz.epharm.reporting.ReportKind

data class ProducerReportChoice(val id: UUID, val name: String?, val active: Boolean, val profileComplete: Boolean, val activeBindingCount: Long)
data class UnboundCampaign(val id: String, val title: String)
data class ProducerReportIndex(val producers: List<ProducerReportChoice>, val unboundCampaigns: List<UnboundCampaign>,
    val notice: String = "Название бренда, автор и совпадение названий не доказывают производителя; неполные периоды видны в истории связей")
data class ProducerReportInterval(val bindingId: UUID, val revision: Long, val wholeHistory: Boolean,
    val approvedFrom: LocalDate?, val approvedTo: LocalDate?, val includedFrom: LocalDate, val includedTo: LocalDate)
data class ProducerCampaignResult(val report: CampaignReport, val intervals: List<ProducerReportInterval>)
data class ProducerMotivationReport(val producerId: UUID, val producerName: String?, val profileComplete: Boolean,
    val from: LocalDate, val to: LocalDate, val timezone: String, val generatedAt: Instant, val asOfExclusive: Instant,
    val campaigns: List<ProducerCampaignResult>, val totals: ReportCounts, val kinds: List<ReportKind>, val days: List<ReportDay>,
    val methods: List<String>)
