package kz.epharm.eshop

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class EshopCatalogAliasCollisionTest {
    @Test
    fun `conflicting verified alias blocks active generation switch`() {
        val jdbc = mockk<JdbcTemplate>(relaxed = true)
        val generation = UUID.randomUUID()
        every { jdbc.queryForMap(any<String>(), generation) } returns
            mapOf("total" to 2, "published" to 2)
        every { jdbc.queryForObject(any<String>(), Boolean::class.java, generation) } returns true
        val repository = EshopCatalogSnapshotRepository(jdbc, mockk(), ObjectMapper())

        assertThrows(IllegalStateException::class.java) {
            repository.completeSync(generation, 2, "run-1", null, Instant.parse("2026-10-10T00:00:00Z"), null)
        }
        verify(exactly = 1) {
            jdbc.queryForObject(match {
                it.contains("p.public_id = a.alias_id") && it.contains("p.sku = a.alias_id") &&
                    it.contains("p.sku <> a.sku")
            }, Boolean::class.java, generation)
        }
        verify(exactly = 0) { jdbc.update(match { it.contains("SET active_generation") }, *anyVararg()) }
    }
}
