package kz.epharm.eshop

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EshopCatalogMapperTest {
    private val mapper = EshopCatalogMapper("https://epharm.inkar.kz")
    private val json = ObjectMapper()

    @Test
    fun `site identity and Russian category label are preserved, image becomes same-origin proxy`() {
        val raw = json.readTree(
            """{"id":"prod_Daribar_1","sku":"D-1","variantId":"var_1","name":"Ибупрофен","brand":"Brand","mnn":"Ибупрофен","categoryHandles":["lekarstva-i-bady"],"image":"/api/media/daribar?sku=D-1"}""",
        )
        val item = mapper.map("D-1", "prod_Daribar_1", "var_1", raw, BigDecimal("12.49"))
        assertEquals("prod_Daribar_1", item.id)
        assertEquals("Лекарства", item.categories.single().name)
        assertEquals("https://epharm.inkar.kz/api/media/eshop?sku=D-1", item.thumbnail)
        assertEquals(12.0, item.variants.single().calculatedPrice?.calculatedAmount)
        assertEquals(13.0, mapper.map("D-1", "prod_Daribar_1", "var_1", raw,
            BigDecimal("12.50")).variants.single().calculatedPrice?.calculatedAmount)
    }

    @Test
    fun `envelope product id mismatch is rejected`() {
        val raw = json.readTree("""{"id":"wrong","sku":"D-1","name":"Товар"}""")
        assertThrows(IllegalArgumentException::class.java) {
            mapper.map("D-1", "prod_Daribar_1", "var_1", raw, null)
        }
    }
}
