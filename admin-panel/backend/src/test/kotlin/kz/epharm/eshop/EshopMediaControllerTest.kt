package kz.epharm.eshop

import io.mockk.every
import io.mockk.mockk
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EshopMediaControllerTest {
    private val catalog = mockk<EshopCatalogSnapshotRepository>().also {
        every { it.hasPublishedSku(any()) } returns false
    }

    @Test
    fun `media origin is pinned to the public HTTPS shop`() {
        assertThrows(IllegalStateException::class.java) {
            EshopMediaController("http://127.0.0.1:8080", catalog)
        }
        assertThrows(IllegalStateException::class.java) {
            EshopMediaController("https://aptekasosklada.kz.evil.test", catalog)
        }
    }

    @Test
    fun `image route rejects URL-shaped SKU before network access`() {
        val controller = EshopMediaController("https://aptekasosklada.kz", catalog)
        assertThrows(AppException::class.java) { controller.image("https://internal.example/?x=1") }
        assertThrows(AppException::class.java) { controller.image("A&B") }
        assertThrows(AppException::class.java) { controller.image("SKU/segment") }
        assertThrows(AppException::class.java) { controller.image("SKU-123") }
    }
}
