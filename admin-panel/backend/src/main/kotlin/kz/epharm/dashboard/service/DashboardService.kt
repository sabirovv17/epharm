package kz.epharm.dashboard.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.dto.DashboardSummaryDto
import kz.epharm.dashboard.dto.TopPharmacyDto
import kz.epharm.dashboard.dto.TopProductDto
import kz.epharm.dashboard.dto.TopRuleDto
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.lift.service.LiftStatistics
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.repository.PharmacyAggRow
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.repository.RuleRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сводка KPI для обзорного экрана HQ (ТЗ §3.2 — Dashboard). РЕАЛЬНЫЕ данные:
 *  - показы/принятия/convRate и топы правил/товаров/аптек — из recommendation_events;
 *  - выплаты (paidOut/pendingPayout) — из payout_batches;
 *  - avgLiftPct — из [LiftService] (конверсия pilot vs control).
 *
 * Метрики считаются за окно `app.analytics.window-days` (по умолчанию 90 дней).
 * Денормализованные поля rules/pharmacies больше НЕ читаются (они не наполнялись событиями).
 */
@Service
class DashboardService(
    private val ruleRepository: RuleRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val payoutBatchRepository: PayoutBatchRepository,
    private val productRepository: ProductRepository,
    private val recommendationEventRepository: RecommendationEventRepository,
    @Value("\${app.analytics.window-days:90}") private val windowDays: Long,
) {

    @Transactional(readOnly = true)
    fun summary(): DashboardSummaryDto {
        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)

        // Правила — для статусов и привязки ruleId → рекомендованный товар (recommend).
        val rules = ruleRepository.findAllByOrderByUpdatedAtDesc()
        val liveRules = rules.filter { it.status != RuleStatus.archived }
        val ruleById = liveRules.associateBy { it.id }

        // Реальные агрегаты из таблицы событий.
        val ruleAgg = recommendationEventRepository.aggregateByRuleSince(since)
            .filter { it.ruleId in ruleById } // только живые правила
        val impressions = ruleAgg.sumOf { it.impressions }.toInt()
        val accepts = ruleAgg.sumOf { it.accepts }.toInt()
        val convRate = if (impressions > 0) round1(accepts * 100.0 / impressions) else 0.0

        // Выплаты — реальные данные.
        val batches = payoutBatchRepository.findAllByOrderByCreatedAtDesc()
        val paidOut = batches.filter { it.status == PayoutBatchStatus.approved }.sumOf { it.amount }
        val pendingPayout = batches.filter { it.status == PayoutBatchStatus.pending }.sumOf { it.amount }

        val pharmacies = pharmacyRepository.findAllByOrderByNameAsc()
        val pilot = pharmacies.filter { it.group == PharmacyGroup.pilot }
        val pharmacyById = pharmacies.associateBy { it.id }

        // Агрегат по аптеке считаем ОДИН раз — для avgLiftPct и топа аптек
        // (не зовём весь LiftService.summary(): он сделал бы тот же запрос повторно).
        val pharmacyAgg = recommendationEventRepository.aggregateByPharmacySince(since)
        val aggById = pharmacyAgg.associateBy { it.pharmacyId }
        val avgLiftPct = LiftStatistics.compute(
            pilot = liftGroup(pilot, aggById),
            control = liftGroup(pharmacies.filter { it.group == PharmacyGroup.control }, aggById),
        ).liftPct

        // Названия товаров грузим точечно (только для топов), не весь каталог.
        val skuAgg = recommendationEventRepository.aggregateBySkuSince(since)
        val neededProductIds = (ruleAgg.mapNotNull { ruleById[it.ruleId]?.recommend } +
            skuAgg.map { it.sku }).toSet()
        val productNames = if (neededProductIds.isEmpty()) {
            emptyMap()
        } else {
            productRepository.findAllById(neededProductIds).associate { it.id to it.name }
        }

        val topRules = ruleAgg
            .filter { it.impressions > 0 }
            .map {
                val rule = ruleById[it.ruleId]
                val cr = round1(it.accepts * 100.0 / it.impressions)
                val label = rule?.recommend?.let { sku -> productNames[sku] ?: sku } ?: it.ruleId
                TopRuleDto(id = it.ruleId, label = label, convRate = cr, accepts = it.accepts.toInt())
            }
            .sortedByDescending { it.convRate }
            .take(5)

        val topProducts = skuAgg
            .filter { it.revenue > 0 || it.accepts > 0 }
            .map {
                TopProductDto(
                    id = it.sku,
                    name = it.name.ifBlank { productNames[it.sku] ?: it.sku },
                    revenue = it.revenue,
                )
            }
            .sortedByDescending { it.revenue }
            .take(5)

        val topPharmacies = pharmacyAgg
            .filter { it.accepts > 0 }
            .mapNotNull { row ->
                val ph = pharmacyById[row.pharmacyId] ?: return@mapNotNull null
                TopPharmacyDto(id = ph.id, name = ph.name, city = ph.city, rulesAccepted = row.accepts.toInt())
            }
            .sortedByDescending { it.rulesAccepted }
            .take(5)

        return DashboardSummaryDto(
            impressions = impressions,
            accepts = accepts,
            convRate = convRate,
            activeRules = liveRules.count { it.status == RuleStatus.active },
            totalRules = liveRules.size,
            paidOut = paidOut,
            pendingPayout = pendingPayout,
            pilotPharmacies = pilot.size,
            avgLiftPct = avgLiftPct,
            topRules = topRules,
            topPharmacies = topPharmacies,
            topProducts = topProducts,
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Суммарные показы/принятия группы аптек (pilot/control) для расчёта lift. */
    private fun liftGroup(list: List<PharmacyEntity>, aggById: Map<String, PharmacyAggRow>): LiftStatistics.Group {
        var impressions = 0L
        var accepts = 0L
        for (ph in list) {
            val a = aggById[ph.id] ?: continue
            impressions += a.impressions
            accepts += a.accepts
        }
        return LiftStatistics.Group(impressions = impressions, accepts = accepts)
    }
}
