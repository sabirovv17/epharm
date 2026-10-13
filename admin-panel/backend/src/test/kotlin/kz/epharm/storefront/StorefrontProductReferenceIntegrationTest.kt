package kz.epharm.storefront

import com.sun.net.httpserver.HttpServer
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class StorefrontProductReferenceIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_storefront_refs_test")
            .withUsername("epharm").withPassword("epharm_test").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var references: StorefrontProductReferenceRepository

    @Test
    fun `website-only product survives client restart and refreshes price by its original handle`() {
        val detailRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/catalog") { exchange ->
            val body = """{"products":[{"id":"site-only","slug":"original-handle","name":"Товар","price":485,"description":"Описание"}],"count":1}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/product/original-handle") { exchange ->
            detailRequests.incrementAndGet()
            val body = """{"product":{"id":"site-only","slug":"original-handle","name":"Товар","price":510,"description":"Описание"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            fun newClient() = StorefrontCatalogClient(true, origin, 1_000, 1_000, references)
            newClient().listProducts(limit = 10, offset = 0)
            assertThat(references.find("https://another-origin.example", "site-only")).isNull()
            val restarted = newClient()
            assertThat(restarted.cachedProduct("site-only")?.variants?.single()?.calculatedPrice?.calculatedAmount)
                .isEqualTo(485.0)
            assertThat(restarted.getProduct("site-only", "obsolete-medusa-handle")?.variants?.single()?.calculatedPrice?.calculatedAmount)
                .isEqualTo(510.0)
            assertThat(detailRequests.get()).isEqualTo(1)
            assertThat(newClient().cachedProduct("site-only")?.variants?.single()?.calculatedPrice?.calculatedAmount)
                .isEqualTo(510.0)
        } finally {
            server.stop(0)
        }
    }
}
