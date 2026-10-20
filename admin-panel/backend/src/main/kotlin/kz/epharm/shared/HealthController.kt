package kz.epharm.shared

import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.storefront.client.StorefrontCatalogClient
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
    private val catalogSnapshot: MedusaCatalogSnapshotRepository? = null,
    private val storefrontCatalog: StorefrontCatalogClient? = null,
) {
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val snapshot = catalogSnapshot?.syncState()
        return mapOf(
            "service" to service,
            "version" to version,
            "releaseId" to version,
            "commit" to commit,
            "status" to "ok",
            // Selection only, not an upstream availability assertion; smoke still probes catalogue/search.
            "catalogSource" to if (storefrontCatalog?.active == true) "website" else "historical_snapshot",
            "catalogSnapshot" to mapOf(
                "ready" to (snapshot?.completedAt != null && snapshot.productCount > 0),
                "products" to (snapshot?.productCount ?: 0),
                "completedAt" to snapshot?.completedAt?.toString(),
            ),
            "timestamp" to Instant.now().toString(),
        )
    }
}
