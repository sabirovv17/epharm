package kz.epharm.mobile.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.mobile.auth.service.LocalOtpProvider
import kz.epharm.mobile.auth.service.OtpProviderConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OtpProviderConfigTest {
    private val config = OtpProviderConfig()
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `dev mode всегда выбирает локальную заглушку без ключа`() {
        val provider = provider(devMode = true, providerName = "daribar", p1smsApiKey = "")
        assertThat(provider).isInstanceOf(LocalOtpProvider::class.java)
        assertThat(provider.id).isEqualTo("dev")
    }

    @Test
    fun `production p1sms без API key завершается ошибкой конфигурации`() {
        assertThatThrownBy { provider(devMode = false, providerName = "p1sms", p1smsApiKey = "") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("P1SMS_API_KEY")
    }

    @Test
    fun `production daribar не требует p1sms key`() {
        val provider = provider(devMode = false, providerName = "daribar", p1smsApiKey = "")
        assertThat(provider.id).isEqualTo("daribar")
    }

    @Test
    fun `daribar запрещает небезопасный HTTP base URL`() {
        assertThatThrownBy {
            provider(devMode = false, providerName = "daribar", daribarBaseUrl = "http://sms.test")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("HTTPS")
    }

    @Test
    fun `daribar запрещает credentials query и fragment в base URL`() {
        listOf(
            "https://user:secret@sms.test",
            "https://sms.test?token=secret",
            "https://sms.test#fragment",
        ).forEach { unsafeUrl ->
            assertThatThrownBy {
                provider(devMode = false, providerName = "daribar", daribarBaseUrl = unsafeUrl)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("HTTPS")
        }
    }

    private fun provider(
        devMode: Boolean,
        providerName: String,
        p1smsApiKey: String = "test-key",
        daribarBaseUrl: String = "https://sms.test",
    ) = config.otpProvider(
        objectMapper = objectMapper,
        devMode = devMode,
        providerName = providerName,
        daribarBaseUrl = daribarBaseUrl,
        daribarTimeoutMs = 10_000,
        p1smsBaseUrl = "https://p1sms.test",
        p1smsApiKey = p1smsApiKey,
        p1smsChannel = "digit",
        p1smsSender = "",
        p1smsTextTemplate = "Код входа ePharm: {code}",
        p1smsTimeoutMs = 10_000,
    )
}
