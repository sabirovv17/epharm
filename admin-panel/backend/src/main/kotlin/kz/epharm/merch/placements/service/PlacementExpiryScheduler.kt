package kz.epharm.merch.placements.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Enable only after the single-writer cutover; the legacy CRM worker must be disabled first. */
@Component
@ConditionalOnProperty(name=["app.merch.placements.expiry-enabled"],havingValue="true")
class PlacementExpiryScheduler(private val placements: PlacementService) {
    @Scheduled(fixedDelayString="\${app.merch.placements.expiry-delay-ms:60000}")
    fun tick() { placements.expireDue() }
}
