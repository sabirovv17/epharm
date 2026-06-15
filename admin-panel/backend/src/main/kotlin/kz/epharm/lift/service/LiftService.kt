package kz.epharm.lift.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.lift.dto.LiftSegmentDto
import kz.epharm.lift.dto.LiftSummaryDto
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.ChainRepository
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Lift-аналитика (ТЗ §3.2). РЕАЛЬНЫЕ данные вместо прежнего «среднего поля liftPct»:
 *  - конверсия аптеки = принятые рекомендации / показы (recommendation_events за окно);
 *  - networkLift = (convPilot − convControl)/convControl × 100, pValue — z-тест пропорций;
 *  - сегменты — lift каждой пилотной сети относительно общего контроля;
 *  - receipts30d — реальные чеки кассы (pos_sales) за 30 дней.
 *
 * Когда нет показов в pilot и/или control (кассы не размечены на группы) —
 * insufficientData=true, pValue=null: не выдаём фейковую статзначимость.
 */
@Service
class LiftService(
    private val pharmacyRepository: PharmacyRepository,
    private val chainRepository: ChainRepository,
    private val recommendationEventRepository: RecommendationEventRepository,
    private val posSaleRepository: PosSaleRepository,
    @Value("\${app.analytics.window-days:90}") private val windowDays: Long,
) {

    @Transactional(readOnly = true)
    fun summary(): LiftSummaryDto {
        val pharmacies = pharmacyRepository.findAllByOrderByNameAsc()
        val pilot = pharmacies.filter { it.group == PharmacyGroup.pilot }
        val control = pharmacies.filter { it.group == PharmacyGroup.control }
        val rolled = pharmacies.filter { it.group == PharmacyGroup.rolled }

        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)
        // Агрегаты показ/принятие по аптеке (реальные события POSM).
        val agg = recommendationEventRepository.aggregateByPharmacySince(since)
            .associate { it.pharmacyId to (it.impressions to it.accepts) }

        val pilotGroup = groupOf(pilot, agg)
        val controlGroup = groupOf(control, agg)
        val stat = LiftStatistics.compute(pilotGroup, controlGroup)

        val chainNames = chainRepository.findAll().associate { it.id to it.name }
        // Сегменты — lift каждой пилотной сети относительно общего контроля.
        val segments = pilot
            .groupBy { it.chainId }
            .map { (chainId, list) ->
                val seg = LiftStatistics.compute(groupOf(list, agg), controlGroup)
                LiftSegmentDto(
                    name = chainNames[chainId] ?: chainId,
                    liftPct = seg.liftPct,
                    pharmacies = list.size,
                )
            }
            .sortedByDescending { it.liftPct }

        // Реальные чеки кассы за 30 дней по группам.
        val since30d = Instant.now().minus(30, ChronoUnit.DAYS)
        val pilotReceipts = receiptsOf(pilot, since30d)
        val controlReceipts = receiptsOf(control, since30d)

        return LiftSummaryDto(
            networkLiftPct = stat.liftPct,
            pValue = stat.pValue,
            insufficientData = !stat.sufficient,
            pilotCount = pilot.size,
            controlCount = control.size,
            rolledCount = rolled.size,
            pilotReceipts30d = pilotReceipts,
            controlReceipts30d = controlReceipts,
            segments = segments,
        )
    }

    private fun groupOf(
        list: List<PharmacyEntity>,
        agg: Map<String, Pair<Long, Long>>,
    ): LiftStatistics.Group {
        var impressions = 0L
        var accepts = 0L
        for (ph in list) {
            val a = agg[ph.id] ?: continue
            impressions += a.first
            accepts += a.second
        }
        return LiftStatistics.Group(impressions = impressions, accepts = accepts)
    }

    private fun receiptsOf(list: List<PharmacyEntity>, since: Instant): Int {
        if (list.isEmpty()) return 0
        return posSaleRepository.countByPharmaciesSince(list.map { it.id }, since).toInt()
    }
}
