package kz.epharm.eshop

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.MedusaCatalogCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.scheduling.annotation.Scheduled
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class EshopCatalogSynchronizerTest {
    private val repository = mockk<EshopCatalogSnapshotRepository>(relaxed = true)
    private val sync = EshopCatalogSynchronizer(
        repository = repository,
        cache = MedusaCatalogCache(0),
        json = ObjectMapper(),
        syncEnabled = false,
        baseUrl = "",
        token = "",
        connectTimeoutMs = 3000,
        readTimeoutMs = 120000,
    )

    @Test
    fun `refresh seconds property resolves to millisecond scheduler delay`() {
        val scheduled = EshopCatalogSynchronizer::class.java
            .getMethod("scheduledRefresh").getAnnotation(Scheduled::class.java)
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource(
            "test", mapOf("app.eshop.catalog.refresh-seconds" to "900"),
        ))
        assertEquals("900000", environment.resolveRequiredPlaceholders(scheduled.fixedDelayString))
    }

    @Test
    fun `complete verified stream publishes exact decimal prices and nullable availability`() {
        val first = product("SKU-1", "prod_Daribar_1", "var_1", 12.49, true)
        val second = product("SKU-2", "prod_Daribar_2", "var_2", 12.50, true)
        val payload = stream(listOf(first, second))

        assertEquals(2, sync.ingest(ByteArrayInputStream(payload)))

        verify(exactly = 1) { repository.beginSync() }
        verify(exactly = 1) {
            repository.upsertBatch(any(), 0, match {
                it.size == 2 && it[0].priceAmount == BigDecimal("12.49") &&
                    it[1].priceAmount == BigDecimal("12.5") &&
                    it[0].productId == "prod_Daribar_1"
            })
        }
        verify(exactly = 1) {
            repository.completeSync(any(), 2, "catalog-run", null, any(), null)
        }
        verify(exactly = 0) { repository.failSync(any(), any()) }
        sync.close()
    }

    @Test
    fun `bad checksum never publishes a partial generation`() {
        val payload = stream(listOf(product("SKU-1", "prod_Daribar_1", "var_1", 12.0, true)))
            .toString(StandardCharsets.UTF_8).replace(Regex("[0-9a-f]{64}(?=\"})"), "0".repeat(64))
        assertThrows(IllegalStateException::class.java) {
            sync.ingest(ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8)))
        }
        verify(exactly = 0) { repository.completeSync(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { repository.failSync(any(), match { it.contains("checksum") }) }
        sync.close()
    }

    @Test
    fun `unpublished mismatch and duplicated SKU reject export before publish`() {
        val mismatch = stream(listOf(product("SKU-1", "prod_Daribar_1", "var_1", 12.0, false)))
        assertThrows(IllegalStateException::class.java) {
            sync.ingest(ByteArrayInputStream(mismatch))
        }
        val duplicate = stream(listOf(
            product("SKU-1", "prod_Daribar_1", "var_1", 12.0, true),
            product("SKU-1", "prod_Daribar_1", "var_1", 12.0, true),
        ))
        assertThrows(IllegalStateException::class.java) {
            sync.ingest(ByteArrayInputStream(duplicate))
        }
        verify(exactly = 0) { repository.completeSync(any(), any(), any(), any(), any(), any()) }
        sync.close()
    }

    private fun product(sku: String, id: String, variantId: String, price: Double, published: Boolean): String =
        """{"type":"product","sku":"$sku","productId":"$id","variantId":"$variantId","product":{"id":"$id","sku":"$sku","variantId":"$variantId","name":"Товар $sku"},"priceAmount":$price,"published":$published,"aliasIds":[]} """.trim()

    private fun stream(products: List<String>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val body = products.joinToString(separator = "\n", postfix = "\n")
        digest.update(body.toByteArray(StandardCharsets.UTF_8))
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val header = """{"type":"header","schemaVersion":1,"catalogRunId":"catalog-run","availabilityRunId":null,"catalogCount":${products.size},"catalogGeneratedAt":"2026-10-10T00:00:00Z","availabilityFinishedAt":null}"""
        val footer = """{"type":"footer","productCount":${products.size},"sha256":"$sha"}"""
        return "$header\n$body$footer\n".toByteArray(StandardCharsets.UTF_8)
    }
}
