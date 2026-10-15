package kz.epharm.reporting

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CampaignReportService(private val repository: CampaignReportRepository) {
    @Transactional(readOnly = true)
    fun report(id: String, from: LocalDate, to: LocalDate): CampaignReport {
        if (id.isBlank() || id.length>64 || from.year !in 1..9999 || to.year !in 1..9999 || to<from || ChronoUnit.DAYS.between(from,to)>=366) {
            throw AppException(ErrorCode.VALIDATION_FAILED,"Укажите кампанию и период от 1 до 366 дней",HttpStatus.BAD_REQUEST)
        }
        val campaign=repository.campaign(id)
            ?: throw AppException(ErrorCode.NOT_FOUND,"Кампания не найдена",HttpStatus.NOT_FOUND)
        val generated=Instant.now()
        val zone=ZoneId.of("Asia/Almaty")
        val start=from.atStartOfDay(zone).toInstant()
        val until=minOf(to.plusDays(1).atStartOfDay(zone).toInstant(),generated)
        val rows=if (start<until) repository.aggregate(id,start,until) else emptyList()
        fun sum(records: List<ReportAggregate>)=records.fold(ReportCounts()) { total,row -> total+row.counts }
        val days=generateSequence(from) { it.plusDays(1) }.takeWhile { it<=to }
            .map { day -> ReportDay(day,sum(rows.filter { it.date==day })) }.toList()
        return CampaignReport(campaign.id,campaign.title,campaign.brand,from,to,zone.id,generated,until,
            sum(rows),listOf("substitution" to "Замены","crosssell" to "Кросс-селл").map { (kind,label) ->
                ReportKind(kind,label,sum(rows.filter { it.kind==kind }))
            },days,METHODS)
    }

    companion object {
        val METHODS=listOf(
            "Подтверждённые показы: отдельные предложения с ACK кассы displayed_at в выбранном периоде; это не число окон и не число покупателей.",
            "Принято фармацевтом: outcome=accepted с decided_at до границы отчёта среди подтверждённых показов. Это действие фармацевта, не статус бизнес-одобрения и не продажа.",
            "Атрибутированные продажи: sold_at и sale_id связаны с реальным чеком той же аптеки и сессии; время printed_at совпадает. Один чек и предложенный товар учитываются не более одного раза в когорте.",
            "Одобрено замен: подтверждённые продажи рекомендованного товара в своём чеке, kind=substitution, независимо от двухминутного показателя. Продажа по рекомендации за 0–120 секунд включительно считается отдельно по displayed_at и printed_at чека. Отрицательные интервалы исключены из двухминутного показателя; clamped seconds_to_sale не используется.",
            "Выдано и без подтверждения считаются по shown_at выдачи сервером; их даты могут отличаться от дат подтверждённого показа. Без подтверждения: ACK отсутствует на границу отчёта.",
            "Результаты когорты ограничены концом выбранного периода, для незавершённого периода — временем формирования. Поздняя доставка событий и часы кассы могут изменить повторно сформированный отчёт.",
            "Связь с кампанией и подпись бренда взяты из текущих rules.promo_id и promos. Историческое переназначение/удаление правил не восстанавливается; immutable-принадлежность производителю не доказана.",
            "Непроверенные заявления о продаже исключены. Выручка, оплата, количество упаковок и эффект рекламы этим отчётом не подтверждаются. Персональные данные не включены.",
        )
    }
}
