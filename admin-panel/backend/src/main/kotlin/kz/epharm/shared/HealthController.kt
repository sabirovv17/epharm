package kz.epharm.shared

import kz.epharm.eshop.EshopCatalogSnapshotRepository
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
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
    private val eshopSnapshot: EshopCatalogSnapshotRepository? = null,
    @Value("\${app.eshop.catalog.sync-enabled:false}") private val eshopSyncEnabled: Boolean = false,
    @Value("\${app.eshop.catalog.read-enabled:false}") private val eshopReadEnabled: Boolean = false,
    @Value("\${app.eshop.catalog.admin-read-enabled:false}") private val eshopAdminReadEnabled: Boolean = false,
) {
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val snapshot = catalogSnapshot?.syncState()
        val eshop = eshopSnapshot?.syncState()
        val activeReady = if (eshopReadEnabled) eshop?.ready == true else
            snapshot?.completedAt != null && snapshot.productCount > 0
        val activeCount = if (eshopReadEnabled) eshop?.productCount ?: 0 else snapshot?.productCount ?: 0
        val activeCompletedAt = if (eshopReadEnabled) eshop?.completedAt else snapshot?.completedAt
        return mapOf(
            "service" to service,
            "version" to version,
            "releaseId" to version,
            "commit" to commit,
            "status" to if ((eshopReadEnabled && !activeReady) ||
                (eshopAdminReadEnabled && eshop?.ready != true)) "degraded" else "ok",
            "catalogSnapshot" to mapOf(
                "ready" to activeReady,
                "products" to activeCount,
                "completedAt" to activeCompletedAt?.toString(),
            ),
            "eshopCatalogSnapshot" to mapOf(
                "syncEnabled" to eshopSyncEnabled,
                "readEnabled" to eshopReadEnabled,
                "adminReadEnabled" to eshopAdminReadEnabled,
                "ready" to (eshop?.ready == true),
                "products" to (eshop?.productCount ?: 0),
                "publishedProducts" to (eshop?.publishedCount ?: 0),
                "completedAt" to eshop?.completedAt?.toString(),
                "catalogGeneratedAt" to eshop?.catalogGeneratedAt?.toString(),
                "availabilityFinishedAt" to eshop?.availabilityFinishedAt?.toString(),
                "catalogRunId" to eshop?.catalogRunId,
                "availabilityRunId" to eshop?.availabilityRunId,
                "lastError" to eshop?.lastError,
            ),
            "timestamp" to Instant.now().toString(),
        )
    }
}
