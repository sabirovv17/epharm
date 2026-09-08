package kz.epharm.shared

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class HealthController(
    @Value("\${spring.application.name:epharm-backend}") private val service: String,
    @Value("\${app.version:dev}") private val version: String,
    @Value("\${app.commit:unknown}") private val commit: String,
) {
    @GetMapping("/health")
    fun health(): Map<String, Any> = mapOf(
        "service" to service,
        "version" to version,
        "releaseId" to version,
        "commit" to commit,
        "status" to "ok",
        "timestamp" to Instant.now().toString(),
    )
}
