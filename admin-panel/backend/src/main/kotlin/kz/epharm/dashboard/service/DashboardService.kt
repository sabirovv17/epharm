package kz.epharm.dashboard.service

import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.dto.DashboardSummaryDto
import kz.epharm.dashboard.dto.TopPharmacyDto
import kz.epharm.dashboard.dto.TopProductDto
import kz.epharm.dashboard.dto.TopRuleDto
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.pharmacies.entity.PharmacyGroup
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.repository.RuleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DashboardService(
    private val ruleRepository: RuleRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val payoutBatchRepository: PayoutBatchRepository,
    private val productRepository: ProductRepository,
) {

    @Transactional(readOnly = true)
    fun summary(): DashboardSummaryDto {
        val rules = ruleRepository.findAllByOrderByUpdatedAtDesc()
        val liveRules = rules.filter { it.status != RuleStatus.archived }
        val productNames = productRepository.findAll().associate { it.id to it.name }

        val impressions = liveRules.sumOf { it.impressions }
        val accepts = liveRules.sumOf { it.accepts }
        val convRate = if (impressions > 0) round1(accepts * 100.0 / impressions) else 0.0

        val batches = payoutBatchRepository.findAllByOrderByCreatedAtDesc()
        val paidOut = batches.filter { it.status == PayoutBatchStatus.approved }.sumOf { it.amount }
        val pendingPayout = batches.filter { it.status == PayoutBatchStatus.pending }.sumOf { it.amount }

        val pharmacies = pharmacyRepository.findAllByOrderByNameAsc()
        val pilot = pharmacies.filter { it.group == PharmacyGroup.pilot }
        val avgLiftPct = if (pilot.isNotEmpty()) {
            round1(pilot.sumOf { it.liftPct.toDouble() } / pilot.size)
        } else 0.0

        val topRules = liveRules
            .sortedByDescending { it.convRate }
            .take(5)
            .map {
                TopRuleDto(
                    id = it.id,
                    label = productNames[it.recommend] ?: it.recommend,
                    convRate = round1(it.convRate.toDouble()),
                    accepts = it.accepts,
                )
            }

        val topPharmacies = pharmacies
            .sortedByDescending { it.rulesAccepted }
            .take(5)
            .map { TopPharmacyDto(id = it.id, name = it.name, city = it.city, rulesAccepted = it.rulesAccepted) }

        val topProducts = liveRules
            .groupBy { it.recommend }
            .map { (pid, rs) -> TopProductDto(id = pid, name = productNames[pid] ?: pid, revenue = rs.sumOf { it.revenue }) }
            .sortedByDescending { it.revenue }
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
}
