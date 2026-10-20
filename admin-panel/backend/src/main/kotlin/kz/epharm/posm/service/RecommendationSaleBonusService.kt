package kz.epharm.posm.service

import java.time.Duration
import java.time.Instant
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.entity.RecommendationOutcome
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.receipts.service.ReconcileService
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** An actual display plus an actual print may reserve a bonus, never accept a recommendation or credit money. */
@Service
class RecommendationSaleBonusService(
    private val events: RecommendationEventRepository,
    private val sales: PosSaleRepository,
    private val pending: PendingBonusRepository,
    private val pharmacists: PharmacistRepository,
    private val rulesEngine: RulesEngineService,
    private val pendingBonusService: PendingBonusService,
    private val reconcile: ReconcileService,
    private val jdbc: JdbcTemplate,
    @Value("\${app.posm.sale-bonus.enabled:false}") private val enabled: Boolean,
    @Value("\${app.posm.sale-bonus.eligible-after:}") eligibleAfterRaw: String,
) {
    private val eligibleAfter = eligibleAfterRaw.trim().takeIf(String::isNotEmpty)?.let(Instant::parse)

    init {
        require(!enabled || eligibleAfter != null) { "Enabling sale bonuses requires an explicit activation cutoff" }
    }

    @Transactional
    fun afterDisplay(event: RecommendationEventEntity) {
        if (!enabled || event.displayedAt == null) return
        sales.findByPharmacyIdAndSessionIdOrderByCreatedAtAsc(event.pharmacyId, event.sessionId)
            .forEach(::afterSale)
    }

    /** Acquire before analytics reads managed events, otherwise a concurrent ACK could be overwritten. */
    fun beforeSale(sessionId: String?) {
        if (enabled && !sessionId.isNullOrBlank()) lockSession(sessionId)
    }

    @Transactional
    fun afterSale(sale: PosSaleEntity) {
        val cutoff = eligibleAfter ?: return
        val latestEvidenceAt = Instant.now().plusSeconds(300)
        if (!enabled || sale.captureSource != "standardn-print-log" || sale.totalAmount <= 0 ||
            sale.printedAt.isBefore(cutoff) || sale.printedAt.isAfter(latestEvidenceAt) ||
            sale.pharmacyId.isBlank() || sale.sessionId.isNullOrBlank() ||
            sale.pharmacistId.isBlank() || sale.pharmacistSource !in TRUSTED_SELLER_SOURCES) return
        val pharmacist = pharmacists.findById(sale.pharmacistId).orElse(null) ?: return
        if (pharmacist.status != PharmacistStatus.active || pharmacist.pharmacyId != sale.pharmacyId) return

        // The same lock as explicit /outcome: accepted-click and print/late-ACK cannot double reserve.
        lockSession(sale.sessionId!!)
        val sessionEvents = events.findAllBySessionId(sale.sessionId!!)
            .filter { it.pharmacyId == sale.pharmacyId && it.pharmacistId == sale.pharmacistId }
        if (sessionEvents.isEmpty()) return
        val cart = sale.items.map { CartItemDto(sku = it.sku, barcode = it.barcode, name = it.name, qty = it.qty) }
        val strongIds = rulesEngine.resolveStrongProductIds(cart)
        val soldSkus = sale.items.indices.filter { strongIds[it] != null }.groupBy { strongIds[it]!! }
            .filterValues { indexes -> indexes.all { index ->
                sale.items[index].qty.isFinite() && sale.items[index].qty > 0 &&
                    sale.items[index].price > 0 && sale.items[index].total > 0
            } }.keys
        if (soldSkus.isEmpty()) return

        sessionEvents.filter { it.recommendSku in soldSkus }
            .groupBy { it.recommendSku }
            .forEach { (sku, group) ->
                if (group.any { it.outcome == RecommendationOutcome.rejected || it.outcome == RecommendationOutcome.expired }) return@forEach
                val alreadyBound = pending.findFirstBySourceSaleIdAndSku(sale.id, sku)
                if (alreadyBound != null) return@forEach
                val existingLinks = group.mapNotNull { it.pendingBonusId }.distinct()
                // A broken or terminal reservation is an audit problem, not permission to create another.
                val existing = if (existingLinks.size == 1) pending.findById(existingLinks.single()).orElse(null) else null
                if (existingLinks.isNotEmpty() && (existing == null || existing.status != PendingBonusStatus.awaiting_receipt ||
                        existing.pharmacistId != sale.pharmacistId || existing.pharmacyId != sale.pharmacyId || existing.sku != sku ||
                        existing.sourceSaleId != null)) return@forEach
                if (group.any { it.outcome == RecommendationOutcome.accepted && it.pendingBonusId == null }) return@forEach
                val winner = group.filter { event ->
                    val displayed = event.displayedAt
                    val sameReservation = existing == null || (event.pendingBonusId == existing.id &&
                        event.bonus == existing.bonus && event.expectedAmount == existing.expectedAmount && event.ruleId == existing.ruleId)
                    sameReservation && event.bonus > 0 && event.expectedAmount > 0 && !event.shownAt.isBefore(cutoff) &&
                        displayed != null && !displayed.isBefore(cutoff) &&
                        !displayed.isAfter(latestEvidenceAt) &&
                        !displayed.isBefore(event.shownAt.minusSeconds(300)) &&
                        !displayed.isAfter(sale.printedAt) &&
                        Duration.between(displayed, sale.printedAt) <= Duration.ofMinutes(30)
                }.maxWithOrNull(compareBy(
                    { if (it.pendingBonusId != null) 1 else 0 }, { it.displayedAt }, { it.shownAt }, { it.id },
                )) ?: return@forEach

                val bonus = existing?.also {
                    it.sourceSaleId = sale.id
                    pending.save(it)
                } ?: pendingBonusService.register(
                    pharmacistId = sale.pharmacistId, sku = sku, productName = winner.recommendName,
                    expectedAmount = winner.expectedAmount, bonus = winner.bonus, ruleId = winner.ruleId,
                    sourceSaleId = sale.id, occurredAt = winner.displayedAt, expectedPharmacyId = sale.pharmacyId,
                )
                winner.pendingBonusId = bonus.id
                events.save(winner)
                reconcile.ingestVerifiedRecommendationSale(bonus.id, sale.id)
            }
    }

    private fun lockSession(sessionId: String) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "recommendation:$sessionId")
    }

    private companion object {
        val TRUSTED_SELLER_SOURCES = setOf("posm_internal", "standardn_explicit_mapping")
    }
}
