package kz.epharm.merch.automation

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

data class PlacementApprovedForAutomation(val planId:UUID)

/** Database/source imports never publish this event. Only a committed, policy-checked native approval does. */
@Component
@ConditionalOnProperty(name=["app.merch.automation.enabled"],havingValue="true")
class PlacementApprovalAutomation(private val engine:WeeklyPlacementAutomation) {
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    fun approved(event:PlacementApprovedForAutomation) {
        try {
            val result=engine.generateForPlan(event.planId)
            if(result.diagnostics.isNotEmpty()) log.warn("Approved placement automation requires attention plan={} diagnostics={}",event.planId,
                result.diagnostics.map {it.reason}.distinct())
        } catch(error:Exception) {
            // Approval is already committed. The per-plan transaction rolled back and the scheduled writer can retry it.
            log.error("Approved placement automation failed plan={} failureType={}; no task completion or credit was recorded",event.planId,error.javaClass.simpleName)
        }
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
