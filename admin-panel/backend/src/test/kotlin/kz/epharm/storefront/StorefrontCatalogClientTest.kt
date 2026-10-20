package kz.epharm.storefront

import com.sun.net.httpserver.HttpServer
import kz.epharm.storefront.client.StorefrontCatalogClient
import kz.epharm.storefront.dto.StorefrontProduct
import kz.epharm.storefront.dto.StorefrontVariant
import kz.epharm.storefront.dto.toMedusaProduct
import kz.epharm.shared.error.AppException
import kz.epharm.medusa.dto.scanBarcodes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class StorefrontCatalogClientTest {
    @Test
    fun `retains explicit top-level barcode aliases without a price or variants`() {
        val mapped = StorefrontProduct(
            id = "site-no-price", wareId = "56d37e88-8449-4933-9f64-aba2553e3760",
            barcode = "8888110284730", barcodes = listOf("8888110284730", "4810201006352"),
        ).toMedusaProduct("https://www.aptekasosklada.kz")

        assertThat(mapped.variants).isEmpty()
        assertThat(mapped.scanBarcodes()).containsExactly("8888110284730", "4810201006352")
        assertThat(mapped.metadata).containsEntry("ware_id", "56d37e88-8449-4933-9f64-aba2553e3760")
    }

    @Test
    fun `collects aliases from same-item variants without treating the website sku as an ipart id`() {
        val mapped = StorefrontProduct(
            id = "same-item", barcode = "4603276001779",
            variants = listOf(
                StorefrontVariant(id = "one", sku = "daribar-uuid", title = "10 шт", barcode = "4603276001779_4603276013765"),
                StorefrontVariant(id = "two", sku = "daribar-uuid", title = "10 шт", barcodes = listOf("4603276013765", "8888110284730")),
            ),
        ).toMedusaProduct("https://www.aptekasosklada.kz")

        assertThat(mapped.scanBarcodes()).containsExactly("4603276001779", "4603276013765", "8888110284730")
        assertThat(mapped.metadata).doesNotContainKey("ipart_id")
    }

    @Test
    fun `does not flatten codes of variants with different packaging or sku`() {
        val mapped = StorefrontProduct(
            id = "different-items", barcode = "8888110284730",
            variants = listOf(
                StorefrontVariant(id = "one", sku = "pack-10", title = "10 шт", barcode = "4603276001779"),
                StorefrontVariant(id = "two", sku = "pack-20", title = "20 шт", barcode = "4603276013765"),
            ),
        ).toMedusaProduct("https://www.aptekasosklada.kz")

        assertThat(mapped.scanBarcodes()).containsExactly("8888110284730")
    }

    @Test
    fun `maps website price metadata and relative media to mobile catalogue model`() {
        val mapped = StorefrontProduct(
            id = "prod_123",
            slug = "paracetamol-500",
            name = "Парацетамол 500 мг",
            brand = "Inkar",
            categorySlug = "pain-relief",
            categoryHandles = listOf("medicines", "pain-relief"),
            price = 1_290.0,
            image = "/api/media/medusa?path=%2Fstatic%2Fparacetamol.jpg",
            prescription = false,
            barcode = "4870000000001",
            variants = listOf(StorefrontVariant(id = "variant_123", barcode = "4870000000001")),
        ).toMedusaProduct("https://shop.example.kz")

        assertThat(mapped.id).isEqualTo("prod_123")
        assertThat(mapped.thumbnail).isEqualTo("https://shop.example.kz/api/media/medusa?path=%2Fstatic%2Fparacetamol.jpg")
        assertThat(mapped.variants.single().calculatedPrice?.calculatedAmount).isEqualTo(1_290.0)
        assertThat(mapped.variants.single().calculatedPrice?.currencyCode).isEqualTo("KZT")
        assertThat(mapped.metadata).containsEntry("brand_name", "Inkar").containsEntry("rx_otc", "otc")
    }

    @Test
    fun `placeholder price remains absent`() {
        val mapped = StorefrontProduct(id = "prod_no_price", name = "Без цены", price = 0.0, priceTbd = true)
            .toMedusaProduct("https://shop.example.kz")
        assertThat(mapped.variants).isEmpty()
    }

    @Test
    fun `url gate accepts trusted origins and rejects arbitrary internal hosts`() {
        assertThat(StorefrontCatalogClient.validateBaseUrl(true, "https://shop.example.kz/", 2_000, 8_000))
            .isEqualTo("https://shop.example.kz")
        assertThat(StorefrontCatalogClient.validateBaseUrl(true, "http://127.0.0.1:3000", 2_000, 8_000))
            .isEqualTo("http://127.0.0.1:3000")
        assertThatIllegalArgumentException().isThrownBy {
            StorefrontCatalogClient.validateBaseUrl(true, "http://10.10.1.80", 2_000, 8_000)
        }
        assertThatIllegalArgumentException().isThrownBy {
            StorefrontCatalogClient.validateBaseUrl(true, "https://shop.example.kz/api", 2_000, 8_000)
        }
        assertThat(StorefrontCatalogClient.validateBaseUrl(false, "", 2_000, 8_000)).isNull()
    }

    @Test
    fun `reads website catalog response and rejects mismatched product detail id`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/catalog") { exchange ->
            val body = """{"products":[{"id":"prod_1","name":"Тест","slug":"test","price":900,"wareId":"56d37e88-8449-4933-9f64-aba2553e3760","barcodes":["8888110284730","4810201006352"],"variants":[{"id":"v1","barcode":"8888110284730","barcodes":["4810201001876"]}]}],"count":1}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/product/test") { exchange ->
            val body = """{"product":{"id":"wrong_id","name":"Чужой товар"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = StorefrontCatalogClient(true, "http://127.0.0.1:${server.address.port}", 1_000, 1_000)
            val page = client.listProducts(limit = 10, offset = 0)
            assertThat(page.products.single().id).isEqualTo("prod_1")
            assertThat(page.products.single().scanBarcodes()).containsExactly("8888110284730", "4810201006352", "4810201001876")
            assertThat(page.products.single().metadata).containsEntry("barcode_source_complete", true)
            assertThat(page.count).isEqualTo(1)
            // The list item has no description, so detail must call the upstream endpoint.
            assertThat(client.getProduct("prod_1", "test")).isNull()
            assertThat(client.cachedProduct("prod_1")?.id).isEqualTo("prod_1")
            assertThat(client.cachedProduct("wrong_id")).isNull()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `empty unfiltered first page is treated as outage rather than replacing snapshot`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/catalog") { exchange ->
            val body = """{"products":[],"count":0}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = StorefrontCatalogClient(true, "http://127.0.0.1:${server.address.port}", 1_000, 1_000)
            assertThatThrownBy { client.listProducts(limit = 10, offset = 0) }.isInstanceOf(AppException::class.java)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `category ids returned to mobile are filter handles`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/category-tree") { exchange ->
            val body = """[{"id":"database-root","handle":"medicines","name":"Лекарства","children":[{"id":"database-child","handle":"pain-relief","name":"От боли"}]}]"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = StorefrontCatalogClient(true, "http://127.0.0.1:${server.address.port}", 1_000, 1_000)
            val categories = client.listCategories()
            assertThat(categories.map { it.id }).containsExactly("medicines", "pain-relief")
            assertThat(categories[1].parentCategoryId).isEqualTo("medicines")
        } finally {
            server.stop(0)
        }
    }
}
