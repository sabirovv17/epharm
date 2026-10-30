package kz.epharm.merch.automation

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Enable only after source projection validation and the explicit single-writer cutover. */
@Component
@ConditionalOnProperty(name=["app.merch.automation.enabled"],havingValue="true")
class WeeklyPlacementScheduler(private val engine:WeeklyPlacementAutomation) {
    @Scheduled(fixedDelayString="\${app.merch.automation.delay-ms:300000}",initialDelayString="\${app.merch.automation.initial-delay-ms:60000}")
    fun tick() {
        try {
            val runs=engine.tick()
            val created=runs.sumOf {it.itemsCreated};val diagnostics=runs.sumOf {it.diagnostics.size}
            if(created>0 || diagnostics>0) log.info("weekly_automation plans={} createdItems={} diagnostics={}",runs.size,created,diagnostics)
        } catch(error:Exception) {log.error("Weekly automation failed; existing work was preserved; failureType={}",error.javaClass.simpleName)}
    }
    private val log=LoggerFactory.getLogger(javaClass)
}
