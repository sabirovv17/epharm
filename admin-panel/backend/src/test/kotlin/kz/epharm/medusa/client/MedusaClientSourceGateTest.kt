package kz.epharm.medusa.client

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.MedusaCatalogCache
import kz.epharm.medusa.MedusaCatalogSnapshotRepository
import kz.epharm.medusa.MedusaCatalogSynchronizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class MedusaClientSourceGateTest {
    @Test
    fun `website source disables every retired remote method even with a stale enabled flag`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = MedusaClient(
                enabled = true,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                publishableKey = "test-key",
                salesChannelId = "test-channel",
                regionId = "test-region",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 1_000,
                snapshotReadTimeoutMs = 1_000,
                storefrontCatalogEnabled = true,
            )

            assertThat(client.active).isFalse()
            assertThat(client.listProducts().products).isEmpty()
            assertThat(client.listProductsForSnapshot(10, 0).products).isEmpty()
            assertThat(client.getProduct("historical-id")).isNull()
            assertThat(client.getProductPharmacies("historical-id")).isNull()
            assertThat(client.listCategories().productCategories).isEmpty()
            assertThat(requests.get()).isZero()

            val snapshot = mockk<MedusaCatalogSnapshotRepository>()
            val synchronizer = MedusaCatalogSynchronizer(
                client, snapshot, MedusaCatalogCache(0), pageSize = 100,
                refreshSeconds = 3_600, retrySeconds = 300,
            )
            try {
                synchronizer.scheduledRefresh()
                verify(exactly = 0) { snapshot.syncState() }
                verify(exactly = 0) { snapshot.beginSync() }
            } finally {
                synchronizer.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `website source ignores incomplete retired configuration instead of requiring old credentials`() {
        val client = MedusaClient(true, "", "", "", "", 0, 0, 0, storefrontCatalogEnabled = true)

        assertThat(client.active).isFalse()
        assertThat(client.listProductsForSnapshot(10, 0).products).isEmpty()
    }

    @Test
    fun `existing constructor remains usable for explicitly enabled legacy development fixtures`() {
        val client = MedusaClient(true, "http://127.0.0.1:9000", "test-key", "test-channel", "test-region", 1_000, 1_000, 1_000)

        assertThat(client.active).isTrue()
    }
}
