package kz.epharm

import io.mockk.every
import io.mockk.mockk
import kz.epharm.shared.HealthController
import kz.epharm.storefront.client.StorefrontCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HealthCatalogSourceTest {
    @Test
    fun `health selects active website without claiming a completed historical snapshot`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns true

        val health = HealthController("backend", "v1.2.3", "commit", storefrontCatalog = website).health()

        assertThat(health["catalogSource"]).isEqualTo("website")
        assertThat((health["catalogSnapshot"] as Map<*, *>)["ready"]).isEqualTo(false)
    }

    @Test
    fun `health does not select a disabled or absent website source`() {
        val website = mockk<StorefrontCatalogClient>()
        every { website.active } returns false

        assertThat(HealthController("backend", "dev", "unknown", storefrontCatalog = website).health()["catalogSource"])
            .isEqualTo("historical_snapshot")
        assertThat(HealthController("backend", "dev", "unknown").health()["catalogSource"])
            .isEqualTo("historical_snapshot")
    }
}
