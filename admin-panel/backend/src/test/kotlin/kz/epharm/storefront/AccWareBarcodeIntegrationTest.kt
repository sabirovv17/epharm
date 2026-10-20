package kz.epharm.storefront

import com.sun.net.httpserver.HttpServer
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.medusa.dto.scanBarcodes
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class AccWareBarcodeIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_ware_alias_test")
            .withUsername("epharm").withPassword("epharm_test").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var taxonomy: AccCatalogTaxonomy
    @Autowired private lateinit var references: StorefrontProductReferenceRepository

    private val ware = "07ee2a95-3574-4fb4-b75b-e3311dd759a7"

    private fun activate(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO acc_catalog_snapshots(id,sha256,source_name,item_count,barcode_count) VALUES (?,?,?,0,0)", id, id.toString(), "isolated-test")
        jdbc.update("UPDATE acc_catalog_state SET active_snapshot_id=? WHERE singleton=1", id)
        return id
    }

    private fun alias(snapshot: UUID, sourceWare: String, barcode: String) {
        jdbc.update("INSERT INTO acc_catalog_barcodes(snapshot_id,ware_id,barcode) VALUES (?,?,?)", snapshot, sourceWare, barcode)
    }

    @Test
    fun `exact case-insensitive WARE lookup excludes barcodes shared with unrequested products and inactive snapshots`() {
        val old = activate()
        alias(old, ware, "8901152233259")
        val current = activate()
        alias(current, ware.uppercase(), "8901148217805")
        alias(current, ware.uppercase(), "8901148250666")
        alias(current, "other-ware", "8901148250666")

        assertThat(taxonomy.unambiguousBarcodesForWareIds(listOf(" $ware ", ware.uppercase(), "unknown")))
            .containsOnlyKeys(ware)
            .containsEntry(ware, listOf("8901148217805"))
        assertThat(taxonomy.unambiguousBarcodesForWareIds(listOf("113525", ware.replace("-", ""), "Цитрамон"))).isEmpty()
    }

    @Test
    fun `WARE lookup supports bounded batches without dropping the last requested item`() {
        val current = activate()
        val requested = (0..200).map { UUID(0L, it.toLong() + 1L).toString() }
        alias(current, requested.last().uppercase(), "8888110284730")

        assertThat(taxonomy.unambiguousBarcodesForWareIds(requested))
            .containsOnlyKeys(requested.last()).containsEntry(requested.last(), listOf("8888110284730"))
    }

    @Test
    fun `website WARE aliases survive durable cache restart and are removed after the active mapping changes`() {
        val snapshot = activate()
        alias(snapshot, ware.uppercase(), "8901148217805")
        alias(snapshot, ware.uppercase(), "8901148250666")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val product = """{"id":"prod_DaribarZDg5MjkxYzgtMmEyNy00YWU1LTgyM2ItMjZmNWRiY2ZhMDZj","name":"Омез 20 мг капсулы № 30","slug":"exact-ware-item","wareId":"$ware","variants":[{"id":"variant-Daribar","sku":"d89291c8-2a27-4ae5-823b-26f5dbcfa06c","price":1285}]}"""
        fun reply(path: String, json: String) = server.createContext(path) { exchange ->
            val body = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        reply("/api/catalog", """{"products":[$product],"count":1}""")
        reply("/api/product/exact-ware-item", """{"product":$product}""")
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            fun client() = StorefrontCatalogClient(true, origin, 1_000, 1_000, references, taxonomy)
            val first = client().listProducts(limit = 50, offset = 0).products.single()
            assertThat(first.scanBarcodes()).containsExactly("8901148217805", "8901148250666")
            assertThat(first.metadata).containsEntry("barcode_source_complete", true)
            assertThat(client().getProduct(first.id)?.scanBarcodes()).containsExactlyElementsOf(first.scanBarcodes())
            assertThat(client().cachedProduct(first.id)?.scanBarcodes()).containsExactlyElementsOf(first.scanBarcodes())
            assertThat(first.metadata).doesNotContainKey("ipart_id")

            activate()
            val removed = client().cachedProduct(first.id)
            assertThat(removed?.scanBarcodes()).isEmpty()
            assertThat(removed?.metadata).containsEntry("barcode_source_complete", true)
            jdbc.update("UPDATE acc_catalog_state SET active_snapshot_id=NULL WHERE singleton=1")
            assertThat(client().cachedProduct(first.id)?.metadata).containsEntry("barcode_source_complete", false)
        } finally {
            server.stop(0)
        }
    }
}
