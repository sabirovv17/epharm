package kz.epharm.medusa

import jakarta.annotation.PreDestroy
import kz.epharm.medusa.client.MedusaClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds the durable local catalogue read model without blocking HTTP requests.
 *
 * A generation is crawled page-by-page and published atomically only after the
 * number of unique products matches Medusa's count.  Failed/incomplete refreshes
 * leave the previous generation available to catalogue and promo search.
 */
@Component
class MedusaCatalogSynchronizer(
    private val medusa: MedusaClient,
    private val snapshot: MedusaCatalogSnapshotRepository,
    private val cache: MedusaCatalogCache,
    @Value("\${app.medusa.snapshot-page-size:100}") pageSize: Int,
    @Value("\${app.medusa.snapshot-fetch-workers:1}") fetchWorkers: Int = 1,
    @Value("\${app.medusa.snapshot-refresh-seconds:3600}") private val refreshSeconds: Long,
    @Value("\${app.medusa.snapshot-retry-seconds:300}") private val retrySeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pageSize = pageSize.coerceIn(1, 500)
    // The legacy origin becomes dramatically slower with concurrent deep-offset
    // requests. Keep production serial; the knob exists only for a stronger future origin.
    private val fetchWorkers = fetchWorkers.coerceIn(1, 2)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "medusa-catalog-sync").apply { isDaemon = true }
    }
    private val fetchExecutor = Executors.newFixedThreadPool(this.fetchWorkers) { runnable ->
        Thread(runnable, "medusa-catalog-fetch").apply { isDaemon = true }
    }

    /** Fast scheduler tick; the actual crawl runs on its own daemon thread. */
    @Scheduled(
        initialDelayString = "\${app.medusa.snapshot-initial-delay-ms:5000}",
        fixedDelayString = "\${app.medusa.snapshot-poll-ms:60000}",
    )
    fun scheduledRefresh() {
        if (!medusa.active || !refreshDue() || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                refreshNow()
            } catch (_: Exception) {
                // refreshNow already records and logs the failure. Keep the
                // scheduler worker alive; the retry window controls the next run.
            } finally {
                running.set(false)
            }
        }
    }

    internal fun refreshNow(): Int {
        val started = Instant.now()
        val syncId = UUID.randomUUID()
        return try {
            snapshot.beginSync()
            val first = medusa.listProductsForSnapshot(limit = pageSize, offset = 0)
            val expectedCount = first.count
            check(expectedCount in 1..MAX_CATALOG_PRODUCTS) {
                "Medusa reported invalid catalogue size: $expectedCount"
            }
            validatePage(first.products.size, offset = 0, expectedCount)
            snapshot.upsertPage(first.products, offset = 0, syncId)

            val offsets = (pageSize until expectedCount step pageSize).toList()
            for (batch in offsets.chunked(fetchWorkers)) {
                val pages = batch.associateWith { offset ->
                    CompletableFuture.supplyAsync(
                        { medusa.listProductsForSnapshot(limit = pageSize, offset = offset) },
                        fetchExecutor,
                    )
                }
                for ((offset, future) in pages) {
                    val response = await(future)
                    validatePage(response.products.size, offset, expectedCount)
                    snapshot.upsertPage(response.products, offset, syncId)
                }
            }

            snapshot.completeSync(syncId, expectedCount)
            cache.clear()
            log.info(
                "Medusa catalogue snapshot refreshed: {} products in {} ms",
                expectedCount,
                Duration.between(started, Instant.now()).toMillis(),
            )
            expectedCount
        } catch (e: Exception) {
            runCatching { snapshot.failSync(syncId, e.message ?: e.javaClass.simpleName) }
                .onFailure { cleanup ->
                    log.warn("Could not clean failed Medusa snapshot generation: {}", cleanup.message)
                }
            log.warn(
                "Medusa catalogue snapshot refresh failed; keeping last complete snapshot: {}",
                e.message,
            )
            throw e
        }
    }

    private fun validatePage(actualSize: Int, offset: Int, expectedCount: Int) {
        val expectedSize = minOf(pageSize, expectedCount - offset)
        check(actualSize == expectedSize) {
            "Incomplete Medusa page at offset $offset: expected $expectedSize products, received $actualSize"
        }
    }

    private fun <T> await(future: CompletableFuture<T>): T = try {
        future.get()
    } catch (e: ExecutionException) {
        throw (e.cause as? Exception ?: e)
    }

    private fun refreshDue(now: Instant = Instant.now()): Boolean {
        val state = snapshot.syncState()
        val lastAttemptFailed = state.startedAt != null &&
            (state.completedAt == null || state.startedAt.isAfter(state.completedAt))
        val last = if (lastAttemptFailed) state.startedAt else state.completedAt
        val waitSeconds = if (lastAttemptFailed || state.completedAt == null) retrySeconds else refreshSeconds
        return last == null || !last.plusSeconds(waitSeconds.coerceAtLeast(1)).isAfter(now)
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
        fetchExecutor.shutdownNow()
    }

    companion object {
        private const val MAX_CATALOG_PRODUCTS = 1_000_000
    }
}
