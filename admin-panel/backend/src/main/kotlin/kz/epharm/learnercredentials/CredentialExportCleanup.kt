package kz.epharm.learnercredentials

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Hard TTL is checked at download; idle cleanup removes at most 100 encrypted jobs per minute. */
@Component
class CredentialExportCleanup(private val exports:LearnerCredentialExportService) {
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    fun clean() {exports.cleanup()}
}
