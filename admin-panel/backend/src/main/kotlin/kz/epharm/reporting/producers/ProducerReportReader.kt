package kz.epharm.reporting.producers

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kz.epharm.auth.access.AdministrativeScope
import kz.epharm.auth.access.UnifiedAdminCatalog
import kz.epharm.reporting.*
import kz.epharm.reporting.exports.ReportPeriod
import kz.epharm.reporting.xlsx.ReportWorkbook
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ProducerReportReader(private val jdbc: JdbcTemplate, private val campaigns: CampaignReportRepository,
    private val access: AdministrativeScope, private val catalog: UnifiedAdminCatalog) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun index(): ProducerReportIndex {
        requireView()
        val producers=jdbc.query("""SELECT p.id,p.name,p.active,(SELECT count(*) FROM promo_producer_bindings b
            WHERE b.producer_id=p.id AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=b.id)) AS bindings
            FROM merch_producers p ORDER BY p.id LIMIT 50001""", { rs,_ -> ProducerReportChoice(rs.getObject("id",UUID::class.java),
            rs.getString("name"),rs.getBoolean("active"),!rs.getString("name").isNullOrBlank(),rs.getLong("bindings")) })
        val unbound=jdbc.query("""SELECT p.id,p.title FROM promos p WHERE NOT EXISTS(SELECT 1 FROM promo_producer_bindings b
            WHERE b.promo_id=p.id AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=b.id))
            ORDER BY p.id LIMIT 50001""", { rs,_ -> UnboundCampaign(rs.getString("id"),rs.getString("title")) })
        if(producers.size>50_000 || unbound.size>50_000) tooLarge()
        return ProducerReportIndex(producers,unbound)
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun read(producerId:UUID,period:ReportPeriod): ProducerMotivationReport {
        requireView()
        val producer=jdbc.queryForList("SELECT name FROM merch_producers WHERE id=?",producerId).singleOrNull()
            ?:throw AppException(ErrorCode.NOT_FOUND,"Производитель не найден",HttpStatus.NOT_FOUND)
        val name=producer["name"] as? String
        val generated=Instant.now();val until=period.end(generated);val zone=ZoneId.of(period.timezone)
        val bindings=jdbc.query("""SELECT id,promo_id,revision,whole_history,date_from,date_to FROM promo_producer_bindings b
            WHERE producer_id=? AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=b.id)
            AND (whole_history OR (date_from<=? AND date_to>=?)) ORDER BY promo_id,revision LIMIT 50001""", { rs,_ ->
            val start=rs.getDate("date_from")?.toLocalDate();val end=rs.getDate("date_to")?.toLocalDate()
            rs.getString("promo_id") to ProducerReportInterval(rs.getObject("id",UUID::class.java),rs.getLong("revision"),rs.getBoolean("whole_history"),
                start,end,maxOf(start?:period.from,period.from),minOf(end?:period.to,period.to))
        },producerId,period.to,period.from)
        if(bindings.size>50_000) tooLarge()
        val grouped=bindings.groupBy({it.first},{it.second})
        val days=generateSequence(period.from) { it.plusDays(1) }.takeWhile { it<=period.to }.toList()
        // Bound materialization before cohort queries and before the shared workbook admission/allocation.
        if(grouped.size.toLong()*(days.size+3)*12 + bindings.size*8L + days.size*10L + 500 > ReportWorkbook.MAX_CELLS) tooLarge()
        val results=grouped.map { (id,intervals) ->
            assertUnambiguous(id)
            val campaign=campaigns.campaign(id) ?:throw AppException(ErrorCode.CONFLICT,"Связанная кампания отсутствует",HttpStatus.CONFLICT)
            val rows=campaigns.aggregateIntervals(id,intervals.map { ReportTimeInterval(it.includedFrom.atStartOfDay(zone).toInstant(),
                it.includedTo.plusDays(1).atStartOfDay(zone).toInstant()) },until)
            fun sum(records:List<ReportAggregate>)=records.fold(ReportCounts()) { total,row -> total+row.counts }
            val report=CampaignReport(campaign.id,campaign.title,campaign.brand,period.from,period.to,period.timezone,generated,until,
                sum(rows),KINDS.map { (kind,label) -> ReportKind(kind,label,sum(rows.filter { it.kind==kind })) },
                days.map { day -> ReportDay(day,sum(rows.filter { it.date==day })) },methods())
            ProducerCampaignResult(report,intervals)
        }
        fun sumCounts(values:List<ReportCounts>)=values.fold(ReportCounts()) { total,row -> total+row }
        return ProducerMotivationReport(producerId,name,!name.isNullOrBlank(),period.from,period.to,period.timezone,generated,until,results,
            sumCounts(results.map { it.report.totals }),KINDS.map { (kind,label) -> ReportKind(kind,label,
                sumCounts(results.map { result -> result.report.kinds.first { it.kind==kind }.counts })) },days.map { day ->
                ReportDay(day,sumCounts(results.map { result -> result.report.days.first { it.date==day }.counts })) },methods())
    }

    private fun assertUnambiguous(id:String) {
        val overlap=jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM promo_producer_bindings a JOIN promo_producer_bindings b
            ON a.promo_id=b.promo_id AND a.id<b.id WHERE a.promo_id=?
            AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=a.id)
            AND NOT EXISTS(SELECT 1 FROM promo_producer_binding_retractions r WHERE r.binding_id=b.id)
            AND (a.whole_history OR b.whole_history OR (a.date_from<=b.date_to AND a.date_to>=b.date_from)))""",Boolean::class.java,id)!!
        if(overlap) throw AppException(ErrorCode.CONFLICT,"Противоречащие связи кампании требуют явной сверки HQ",HttpStatus.CONFLICT)
    }
    private fun requireView() { access.requireExplicitPermission(catalog.campaignReportView);access.requireAllPermission(catalog.campaignReportView) }
    private fun methods()=CampaignReportService.METHODS.filterNot { it.startsWith("Связь с кампанией и подпись бренда") } + listOf(
        "Включены только действующие явные связи производителя по устойчивым ID; показы/выдачи ограничены пересечением подтверждённых интервалов и выбранного периода. Отозванные связи исключены, несвязанные акции не угаданы по бренду или автору.",
        "Продажи и outcomes учитываются до общей границы отчёта по методике исходной кампании; продажа после конца интервала связи не теряется, если её показ внутри интервала. sale_id и SKU дедуплицируются один раз среди всех включённых интервалов одной кампании.",
        "Итоги складывают абсолютные показатели кампаний; конверсия рассчитана заново по суммарному числу подтверждённых показов. Один чек может законно относиться к разным кампаниям; итоги не являются числом уникальных чеков сети.",
        "Связь события с кампанией по текущим rules.promo_id не восстанавливает историческое переназначение или удаление правил. Названия кампаний, брендов и компании текущие; raw основания проверки, персональные и банковские реквизиты исключены.",
    )
    private fun tooLarge():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Отчёт превышает лимит XLSX: сузьте период; результат не усечён",HttpStatus.PAYLOAD_TOO_LARGE)
    companion object { private val KINDS=listOf("substitution" to "Замены","crosssell" to "Кросс-селл") }
}
