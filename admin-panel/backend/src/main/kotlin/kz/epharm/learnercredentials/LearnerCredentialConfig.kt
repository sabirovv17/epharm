package kz.epharm.learnercredentials

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LearnerCredentialConfig(
    @Value("\${app.learner-credentials.enabled:false}") val enabled:Boolean,
    @Value("\${app.learner-credentials.key-file:}") val keyFile:String,
    @Value("\${app.learner-credentials.export-ttl-seconds:120}") val exportTtlSeconds:Long,
    @Value("\${app.learner-credentials.max-batch-size:25}") val maxBatchSize:Int,
    @Value("\${app.learner-credentials.max-exports-per-hour:4}") val maxExportsPerHour:Int,
) {
    init {require(exportTtlSeconds in 30..300 && maxBatchSize in 1..25 && maxExportsPerHour in 1..20) {"Invalid learner credential export limits"}}
}
