package kz.epharm.medusa.client

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MedusaEndpointPolicyTest {
    @Test
    fun `disabled integration ignores absent credentials`() {
        assertThat(validate(enabled = false, baseUrl = "")).isNull()
    }

    @Test
    fun `enabled integration accepts an HTTPS origin`() {
        assertThat(validate(baseUrl = "https://medusa.example.kz/")).isEqualTo("https://medusa.example.kz")
    }

    @Test
    fun `enabled integration fails closed on missing identifiers`() {
        assertThatThrownBy { validate(baseUrl = "https://medusa.example.kz", publishableKey = "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MEDUSA_PUBLISHABLE_KEY")
    }

    @Test
    fun `remote cleartext and non-origin URLs are rejected`() {
        listOf(
            "http://78.140.246.238:9000",
            "http://medusa.example.kz",
            "https://user:password@medusa.example.kz",
            "https://medusa.example.kz/store",
            "https://medusa.example.kz?token=secret",
        ).forEach { unsafe ->
            assertThatThrownBy { validate(baseUrl = unsafe) }
                .describedAs(unsafe)
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `loopback HTTP remains available for local development`() {
        assertThat(validate(baseUrl = "http://localhost:9000"))
            .isEqualTo("http://localhost:9000")
        assertThat(validate(baseUrl = "http://127.0.0.1:9000/"))
            .isEqualTo("http://127.0.0.1:9000")
    }

    private fun validate(
        enabled: Boolean = true,
        baseUrl: String,
        publishableKey: String = "pk_test",
    ) = MedusaEndpointPolicy.validate(
        enabled = enabled,
        rawBaseUrl = baseUrl,
        publishableKey = publishableKey,
        salesChannelId = "sc_test",
        regionId = "reg_test",
        connectTimeoutMs = 2_000,
        readTimeoutMs = 6_000,
    )
}
