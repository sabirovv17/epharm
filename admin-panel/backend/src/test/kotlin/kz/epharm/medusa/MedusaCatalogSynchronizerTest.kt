package kz.epharm.medusa

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.medusa.client.MedusaClient
import kz.epharm.medusa.dto.MedusaProduct
import kz.epharm.medusa.dto.MedusaProductListResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MedusaCatalogSynchronizerTest {
    private val medusa = mockk<MedusaClient>()
    private val snapshot = mockk<MedusaCatalogSnapshotRepository>(relaxed = true)
    private val cache = MedusaCatalogCache(ttlSeconds = 300)

    @Test
    fun `полный снимок публикуется только после загрузки всех страниц`() {
        every { medusa.listProductsForSnapshot(2, 0) } returns page(
            count = 3,
            offset = 0,
            MedusaProduct(id = "p1", title = "Один"),
            MedusaProduct(id = "p2", title = "Два"),
        )
        every { medusa.listProductsForSnapshot(2, 2) } returns page(
            count = 3,
            offset = 2,
            MedusaProduct(id = "p3", title = "Три"),
        )
        val synchronizer = synchronizer(pageSize = 2)

        assertEquals(3, synchronizer.refreshNow())

        verify(exactly = 1) { snapshot.beginSync() }
        verify(exactly = 1) { snapshot.upsertPage(match { it.map(MedusaProduct::id) == listOf("p1", "p2") }, 0, any()) }
        verify(exactly = 1) { snapshot.upsertPage(match { it.map(MedusaProduct::id) == listOf("p3") }, 2, any()) }
        verify(exactly = 1) { snapshot.completeSync(any(), 3) }
        verify(exactly = 0) { snapshot.failSync(any(), any()) }
        synchronizer.close()
    }

    @Test
    fun `ошибка следующей страницы сохраняет последнюю полную версию`() {
        every { medusa.listProductsForSnapshot(2, 0) } returns page(
            count = 3,
            offset = 0,
            MedusaProduct(id = "p1"),
            MedusaProduct(id = "p2"),
        )
        every { medusa.listProductsForSnapshot(2, 2) } throws IllegalStateException("upstream timeout")
        val synchronizer = synchronizer(pageSize = 2)

        assertThrows(IllegalStateException::class.java) { synchronizer.refreshNow() }

        verify(exactly = 0) { snapshot.completeSync(any(), any()) }
        verify(exactly = 1) { snapshot.failSync(any(), match { it.contains("timeout") }) }
        synchronizer.close()
    }

    private fun synchronizer(pageSize: Int) = MedusaCatalogSynchronizer(
        medusa = medusa,
        snapshot = snapshot,
        cache = cache,
        pageSize = pageSize,
        fetchWorkers = 1,
        refreshSeconds = 3_600,
        retrySeconds = 300,
    )

    private fun page(count: Int, offset: Int, vararg products: MedusaProduct) =
        MedusaProductListResponse(
            products = products.toList(),
            count = count,
            offset = offset,
            limit = products.size,
        )
}
